package com.blackfynn.publish

import akka.Done
import akka.actor.ActorSystem
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.{ ActorAttributes, Materializer, Supervision }
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import com.typesafe.scalalogging.LazyLogging
import com.blackfynn.models.{ ExternalId, FileManifest, PublishedFile }
import com.blackfynn.publish.Publish.logger
import com.blackfynn.publish.models.{ PackageExternalIdMap, S3Action }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

object PackagesExport extends LazyLogging {

  val supervision: Supervision.Decider = {
    case e => {
      logger.error("Stream error", e)
      Supervision.Stop
    }
  }

  def exportPackageSources(
    container: PublishContainer,
    previousFiles: List[PublishedFile]
  )(implicit
    ec: ExecutionContext,
    mat: Materializer,
    system: ActorSystem
  ): Future[(PackageExternalIdMap, List[FileManifest])] = {
    implicit val publishContainer: PublishContainer = container

    val (manifestF, packageNodeIdF) = PackagesSource()
      .withAttributes(ActorAttributes.supervisionStrategy(supervision))
      .via(BuildS3ActionRequests(previousFiles))
      .via(CopyS3ObjectsFlow())
      .alsoToMat(buildFileManifest)(Keep.right)
      .toMat(buildPackageExternalIdMap)(Keep.both)
      .run()

    for {
      manifest <- manifestF
      nodeIdMap <- packageNodeIdF

      filesToDelete <- Future(previousFiles.filterNot { publishedFile =>
        //if the S3 key of an already published file is in the manifest, then it means that the file has either been
        // overwritten/version (CopyAction) or left untouched (NoOpAction). In both cases, we should not delete the file
        manifest
          .map(_.path)
          .map(path => container.s3Key + path)
          .contains(publishedFile.s3Key)
      })

      _ = filesToDelete.map(file => {
        deleteFile(file)
      })

    } yield (nodeIdMap, manifest)
  }

  def deleteFile(
    fileToDelete: PublishedFile
  )(implicit
    container: PublishContainer,
    ec: ExecutionContext,
    mat: Materializer,
    system: ActorSystem
  ): Future[Done] = {

    val futureResult = S3
      .deleteObject(
        bucket = container.s3Bucket,
        key = fileToDelete.s3Key,
        versionId = fileToDelete.s3VersionId
      )
      .runWith(Sink.head)

    futureResult.onComplete {
      case Success(_) => logger.info(s"Done deleting ${fileToDelete.s3Key}")
      case Failure(e) => logger.error(e.getMessage, e)
    }

    futureResult
  }

  def buildFileManifest(
    implicit
    container: PublishContainer
  ): Sink[S3Action, Future[List[FileManifest]]] =
    Sink.fold(Nil: List[FileManifest])((accum, action: S3Action) => {
      FileManifest(
        path = action.fileKey,
        size = action.file.size,
        fileType = action.file.fileType,
        sourcePackageId = Some(action.pkg.nodeId),
        sourceFileId = Some(action.file.uuid),
        versionId = action.s3version
      ) :: accum
    })

  /**
    * Map package ID to S3 path so that `model-publish` can rewrite node IDs as
    * package paths.
    *
    * This sink receives multiple CopyActions (one per source file) for each
    * package.  However, since all source files belonging to a package have the
    * same `packageKey` they are correctly de-duplicated.
    */
  def buildPackageExternalIdMap(
    implicit
    container: PublishContainer
  ): Sink[S3Action, Future[PackageExternalIdMap]] =
    Sink.fold(Map.empty: PackageExternalIdMap)((accum, action: S3Action) => {
      accum ++ Map(
        ExternalId.nodeId(action.pkg.nodeId) -> action.packageKey,
        ExternalId.intId(action.pkg.id) -> action.packageKey
      )
    })
}
