/*
 * Copyright 2021 University of Pennsylvania
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pennsieve.publish

import java.time.LocalDate
import java.util.UUID

import akka.actor.ActorSystem
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl.Sink
import cats.data._
import cats.implicits._
import com.amazonaws.services.s3.model.CopyObjectRequest
import com.pennsieve.core.utilities
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.domain.{
  CoreError,
  ExceptionError,
  PredicateError,
  ServiceError,
  ThrowableError
}
import com.pennsieve.models._
import com.pennsieve.publish.models._
import com.pennsieve.publish.utils.joinKeys
import com.typesafe.scalalogging.StrictLogging
import io.circe._
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.parser._
import io.circe.syntax._

import scala.concurrent.{ ExecutionContext, Future }
import scala.io.Source

object Publish extends StrictLogging {

  val PUBLISH_ASSETS_FILENAME: String = "publish.json"

  val GRAPH_ASSETS_FILENAME: String = "graph.json"

  val OUTPUT_FILENAME: String = "outputs.json"

  val README_FILENAME: String = "readme.md"

  val CHANGELOG_FILENAME: String = "changelog.md"

  val BANNER_FILENAME: String = "banner.jpg"

  val METADATA_FILENAME: String = "manifest.json"

  private def publishedAssetsKey(container: PublishContainer): String =
    joinKeys(
      Seq(
        container.publishedDatasetId.toString,
        container.version.toString,
        PUBLISH_ASSETS_FILENAME
      )
    )

  private def graphManifestKey(container: PublishContainer): String =
    joinKeys(
      Seq(
        container.publishedDatasetId.toString,
        container.version.toString,
        GRAPH_ASSETS_FILENAME
      )
    )

  private def outputKey(container: PublishContainer): String =
    joinKeys(
      Seq(
        container.publishedDatasetId.toString,
        container.version.toString,
        OUTPUT_FILENAME
      )
    )

  /**
    * These intermediate files are generated as part of publishing. After the `finalizeDataset` step runs, they should
    * be deleted.
    */
  def temporaryFiles: Seq[String] =
    Seq(PUBLISH_ASSETS_FILENAME, GRAPH_ASSETS_FILENAME)

  /**
    * Publish the assets of a dataset to S3. This includes exporting the dataset:
    *
    * - package sources
    * - banner image
    * - README file
    */
  def publishAssets(
    container: PublishContainer
  )(implicit
    executionContext: ExecutionContext,
    system: ActorSystem
  ): EitherT[Future, CoreError, Unit] =
    for {
      packagesResult <- PackagesExport
        .exportPackageSources(container)
        .toEitherT
      (externalIdToPackagePath, packageFileManifests) = packagesResult

      bannerResult <- copyBanner(container)
      (bannerKey, bannerFileManifest) = bannerResult

      readmeResult <- copyReadme(container)
      (readmeKey, readmeFileManifest) = readmeResult

      changelogResult <- copyChangelog(container)
      (changelogKey, changelogFilemanifest) = changelogresult

      assets = PublishAssetResult(
        externalIdToPackagePath = externalIdToPackagePath,
        packageManifests = packageFileManifests,
        bannerKey = bannerKey,
        bannerManifest = bannerFileManifest,
        readmeKey = readmeKey,
        readmeManifest = readmeFileManifest
        changelogKey = changelogKey,
        changelogManifest = changelogFileManifest
      )

      _ <- uploadToS3(container, publishedAssetsKey(container), assets.asJson)

    } yield ()

  /**
    * Finalizes a dataset for publishing by:
    *
    * - computing the total file size for the dataset
    * - recording metadata about the dataset
    *
    * TODO: move this into a lambda or a different task definition so we don't
    * have to reload the Postgres snapshot just to write these files.
    */
  def finalizeDataset(
    container: PublishContainer
  )(implicit
    executionContext: ExecutionContext,
    system: ActorSystem
  ): EitherT[Future, CoreError, Unit] =
    for {
      assets <- downloadFromS3[PublishAssetResult](
        container,
        publishedAssetsKey(container)
      )

      graph <- downloadFromS3[ExportedGraphResult](
        container,
        graphManifestKey(container)
      )

      _ = logger.info(s"Writing final manifest file: $METADATA_FILENAME")

      _ <- writeMetadata(
        container,
        assets.bannerManifest.manifest :: assets.readmeManifest.manifest :: assets.changelofManifest :: graph.manifests ++ assets.packageManifests
          .map(_.manifest)
      )

      totalSize <- computeTotalSize(container)

      _ = logger.info(s"Writing temporary file: $OUTPUT_FILENAME")

      _ <- writeJson(
        container,
        outputKey(container),
        TempPublishResults(
          readmeKey = assets.readmeKey,
          changelogKey = assets.changelogKey,
          bannerKey = assets.bannerKey,
          totalSize = totalSize
        ).asJson
      )

      _ = logger.info(s"Deleting temporary file: $PUBLISH_ASSETS_FILENAME")

      _ <- container.s3
        .deleteObject(container.s3Bucket, publishedAssetsKey(container))
        .toEitherT[Future]
        .leftMap[CoreError](t => ExceptionError(new Exception(t)))

      _ = logger.info(s"Deleting temporary file: $GRAPH_ASSETS_FILENAME")

      _ <- container.s3
        .deleteObject(container.s3Bucket, graphManifestKey(container))
        .toEitherT[Future]
        .leftMap[CoreError](t => ExceptionError(new Exception(t)))
    } yield ()

  /**
    * Write a JSON file to the publish bucket.
    */
  def writeJson(
    container: PublishContainer,
    s3Key: String,
    json: Json
  )(implicit
    executionContext: ExecutionContext
  ): EitherT[Future, CoreError, Unit] =
    EitherT
      .fromEither[Future](
        container.s3
          .putObject(container.s3Bucket, s3Key, dropNullPrinter(json))
          .leftMap(ThrowableError)
          .map(_ => ())
      )

  /**
    * Perform a blocking, single-part download from S3.
    */
  private def downloadFromS3[T: Decoder](
    container: PublishContainer,
    key: String
  )(implicit
    executionContext: ExecutionContext,
    system: ActorSystem
  ): EitherT[Future, CoreError, T] = {
    EitherT
      .fromEither[Future](
        container.s3
          .getObject(container.s3Bucket, key)
          .flatMap { obj =>
            {
              Either.catchNonFatal(
                Source.fromInputStream(obj.getObjectContent).mkString
              )
            }
          }
          .flatMap { s =>
            decode[T](s)
          }
          .leftMap(ThrowableError)
      )
  }

  /**
    * Perform a blocking, single-part upload to S3.
    */
  private def uploadToS3(
    container: PublishContainer,
    key: String,
    payload: Json
  )(implicit
    executionContext: ExecutionContext,
    system: ActorSystem
  ): EitherT[Future, CoreError, Unit] = {
    EitherT
      .fromEither[Future](
        container.s3
          .putObject(container.s3Bucket, key, dropNullPrinter(payload))
          .leftMap(ThrowableError)
          .map(_ => ())
      )
  }

  /**
    * Instead of exporting optional fields with a `null` literal,
    * remove the field entirely.
    */
  def dropNullPrinter(json: Json): String =
    Printer.spaces2.copy(dropNullValues = true).pretty(json)

  /**
    * Copy the dataset's banner image to the public assets bucket, and
    * the requester-pays publish bucket.
    */
  def copyBanner(
    container: PublishContainer
  )(implicit
    executionContext: ExecutionContext,
    system: ActorSystem
  ): EitherT[Future, CoreError, (String, FileManifest)] = {
    for {
      banner <- container.datasetAssetsManager
        .getBanner(container.dataset)
        .flatMap(
          EitherT.fromOption[Future](
            _,
            PredicateError("Dataset does not have a banner image"): CoreError
          )
        )

      // Preserve the file extension of the banner
      (_, extension) = utilities.splitFileName(banner.name)
      bannerName = s"banner$extension"
      bannerKey = joinKeys(container.s3Key, bannerName)

      // Copy to public asset bucket
      _ <- container.s3
        .copyObject(
          new CopyObjectRequest(
            banner.s3Bucket,
            banner.s3Key,
            container.s3AssetBucket,
            joinKeys(container.s3AssetKeyPrefix, bannerKey)
          )
        )
        .toEitherT[Future]
        .leftMap[CoreError](ThrowableError)

      // Copy to published dataset bucket
      _ <- container.s3
        .copyObject(
          new CopyObjectRequest(
            banner.s3Bucket,
            banner.s3Key,
            container.s3Bucket,
            bannerKey
          )
        )
        .toEitherT[Future]
        .leftMap[CoreError](ThrowableError)

      // Get size of file for manifest
      bannerSize <- container.s3
        .getObjectMetadata(banner.s3Bucket, banner.s3Key)
        .map(_.getContentLength())
        .toEitherT[Future]
        .leftMap[CoreError](ThrowableError)

    } yield
      (
        bannerKey,
        FileManifest(
          bannerName,
          bannerName,
          bannerSize,
          FileExtensions.fileTypeMap(extension.toLowerCase)
        )
      )
  }

  /**
    * Copy the dataset's Markdown readme to the public assets bucket, and
    * the requester-pays publish bucket.
    */
  def copyReadme(
    container: PublishContainer
  )(implicit
    executionContext: ExecutionContext,
    system: ActorSystem
  ): EitherT[Future, CoreError, (String, FileManifest)] = {

    val readmeKey = joinKeys(container.s3Key, README_FILENAME)

    for {
      readme <- container.datasetAssetsManager
        .getReadme(container.dataset)
        .flatMap(
          EitherT.fromOption[Future](
            _,
            PredicateError("Dataset does not have a readme"): CoreError
          )
        )

      // Copy to public asset bucket
      _ <- container.s3
        .copyObject(
          new CopyObjectRequest(
            readme.s3Bucket,
            readme.s3Key,
            container.s3AssetBucket,
            joinKeys(container.s3AssetKeyPrefix, readmeKey)
          )
        )
        .toEitherT[Future]
        .leftMap[CoreError](ThrowableError)

      // Copy to published dataset bucket
      _ <- container.s3
        .copyObject(
          new CopyObjectRequest(
            readme.s3Bucket,
            readme.s3Key,
            container.s3Bucket,
            readmeKey
          )
        )
        .toEitherT[Future]
        .leftMap[CoreError](ThrowableError)

      // Get size of file for manifest
      readmeSize <- container.s3
        .getObjectMetadata(readme.s3Bucket, readme.s3Key)
        .map(_.getContentLength())
        .toEitherT[Future]
        .leftMap[CoreError](ThrowableError)

    } yield
      (
        readmeKey,
        FileManifest(
          README_FILENAME,
          README_FILENAME,
          readmeSize,
          FileType.Markdown
        )
      )
  }

  /**
    * Copy the dataset's Markdown changelog to the public assets bucket, and
    * the requester-pays publish bucket.
    */
  def copyChangelog(
    container: PublishContainer
  )(implicit
    executionContext: ExecutionContext,
    system: ActorSystem
  ): EitherT[Future, CoreError, (String, FileManifest)] = {

    val changelogKey = joinKeys(container.s3Key, CHANGELOG_FILENAME)

    for {
      changelog <- container.datasetAssetsManager
        .getChangelog(container.dataset)
        .flatMap(
          EitherT.fromOption[Future](
            _,
            PredicateError("Dataset does not have a chnagelog"): CoreError
          )
        )

      // Copy to public asset bucket
      _ <- container.s3
        .copyObject(
          new CopyObjectRequest(
            readme.s3Bucket,
            readme.s3Key,
            container.s3AssetBucket,
            joinKeys(container.s3AssetKeyPrefix, readmeKey)
          )
        )
        .toEitherT[Future]
        .leftMap[CoreError](ThrowableError)

      // Copy to published dataset bucket
      _ <- container.s3
        .copyObject(
          new CopyObjectRequest(
            readme.s3Bucket,
            readme.s3Key,
            container.s3Bucket,
            readmeKey
          )
        )
        .toEitherT[Future]
        .leftMap[CoreError](ThrowableError)

      // Get size of file for manifest
      readmeSize <- container.s3
        .getObjectMetadata(readme.s3Bucket, readme.s3Key)
        .map(_.getContentLength())
        .toEitherT[Future]
        .leftMap[CoreError](ThrowableError)

    } yield
      (
        readmeKey,
        FileManifest(
          README_FILENAME,
          README_FILENAME,
          readmeSize,
          FileType.Markdown
        )
      )
  }

  /**
    * Write published metadata JSON file.
    */
  def writeMetadata(
    container: PublishContainer,
    manifests: List[FileManifest]
  )(implicit
    executionContext: ExecutionContext,
    system: ActorSystem
  ): EitherT[Future, CoreError, Unit] = {

    // Self-describing metadata file to include in the file manifest.
    // This presents a chicken and egg problem since we need to know the
    // size of metadata.json for the manifest, but we need the size
    // to be encoded in the JSON file before we can compute the size.
    // Set size to 0 for now, and revise it later.
    val metadataManifest =
      FileManifest(METADATA_FILENAME, METADATA_FILENAME, 0, FileType.Json)

    val unsizedMetadata = DatasetMetadataV4_0(
      pennsieveDatasetId = container.publishedDatasetId,
      version = container.version,
      revision = None,
      name = container.dataset.name,
      description = container.dataset.description.getOrElse(""),
      creator = PublishedContributor(
        first_name = container.user.firstName,
        middle_initial = container.user.middleInitial,
        last_name = container.user.lastName,
        degree = container.user.degree,
        orcid = Some(container.userOrcid)
      ),
      contributors = container.contributors,
      sourceOrganization = container.organization.name,
      keywords = container.dataset.tags,
      datePublished = LocalDate.now(),
      license = container.dataset.license,
      `@id` = s"https://doi.org/${container.doi}",
      files = (metadataManifest :: manifests).sorted,
      collections = Some(container.collections),
      relatedPublications = Some(container.externalPublications)
    )

    // Compute the size of the metadata, including the number of characters
    // required to store the size itself.
    val metadataSize = sizeCountingOwnSize(
      dropNullPrinter(unsizedMetadata.asJson).getBytes("utf-8").length - 1
    )

    // Update the actual size of metadata.json
    val metadata = unsizedMetadata.copy(
      files = (metadataManifest.copy(size = metadataSize) :: manifests).sorted
    )

    writeJson(
      container,
      joinKeys(container.s3Key, METADATA_FILENAME),
      metadata.asJson
    )
  }

  /**
    * Calculate the total size of a file, knowing that the result will be
    * included *in* the file as a JSON field. `x` is the current size of the
    * file.
    */
  def sizeCountingOwnSize(x: Int): Int = {
    val guess = x + strlen(x)
    if (strlen(guess) > strlen(x))
      guess + 1
    else
      guess
  }
  private def strlen(x: Int): Int = x.toString.length

  /**
    * Compute the total storage size of the dataset by scanning the S3 objects
    * under the publish key.
    */
  def computeTotalSize(
    container: PublishContainer
  )(implicit
    executionContext: ExecutionContext,
    system: ActorSystem
  ): EitherT[Future, CoreError, Long] =
    S3.listBucket(container.s3Bucket, Some(container.s3Key))
      .map(_.size)
      .runWith(Sink.fold(0: Long)(_ + _))
      .toEitherT[CoreError](ThrowableError)

  /**
    * Metadata produced by the publish job that discover-service needs to know
    * about in order to display correct information.
    */
  case class TempPublishResults(
    readmeKey: String,
    bannerKey: String,
    totalSize: Long
  )

  object TempPublishResults {
    implicit val encoder: Encoder[TempPublishResults] =
      deriveEncoder[TempPublishResults]
    implicit val decoder: Decoder[TempPublishResults] =
      deriveDecoder[TempPublishResults]
  }
}
