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
import akka.stream.alpakka.s3.S3Headers
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl.Sink
import cats.data._
import cats.implicits._
import com.amazonaws.services.s3.model.{
  CopyObjectRequest,
  ObjectMetadata,
  PutObjectRequest,
  PutObjectResult
}
import com.pennsieve.aws.s3.S3Trait
import com.pennsieve.core.utilities
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.domain.{
  CoreError,
  ExceptionError,
  PredicateError,
  ServiceError,
  ThrowableError
}
import com.pennsieve.managers.DatasetAssetsManager
import com.pennsieve.models._
import com.pennsieve.publish.models._
import com.pennsieve.publish.utils.joinKeys
import com.typesafe.scalalogging.StrictLogging
import io.circe._
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.parser._
import io.circe.syntax._
import org.apache.commons.io.IOUtils

import java.io.ByteArrayInputStream
import java.nio.charset.{ Charset, StandardCharsets }
import scala.concurrent.{ ExecutionContext, Future }
import scala.io.Source

object PublishingWorkflows {
  val Unknown: Long = -1
  val Version4: Long = 4
  val Version5: Long = 5
}
object Publish extends StrictLogging {

  val PUBLISH_ASSETS_FILENAME: String = "publish.json"

  val GRAPH_ASSETS_FILENAME: String = "graph.json"

  val OUTPUT_FILENAME: String = "outputs.json"

  val README_FILENAME: String = "readme.md"

  val CHANGELOG_FILENAME: String = "changelog.md"

  val BANNER_FILENAME: String = "banner.jpg"

  val METADATA_FILENAME: String = "manifest.json"

  private def assetKey(
    containerConfig: PublishContainerConfig,
    assetFilename: String
  ): String =
    containerConfig.workflowId match {
      case PublishingWorkflows.Version5 =>
        joinKeys(
          Seq(containerConfig.publishedDatasetId.toString, assetFilename)
        )
      case _ =>
        joinKeys(
          Seq(
            containerConfig.publishedDatasetId.toString,
            containerConfig.version.toString,
            assetFilename
          )
        )
    }

  private def publishedAssetsKey(config: PublishContainerConfig): String =
    assetKey(config, PUBLISH_ASSETS_FILENAME)

  private def graphManifestKey(config: PublishContainerConfig): String =
    assetKey(config, GRAPH_ASSETS_FILENAME)

  private def outputKey(config: PublishContainerConfig): String =
    assetKey(config, OUTPUT_FILENAME)

  private def publishedMetadataKey(config: PublishContainerConfig): String =
    assetKey(config, METADATA_FILENAME)

  private def publicAssetKeyPrefix(container: PublishContainer): String =
    joinKeys(
      Seq(container.publishedDatasetId.toString, container.version.toString)
    )

  /**
    * These intermediate files are generated as part of publishing. After the `finalizeDataset` step runs, they should
    * be deleted.
    */
  def temporaryFiles: Seq[String] =
    Seq(PUBLISH_ASSETS_FILENAME, GRAPH_ASSETS_FILENAME)

  def publishAssets(
    container: PublishContainer
  )(implicit
    executionContext: ExecutionContext,
    system: ActorSystem
  ): EitherT[Future, CoreError, Unit] =
    container.workflowId match {
      case PublishingWorkflows.Version5 => publishAssets5x(container)
      case _ => publishAssets4x(container)
    }

  def publishAssets5x(
    container: PublishContainer
  )(implicit
    executionContext: ExecutionContext,
    system: ActorSystem
  ): EitherT[Future, CoreError, Unit] = {
    logger.info(s"Starting publishAssets5x()")
    for {
      metadata <- getDatatsetMetadata(container)

      // filter out where sourcePackageId.isEmpty because these are Files not attached to Packages
      // (i.e., readme.md, banner.jpg, changelog.md, the manifest.json, and Model export files)
      previousFiles = metadata.files.filterNot(_.sourcePackageId.isEmpty).map {
        previousFile =>
          logger.info(s"publishAssets5x() previousFile: ${previousFile}")
          previousFile
      }

      // get current set of Packages, and copy to Discover S3
      packagesResult <- PackagesExport
        .exportPackageSources5x(container, previousFiles)
        .toEitherT

      (externalIdToPackagePath, packageFileManifests) = packagesResult

      // for the banner, readme and changelog
      // the `key` is the location in the public assets bucket (for the front-end)
      // the `file-manifest` is the location in the publish bucket

      // copy the banner to Discover S3 bucket
      bannerResult <- copyBanner(container)
      (bannerKey, bannerFileManifest) = bannerResult

      // copy the README to Discover S3 bucket
      readmeResult <- copyReadme(container)
      (readmeKey, readmeFileManifest) = readmeResult

      // copy the ChangeLog to Discover S3 bucket
      changelogResult <- copyChangelog(container)
      (changelogKey, changelogFileManifest) = changelogResult

      assets = PublishAssetResult(
        externalIdToPackagePath = externalIdToPackagePath,
        packageManifests = packageFileManifests,
        bannerKey = bannerKey,
        bannerManifest = bannerFileManifest,
        readmeKey = readmeKey,
        readmeManifest = readmeFileManifest,
        changelogKey = changelogKey,
        changelogManifest = changelogFileManifest
      )

      // upload the asset list (all published files) to Discover S3
      _ <- uploadToS3(container, publishedAssetsKey(container), assets.asJson)

    } yield ()
  }

  /**
    * Publish the assets of a dataset to S3. This includes exporting the dataset:
    *
    * - package sources
    * - banner image
    * - README file
    * - Changelog file if applicable
    */
  def publishAssets4x(
    container: PublishContainer
  )(implicit
    executionContext: ExecutionContext,
    system: ActorSystem
  ): EitherT[Future, CoreError, Unit] = {
    logger.info(s"Starting publishAssets4x()")
    for {
      // get current set of Packages, and copy to Discover S3
      packagesResult <- PackagesExport
        .exportPackageSources(container)
        .toEitherT

      (externalIdToPackagePath, packageFileManifests) = packagesResult

      // copy the banner to Discover S3 bucket
      bannerResult <- copyBanner(container)
      (bannerKey, bannerFileManifest) = bannerResult

      // copy the README to Discover S3 bucket
      readmeResult <- copyReadme(container)
      (readmeKey, readmeFileManifest) = readmeResult

      // copy the ChangeLog to Discover S3 bucket
      changelogResult <- copyChangelog(container)
      (changelogKey, changelogFileManifest) = changelogResult

      assets = PublishAssetResult(
        externalIdToPackagePath = externalIdToPackagePath,
        packageManifests = packageFileManifests,
        bannerKey = bannerKey,
        bannerManifest = bannerFileManifest,
        readmeKey = readmeKey,
        readmeManifest = readmeFileManifest,
        changelogKey = changelogKey,
        changelogManifest = changelogFileManifest
      )

      // upload the asset list (all published files) to Discover S3
      _ <- uploadToS3(container, publishedAssetsKey(container), assets.asJson)

    } yield ()
  }

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
    container: PublishContainerConfig
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

      manifestVersion <- writeMetadata(
        container,
        assets.bannerManifest.manifest :: assets.readmeManifest.manifest :: assets.changelogManifest.manifest :: graph.manifests ++ assets.packageManifests
          .map(_.manifest)
      )

      totalSize <- computeTotalSize(container)

      _ = logger.info(s"Writing temporary file: $OUTPUT_FILENAME")

      _ <- uploadToS3(
        container,
        outputKey(container),
        TempPublishResults(
          readmeKey = assets.readmeKey,
          changelogKey = assets.changelogKey,
          bannerKey = assets.bannerKey,
          totalSize = totalSize,
          manifestVersion = manifestVersion
        ).asJson
      )

//      _ = logger.info(
//        s"Deleting temporary files: $PUBLISH_ASSETS_FILENAME, $GRAPH_ASSETS_FILENAME"
//      )
//
//      _ <- container.s3
//        .deleteObjectsByKeys(
//          container.s3Bucket,
//          Seq(publishedAssetsKey(container), graphManifestKey(container)),
//          isRequesterPays = true
//        )
//        .toEitherT[Future]
//        .leftMap[CoreError](t => ExceptionError(new Exception(t)))

    } yield ()

  /**
    * Perform a blocking, single-part download from S3.
    */
  private def downloadFromS3[T: Decoder](
    containerConfig: PublishContainerConfig,
    key: String
  )(implicit
    executionContext: ExecutionContext,
    system: ActorSystem
  ): EitherT[Future, CoreError, T] = {
    EitherT
      .fromEither[Future](
        containerConfig.s3
          .getObject(containerConfig.s3Bucket, key, true)
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
    containerConfig: PublishContainerConfig,
    key: String,
    payload: Json
  )(implicit
    executionContext: ExecutionContext,
    system: ActorSystem
  ): EitherT[Future, CoreError, PutObjectResult] = {

    val payloadBytes = dropNullPrinter(payload)
      .getBytes(StandardCharsets.UTF_8)
    val payloadInputStream: ByteArrayInputStream = new ByteArrayInputStream(
      payloadBytes
    )
    val metadata = new ObjectMetadata()
    metadata.setContentLength(payloadBytes.length)
    EitherT
      .fromEither[Future](
        containerConfig.s3
          .putObject(
            new PutObjectRequest(
              containerConfig.s3Bucket,
              key,
              payloadInputStream,
              metadata
            )
          )
          .leftMap(ThrowableError)
        //.map(_ => ())
      )
  }

  /**
    * downloads the dataset metadata (manifest.json) from S3
    */
  def getDatatsetMetadata(
    container: PublishContainerConfig
  )(implicit
    executionContext: ExecutionContext,
    system: ActorSystem
  ): EitherT[Future, CoreError, DatasetMetadata] =
    container.version match {
      case 1 => EitherT.fromEither(Either.right(DatasetMetadataEmpty()))
      case _ =>
        downloadFromS3[DatasetMetadata](
          container,
          publishedMetadataKey(container)
        )
    }

  /**
    * Instead of exporting optional fields with a `null` literal,
    * remove the field entirely.
    */
  def dropNullPrinter(json: Json): String =
    Printer.spaces2.copy(dropNullValues = true).print(json)

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

      // the public assets key always has dataset-id/dataset-version in path
      bannerPublicAssetsKey = joinKeys(
        publicAssetKeyPrefix(container),
        bannerName
      )

      // Copy to public asset bucket
      _ <- container.s3
        .copyObject(
          new CopyObjectRequest(
            banner.s3Bucket,
            banner.s3Key,
            container.s3AssetBucket,
            joinKeys(container.s3AssetKeyPrefix, bannerPublicAssetsKey)
          )
        )
        .toEitherT[Future]
        .leftMap[CoreError](ThrowableError)

      // Copy to published dataset bucket: uses container.s3Key as head of path (see above)
      copyResult <- container.s3
        .copyObject(
          new CopyObjectRequest(
            banner.s3Bucket,
            banner.s3Key,
            container.s3Bucket,
            bannerKey
          ).withRequesterPays(true)
        )
        .toEitherT[Future]
        .leftMap[CoreError](ThrowableError)

      s3VersionId = container.workflowId match {
        case PublishingWorkflows.Version5 => Some(copyResult.getVersionId())
        case _ => None
      }

      // Get size of file for manifest
      bannerSize <- container.s3
        .getObjectMetadata(banner.s3Bucket, banner.s3Key)
        .map(_.getContentLength())
        .toEitherT[Future]
        .leftMap[CoreError](ThrowableError)

    } yield
      (
        bannerPublicAssetsKey,
        FileManifest(
          bannerName,
          bannerName,
          bannerSize,
          FileExtensions.fileTypeMap(extension.toLowerCase),
          s3VersionId = s3VersionId
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

      // the public assets key always has dataset-id/dataset-version in path
      readmePublicAssetsKey = joinKeys(
        publicAssetKeyPrefix(container),
        README_FILENAME
      )

      // Copy to public asset bucket: always has dataset-id/dataset-version in path
      _ <- container.s3
        .copyObject(
          new CopyObjectRequest(
            readme.s3Bucket,
            readme.s3Key,
            container.s3AssetBucket,
            joinKeys(container.s3AssetKeyPrefix, readmePublicAssetsKey)
          )
        )
        .toEitherT[Future]
        .leftMap[CoreError](ThrowableError)

      // Copy to published dataset bucket
      copyResult <- container.s3
        .copyObject(
          new CopyObjectRequest(
            readme.s3Bucket,
            readme.s3Key,
            container.s3Bucket,
            readmeKey
          ).withRequesterPays(true)
        )
        .toEitherT[Future]
        .leftMap[CoreError](ThrowableError)

      s3VersionId = container.workflowId match {
        case PublishingWorkflows.Version5 => Some(copyResult.getVersionId())
        case _ => None
      }

      // Get size of file for manifest
      readmeSize <- container.s3
        .getObjectMetadata(readme.s3Bucket, readme.s3Key)
        .map(_.getContentLength())
        .toEitherT[Future]
        .leftMap[CoreError](ThrowableError)

    } yield
      (
        readmePublicAssetsKey,
        FileManifest(
          README_FILENAME,
          README_FILENAME,
          readmeSize,
          FileType.Markdown,
          s3VersionId = s3VersionId
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
        .getOrCreateChangelog(
          container.dataset,
          container.s3Bucket,
          DatasetAssetsManager.defaultChangelogFileName,
          asset =>
            container.datasetAssetClient.uploadAsset(
              asset,
              DatasetAssetsManager.defaultChangelogText
                .getBytes("utf-8")
                .length,
              Some("text/plain"),
              IOUtils.toInputStream(
                DatasetAssetsManager.defaultChangelogText,
                "utf-8"
              )
            )
        )

      // the public assets key always has dataset-id/dataset-version in path
      changelogPublicAssetsKey = joinKeys(
        publicAssetKeyPrefix(container),
        CHANGELOG_FILENAME
      )

      // Copy to public asset bucket: always has dataset-id/dataset-version in path
      _ <- container.s3
        .copyObject(
          new CopyObjectRequest(
            changelog.s3Bucket,
            changelog.s3Key,
            container.s3AssetBucket,
            joinKeys(container.s3AssetKeyPrefix, changelogPublicAssetsKey)
          )
        )
        .toEitherT[Future]
        .leftMap[CoreError](ThrowableError)

      // Copy to published dataset bucket
      copyResult <- container.s3
        .copyObject(
          new CopyObjectRequest(
            changelog.s3Bucket,
            changelog.s3Key,
            container.s3Bucket,
            changelogKey
          )
        )
        .toEitherT[Future]
        .leftMap[CoreError](ThrowableError)

      s3VersionId = container.workflowId match {
        case PublishingWorkflows.Version5 => Some(copyResult.getVersionId())
        case _ => None
      }

      // Get size of file for manifest
      changelogSize <- container.s3
        .getObjectMetadata(changelog.s3Bucket, changelog.s3Key)
        .map(_.getContentLength())
        .toEitherT[Future]
        .leftMap[CoreError](ThrowableError)

    } yield
      (
        changelogPublicAssetsKey,
        FileManifest(
          CHANGELOG_FILENAME,
          CHANGELOG_FILENAME,
          changelogSize,
          FileType.Markdown,
          s3VersionId = s3VersionId
        )
      )
  }

  /**
    * Write published metadata JSON file.
    */
  def writeMetadata(
    containerConfig: PublishContainerConfig,
    manifests: List[FileManifest]
  )(implicit
    executionContext: ExecutionContext,
    system: ActorSystem
  ): EitherT[Future, CoreError, Option[String]] = {

    // Self-describing metadata file to include in the file manifest.
    // This presents a chicken and egg problem since we need to know the
    // size of metadata.json for the manifest, but we need the size
    // to be encoded in the JSON file before we can compute the size.
    // Set size to 0 for now, and revise it later.
    val metadataManifest =
      FileManifest(METADATA_FILENAME, METADATA_FILENAME, 0, FileType.Json)

    val unsizedMetadata = DatasetMetadataV4_0(
      pennsieveDatasetId = containerConfig.publishedDatasetId,
      version = containerConfig.version,
      revision = None,
      name = containerConfig.dataset.name,
      description = containerConfig.dataset.description.getOrElse(""),
      creator = PublishedContributor(
        first_name = containerConfig.user.firstName,
        middle_initial = containerConfig.user.middleInitial,
        last_name = containerConfig.user.lastName,
        degree = containerConfig.user.degree,
        orcid = Some(containerConfig.userOrcid)
      ),
      contributors = containerConfig.contributors,
      sourceOrganization = containerConfig.organization.name,
      keywords = containerConfig.dataset.tags,
      datePublished = LocalDate.now(),
      license = containerConfig.dataset.license,
      `@id` = s"https://doi.org/${containerConfig.doi}",
      files = (metadataManifest :: manifests).sorted,
      collections = Some(containerConfig.collections),
      relatedPublications = Some(containerConfig.externalPublications)
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

    uploadToS3(
      containerConfig,
      joinKeys(containerConfig.s3Key, METADATA_FILENAME),
      metadata.asJson
    ).map { result =>
      containerConfig.workflowId match {
        case PublishingWorkflows.Version5 => Some(result.getVersionId())
        case _ => None
      }
    }
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
    containerConfig: PublishContainerConfig
  )(implicit
    executionContext: ExecutionContext,
    system: ActorSystem
  ): EitherT[Future, CoreError, Long] =
    S3.listBucket(
        containerConfig.s3Bucket,
        Some(containerConfig.s3Key),
        S3Headers()
          .withCustomHeaders(Map("x-amz-request-payer" -> "requester"))
      )
      .map(_.size)
      .runWith(Sink.fold(0: Long)(_ + _))
      .toEitherT[CoreError](ThrowableError)

  /**
    * Metadata produced by the publish job that discover-service needs to know
    * about in order to display correct information.
    */
  case class TempPublishResults(
    readmeKey: String,
    changelogKey: String,
    bannerKey: String,
    totalSize: Long,
    manifestVersion: Option[String] = None
  )

  object TempPublishResults {
    implicit val encoder: Encoder[TempPublishResults] =
      deriveEncoder[TempPublishResults]
    implicit val decoder: Decoder[TempPublishResults] =
      deriveDecoder[TempPublishResults]
  }
}
