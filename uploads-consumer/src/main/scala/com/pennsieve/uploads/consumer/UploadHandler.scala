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

package com.pennsieve.uploads.consumer

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.{ Authorization, OAuth2BearerToken }
import akka.stream.scaladsl.{ Sink, Source }
import cats.data._
import cats.implicits._
import com.amazonaws.services.s3.AmazonS3URI
import com.amazonaws.services.s3.model._
import com.amazonaws.util.IOUtils.drainInputStream
import com.pennsieve.auth.middleware.Jwt
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.core.utilities.JwtAuthenticator
import com.pennsieve.db.{
  DatasetTeamMapper,
  DatasetUserMapper,
  DatasetsMapper,
  FilesMapper,
  OrganizationsMapper,
  PackagesMapper,
  UserMapper
}
import com.pennsieve.jobscheduling.clients.generated.definitions.UploadResult
import com.pennsieve.jobscheduling.clients.generated.jobs.CompleteUploadResponse
import com.pennsieve.models.{
  FileObjectType,
  FileState,
  JobId,
  Manifest,
  Organization,
  PackageState,
  Upload
}
import com.pennsieve.service.utilities.{ ContextLogger, LogContext }
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.uploads.consumer.antivirus.{
  AlreadyMoved,
  Clean,
  Infected,
  Locked,
  ScanResult,
  SizeLimitExceededException
}
import org.apache.commons.io.FilenameUtils

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NonFatal

/**
  * Core upload consumer logic. Virus scans each file in a package and moves
  * them from the uploads bucket to the storage bucket.
  *
  * The consumer acquires a transactional advisory lock on the package before
  * processing each file. This prevents other consumer instances from
  * concurrently copying the same file.  If the package is locked when the
  * consumer arrives, the consumer shortcircuits processing and returns the
  * message to the SQS queue assuming that the consumer currently processing the
  * package will complete the upload.

       +-----+              +-----------+                +-----------+    +-------+
       | SQS |              | Consumer1 |                | Consumer2 |    | ClamD |
       +-----+              +-----------+                +-----------+    +-------+
--------\ |                       |                            |              |
| Start |-|                       |                            |              |
|-------| |                       |                            |              |
          |                       |                            |              |
          | Consume package       |                            |              |
          |---------------------->|                            |              |
          |                       | ---------------------\     |              |
          |                       |-| Package NOT LOCKED |     |              |
          |                       | |--------------------|     |              |
          |                       |                            |              |
          |                       | Scan file                  |              |
          |                       |------------------------------------------>|
          |                       |                            |              |
          | Consume package       |                            |              |
          |--------------------------------------------------->|              |
          |                       |                            | ---------\   |
          |                       |                            |-| LOCKED |   |
          |                       |                            | |--------|   |
          |                       |                            |              |
          |                Locked, increase visibility timeout |              |
          |<---------------------------------------------------|              |
          |                       |                            |              |
          |                       |                            |        Clean |
          |                       |<------------------------------------------|
          |                       |                            |              |
          |        Delete message |                            |              |
          |<----------------------|                            |              |
          |                       |                            |              |

  * (Image generated on https://textart.io/sequence, see bottom of file for UML source)
  **/
object UploadHandler {
  import Processor.tier

  // `job-manifests` prefix is used for the ETL S3 trigger in order to dispatch workflows
  def manifestKey(importId: JobId): String =
    s"job-manifests/$importId/manifest.json"

  private def cleanS3Key(key: String): String =
    key.replaceAll("[^a-zA-Z0-9./@-]", "_")

  private def createStorageS3URI(
    organization: Organization,
    uploadS3URI: AmazonS3URI
  )(implicit
    container: Container
  ): AmazonS3URI = {
    val bucket = organization.storageBucket.getOrElse(container.storageBucket)
    // Note: directory has trailing slash
    val directory = FilenameUtils.getPath(uploadS3URI.getKey)
    val parsedFileName = cleanS3Key(FilenameUtils.getName(uploadS3URI.getKey))
    new AmazonS3URI(s"s3://$bucket/$directory$parsedFileName")
  }

  private final val CopyObjectLimit: Long = 5L * 1024L * 1024L * 1024L // 5 GB
  private final val CopyChunkSize: Long = 1024L * 1024L * 1024L // 1 GB

  def handle(
    manifest: Manifest
  )(implicit
    container: Container,
    executionContext: ExecutionContext,
    system: ActorSystem,
    log: ContextLogger
  ): EitherT[Future, Throwable, ScanResult] = {
    implicit val context: UploadConsumerLogContext =
      UploadConsumerLogContext(manifest)

    log.tierContext.info(
      s"Got manifest for package ${manifest.content.packageId}"
    )

    val scanResultParallelism: Int =
      container.s3ClientConfiguration.getMaxConnections / container.parallelism

    getUploadPayload(manifest)
      .toEitherT[Future]
      .flatMap { uploadPayload =>
        {
          // Note: Use `map` and `sequence` instead of `traverse` here to process all
          // files so we delete all infected files from S3 instead of short-circuiting
          // after the first infected file.
          val scanResults: EitherT[Future, Throwable, List[ScanResult]] =
            Source(uploadPayload.files)
              .map(new AmazonS3URI(_))
              .foldAsync(List.empty[Either[Throwable, ScanResult]]) {
                // Exit early if, while processing *any* file in this upload, the
                // package is locked.  This means that another uploads-consumer is
                // currently processing these package. That consumer will finish
                // the upload.
                case (accum, uploadS3URI) if accum.collect {
                      case Right(Locked) => Locked
                    }.nonEmpty =>
                  log.tierContext.info(
                    s"Package was locked, fast-forward over $uploadS3URI "
                  )
                  Future.successful(Locked.asRight[Throwable] :: accum)

                // Otherwise, proceed as normal
                case (accum, uploadS3URI) =>
                  val query = withPackageLock(
                    organizationId = manifest.organizationId,
                    packageId = manifest.content.packageId
                  ) {
                    handleUploadedAsset(manifest, uploadS3URI, uploadPayload)
                  }

                  container.db
                    .run(query)
                    .map(Right(_))
                    .recover {
                      case NonFatal(t) => Left(t)
                    }
                    .map(r => (r :: accum))
              }
              .runWith(Sink.head)
              .map(_.sequence)
              .toEitherT(identity)

          val result: EitherT[Future, Throwable, ScanResult] =
            scanResults
              .flatMap { results: List[ScanResult] =>
                // If the package is locked, short circuit to put the message back
                // on the queue. Another process will finish the upload.
                if (results.exists(_ == Locked))
                  EitherT.rightT(Locked: ScanResult)

                // Assume that the upload is complete.
                // TODO: This may not be true - is there a way for
                // uploads-consumer to check?
                else if (results.forall(_ == AlreadyMoved)) {
                  log.tierContext.info(
                    s"All files already moved for ${manifest.importId}"
                  )
                  EitherT.rightT(Clean: ScanResult)
                }

                // If any asset was infected fail the process
                else if (results.exists(_ == Infected)) {
                  handleFailure(manifest)
                    .map[ScanResult](_ => Infected)
                    .toEitherT[Throwable](identity)

                  // Otherwise complete the upload successfully
                  // TODO: lock this call?
                } else {
                  completeUpload(manifest, isSuccess = true)
                    .map[ScanResult](_ => Clean)
                }
              }

          // If anything fails we should try to set the package state to UPLOAD_FAILED
          // TODO: should this be moved to TriggerUtilities.failManifest and run
          // only when the message reaches the DLQ?
          result.recoverWith {
            case exception =>
              log.tierContext
                .error(s"Exception while handling upload", exception)
              handleFailure(manifest)
                .toEitherT[Throwable](identity)
                .flatMap(_ => EitherT.leftT[Future, ScanResult](exception))
          }
        }
      }
  }

  /**
    * Update the S3 key of a source file
    */
  def updateFile(
    files: FilesMapper,
    packageId: Int,
    existingBucket: String,
    existingKey: String,
    newBucket: String,
    newKey: String,
    fileState: FileState
  )(implicit
    ec: ExecutionContext
  ): DBIO[Int] = {
    files
      .filter(_.packageId === packageId)
      .filter(_.s3bucket === existingBucket)
      .filter(_.s3key === existingKey)
      .filter(_.objectType === (FileObjectType.Source: FileObjectType))
      .map(c => (c.s3bucket, c.s3key, c.uploadedState))
      .update((newBucket, newKey, Some(fileState)))
  }

  /**
    * Delete a source file by its S3 key.
    */
  def deleteFileByKey(
    files: FilesMapper,
    packageId: Int,
    s3Bucket: String,
    s3Key: String
  )(implicit
    ec: ExecutionContext
  ): DBIO[Int] = {
    files
      .filter(_.packageId === packageId)
      .filter(_.s3bucket === s3Bucket)
      .filter(_.s3key === s3Key)
      .filter(_.objectType === (FileObjectType.Source: FileObjectType))
      .delete
  }

  /**
    * Mark a package as infected.
    */
  def markPackageAsInfected(
    packages: PackagesMapper,
    packageId: Int
  )(implicit
    ec: ExecutionContext
  ): DBIO[Int] = {
    packages
      .filter(_.id === packageId)
      .map(_.state)
      .update(PackageState.INFECTED)
  }

  /**
    * Get an exclusive lock on this package before running the given DBIO
    * action. No other `uploads-consumer` instances are allowed to operate on
    * this package until the transaction ends.
    *
    * If the package is already locked, return `ScanResult.Locked` and do not
    * run the action.
    */
  def withPackageLock(
    organizationId: Int,
    packageId: Int
  )(
    action: => DBIO[ScanResult]
  )(implicit
    executionContext: ExecutionContext,
    container: Container,
    context: LogContext,
    log: ContextLogger
  ): DBIO[ScanResult] = {
    val query = for {
      lockAcquired <- sql"SELECT pg_try_advisory_xact_lock($organizationId, $packageId)"
        .as[Boolean]
        .map(_.headOption.getOrElse(false))

      scanResult <- if (lockAcquired) {
        log.tierContext.info("Acquired package lock")
        action
      } else {
        log.tierContext.info("Could not lock package")
        DBIO.successful(Locked)
      }
    } yield scanResult

    // This *must* be transactional for the advisory lock to work
    query.transactionally
  }

  def handleUploadedAsset(
    manifest: Manifest,
    uploadS3URI: AmazonS3URI,
    uploadPayload: Upload
  )(implicit
    executionContext: ExecutionContext,
    container: Container,
    context: LogContext,
    log: ContextLogger
  ): DBIO[ScanResult] = {

    log.tierContext.info(s"Scanning $uploadS3URI ")

    val fileName = FilenameUtils.getName(uploadS3URI.getKey)

    for {
      organization <- OrganizationsMapper.getOrganization(
        manifest.organizationId
      )

      storageS3URI = createStorageS3URI(organization, uploadS3URI)

      alreadyMovedToStorageBucket <- DBIO.from {
        alreadyMovedToStorage(storageS3URI)
      }

      // TODO ensure that, if file has been moved but does not exist
      // in Postgres, it is added. Should we add a unique constraint
      // on s3_key?

      result <- alreadyMovedToStorageBucket match {
        case false =>
          scanAndMoveAsset(
            organization,
            uploadS3URI,
            storageS3URI,
            uploadPayload
          )
        case true =>
          log.tierContext
            .info(s"$uploadS3URI already moved to storage bucket")
          DBIO.successful[ScanResult](AlreadyMoved)
      }

    } yield (result)
  }

  def alreadyMovedToStorage(
    storageS3URI: AmazonS3URI
  )(implicit
    executionContext: ExecutionContext,
    container: Container
  ): Future[Boolean] =
    Future {
      container.s3
        .getObjectMetadata(storageS3URI)
        .isRight
    }

  /**
    * Streams an asset from the uploads bucket to the AV scanner.
    * If the scan finishes successfully, we move it to the storage bucket.
    * If the scan fails, we delete it from the uploads bucket.
    */
  def scanAndMoveAsset(
    organization: Organization,
    uploadS3URI: AmazonS3URI,
    storageS3URI: AmazonS3URI,
    uploadPayload: Upload
  )(implicit
    executionContext: ExecutionContext,
    container: Container,
    context: LogContext,
    log: ContextLogger
  ): DBIO[ScanResult] = {
    for {
      // download S3 asset
      asset <- DBIO.from {
        Future {
          container.s3.getObject(uploadS3URI)
        }.flatMap(_.fold(Future.failed(_), Future.successful(_)))
      }

      // scan S3 asset for viruses
      scanResult <- DBIO
        .from {
          Future {
            val stream = asset.getObjectContent
            try {
              container.clamd.scan(stream): ScanResult
            } catch {
              // If the size of the file exceeds the ClamD limit, consider it clean.
              // TODO: use a better virus scanner that can handle large files.
              case exception: SizeLimitExceededException =>
                drainInputStream(stream)
                log.tierContext.error(exception.getMessage)
                Clean
            }
          }
        }
        .cleanUp(
          // Close the stream
          r => {
            r match {
              case Some(e) =>
                log.tierContext
                  .error(s"Closing stream after error ${e.getMessage}")
              case None => log.tierContext.debug("Closing stream")
            }
            DBIO.from {
              Future {
                asset.close
              }
            }
          }
        )

      _ <- scanResult match {
        case Clean =>
          handleCleanAsset(organization, asset, storageS3URI, uploadPayload)
        case Infected =>
          handleInfectedAsset(organization, asset, uploadPayload)
        // This should never happen because ClamD only returns Clean and Infected
        case r => DBIO.failed(new Exception(s"Unknown scan result $r"))
      }

    } yield scanResult
  }

  /**
    * Moves an asset from the uploads bucket to the storage bucket and update
    * the file's source file representation to reference the new storage asset for the given package.
    */
  def handleCleanAsset(
    organization: Organization,
    asset: S3Object,
    storageS3URI: AmazonS3URI,
    uploadPayload: Upload
  )(implicit
    executionContext: ExecutionContext,
    container: Container,
    context: LogContext,
    log: ContextLogger
  ): DBIO[Unit] = {

    val files = new FilesMapper(organization)
    val datasets = new DatasetsMapper(organization)
    implicit val datasetUser = new DatasetUserMapper(organization)
    implicit val datasetTeam = new DatasetTeamMapper(organization)

    log.tierContext.info(
      s"Clean. Moving s3://${asset.getBucketName}/${asset.getKey} to $storageS3URI"
    )

    for {
      dataset <- datasets.getDataset(uploadPayload.datasetId)
      user <- UserMapper.getUser(uploadPayload.userId)

      _ <- DBIO.from {
        moveAsset(organization, asset, storageS3URI)
      }

      // If the dataset is currently publishing or under review, new files
      // should not be immediately visible to the publish job,
      // except if they were uploaded by a member of the publishing team.
      // We send the actual user who uploaded the file rather than the service User
      // to check that last part.
      locked <- datasets.isLocked(dataset, user)

      fileState = locked match {
        case true => FileState.PENDING
        case _ => FileState.UPLOADED
      }

      // Update the S3 key of the source file to reference the new location in the storage bucket:
      updateCount <- updateFile(
        files,
        uploadPayload.packageId,
        existingBucket = asset.getBucketName,
        existingKey = asset.getKey,
        newBucket = storageS3URI.getBucket,
        newKey = storageS3URI.getKey,
        fileState
      )

      _ = log.tierContext.info(
        s"Updated ${updateCount} packageId=${uploadPayload.packageId} key=${asset.getKey} -> ${storageS3URI.getKey}; fileState=${fileState}"
      )

    } yield ()
  }

  /*
   * Deletes the asset from the uploads bucket
   */
  def handleInfectedAsset(
    organization: Organization,
    asset: S3Object,
    uploadPayload: Upload
  )(implicit
    executionContext: ExecutionContext,
    container: Container,
    context: LogContext,
    log: ContextLogger
  ): DBIO[Unit] = {
    val uploadsKey = asset.getKey
    val packageId = uploadPayload.packageId

    log.tierContext.info(s"Infected. Deleting $uploadsKey")

    val packages = new PackagesMapper(organization)
    val files = new FilesMapper(organization)

    for {
      _ <- DBIO.from {
        Future {
          container.s3
            .deleteObject(asset)
        }
      }
      _ <- markPackageAsInfected(packages, packageId)
      _ <- deleteFileByKey(
        files,
        packageId,
        container.uploadsBucket,
        uploadsKey
      )

      // _ <- TODO send infected notification to NotificationService
    } yield ()
  }

  /**
    * Call the job scheduling service and complete the upload with success or failure
    * @param manifest
    * @param isSuccess
    * @param container
    * @param executionContext
    * @return
    */
  def completeUpload(
    manifest: Manifest,
    isSuccess: Boolean
  )(implicit
    container: Container,
    executionContext: ExecutionContext,
    context: LogContext,
    log: ContextLogger
  ): EitherT[Future, Throwable, Unit] = {

    log.tierContext.info(s"Completing upload with isSuccess=$isSuccess")

    val token = generateJwtToken(manifest)
    val headers = Authorization(OAuth2BearerToken(token.value))
    container.jobsClient
      .completeUpload(
        manifest.organizationId,
        manifest.importId.value.toString,
        UploadResult(isSuccess),
        List(headers)
      )
      .leftMap {
        case Right(response) =>
          new Exception(s"Job Scheduling Service Error: $response")
        case Left(exception) => exception
      }
      .flatMap {
        case CompleteUploadResponse.OK | CompleteUploadResponse.Accepted =>
          EitherT.rightT[Future, Throwable](())
        case error =>
          EitherT.leftT[Future, Unit](
            new Exception(s"Job Scheduling Service Error: $error")
          )
      }
  }

  def generateJwtToken(
    manifest: Manifest
  )(implicit
    container: Container
  ): Jwt.Token =
    JwtAuthenticator.generateServiceToken(1.minute, manifest.organizationId)(
      new Jwt.Config { def key = container.jwtKey }
    )

  /*
   * For new upload jobs: set package state to UPLOAD_FAILED and send a failure notification.
   * For append upload jobs: just send a failure notification.
   */
  def handleFailure(
    manifest: Manifest
  )(implicit
    container: Container,
    ec: ExecutionContext,
    context: LogContext,
    log: ContextLogger
  ): Future[Unit] =
    completeUpload(manifest, isSuccess = false).valueOrF(Future.failed)

  /*
   * Moves an S3 asset from the uploads bucket to the storage bucket
   *
   * Files in external buckets grant full control to the bucket owner.
   */
  def moveAsset(
    organization: Organization,
    asset: S3Object,
    storageS3URI: AmazonS3URI
  )(implicit
    executionContext: ExecutionContext,
    container: Container
  ): Future[Unit] = {

    val acl = organization.storageBucket.map(
      _ => CannedAccessControlList.BucketOwnerFullControl
    )

    Future {
      for {
        _ <- container.s3.multipartCopy(
          sourceBucket = asset.getBucketName,
          sourceKey = asset.getKey,
          destinationBucket = storageS3URI.getBucket,
          destinationKey = storageS3URI.getKey,
          acl
        )
        _ <- container.s3.deleteObject(asset)

      } yield ()
    }.flatMap(_.fold(Future.failed, Future.successful))
  }

  private def getUploadPayload(
    manifest: Manifest
  )(implicit
    container: Container
  ): Either[Throwable, Upload] =
    manifest.content match {
      case upload: Upload => upload.asRight
      case _ => Left(new Exception("invalid job-type sent to uploads-consumer"))
    }
}
/**
  Diagram generated on  https://textart.io/sequence

object SQS Consumer1 Consumer2 ClamD
note left of SQS: Start
 SQS->Consumer1: Consume package
note right of Consumer1: Package NOT LOCKED
Consumer1->ClamD: Scan file
SQS->Consumer2: Consume package
note right of Consumer2: LOCKED
Consumer2->SQS: Locked, increase visibility timeout
ClamD->Consumer1: Clean
Consumer1->SQS: Delete message

  */
