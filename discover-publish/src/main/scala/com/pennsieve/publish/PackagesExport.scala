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

import akka.actor.ActorSystem
import akka.stream.{ ActorAttributes, Supervision }
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import cats.data.EitherT
import cats.implicits._
import com.amazonaws.services.s3.model.{ ObjectMetadata, PutObjectRequest }
import com.pennsieve.domain.{ CoreError, ThrowableError }
import com.typesafe.scalalogging.LazyLogging
import com.pennsieve.models.{ ExternalId, FileManifest }
import com.pennsieve.publish.PackagesExport.supervision
import com.pennsieve.publish.Publish
import com.pennsieve.publish.Publish.dropNullPrinter
import com.pennsieve.publish.models.{
  CopyAction,
  DeleteAction,
  FileAction,
  FileActionItem,
  FileActionList,
  FileActionType,
  KeepAction,
  PackageExternalIdMap
}

import scala.collection.mutable.ListBuffer
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration._
import io.circe._
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.parser._
import io.circe.syntax._

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

object PackagesExport extends LazyLogging {

  val supervision: Supervision.Decider = {
    case e => {
      logger.error("Stream error", e)
      Supervision.Stop
    }
  }

  def exportPackageSources5x(
    container: PublishContainer,
    previousFileManifests: List[FileManifest]
  )(implicit
    ec: ExecutionContext,
    system: ActorSystem
  ): Future[(PackageExternalIdMap, List[FileManifest])] = {
    implicit val publishContainer: PublishContainer = container
    logger.info("exporting package sources (for 5x)")

    // get all Packages and transform into PackageFile and FileManifest
    val (currentPackageFileListF, currentFileManifestsF) = PackagesSource()
      .withAttributes(ActorAttributes.supervisionStrategy(supervision))
      .via(BuildPackageFiles())
      .alsoToMat(accumulatePackageFileList)(Keep.right)
      .toMat(transformPackageFileToManifest)(Keep.both)
      .run()

    for {
      // get current file manifests and package files
      currentFileManifests <- currentFileManifestsF
      currentPackageFileList <- currentPackageFileListF

      // compute File Actions (compare previous state with current)
      fileActions = computeFileActions(
        previousFileManifests,
        currentFileManifests,
        currentPackageFileList
      )

      _ = fileActions.foreach(
        fa => logger.info(s"exportPackageSources5x() fileAction: ${fa}")
      )

      // Write FileActionList to S3 (will be used by Cleanup Job on failure)
      fileActionListUpload = Storage.uploadToS3(
        container,
        Storage.fileActionsKey(container),
        FileActionList.from(fileActions).asJson
      )
      _ = Await.result(fileActionListUpload.value, 1.hour)

      // Perform File Actions on S3 (copy, delete, keep)
      (manifestF, nodeIdMapF) = Source(fileActions)
        .withAttributes(ActorAttributes.supervisionStrategy(supervision))
        .via(ExecuteS3ObjectActions())
        .alsoToMat(buildFileManifest)(Keep.right)
        .toMat(buildPackageExternalIdMap)(Keep.both)
        .run()

      // resolve manifest, nodeIdMap, and actionsList (delete, copy, keep)
      manifest <- manifestF
      nodeIdMap <- nodeIdMapF

    } yield (nodeIdMap, manifest)
  }

  def exportPackageSources(
    container: PublishContainer
  )(implicit
    ec: ExecutionContext,
    system: ActorSystem
  ): Future[(PackageExternalIdMap, List[FileManifest])] = {
    implicit val publishContainer: PublishContainer = container
    logger.info("exporting package sources (for legacy)")

    val (manifestF, packageNodeIdF) = PackagesSource()
      .withAttributes(ActorAttributes.supervisionStrategy(supervision))
      .via(BuildCopyRequests())
      .via(CopyS3ObjectsFlow())
      .alsoToMat(buildFileManifest)(Keep.right)
      .toMat(buildPackageExternalIdMap)(Keep.both)
      .run()

    for {
      manifest <- manifestF
      nodeIdMap <- packageNodeIdF
    } yield (nodeIdMap, manifest)
  }

  def buildFileManifest(
    implicit
    container: PublishContainer
  ): Sink[FileAction, Future[List[FileManifest]]] =
    Sink.fold(Nil: List[FileManifest])(
      (accum, action: FileAction) =>
        action match {
          case action: CopyAction =>
            FileManifest(
              name = action.file.name,
              path = action.fileKey,
              size = action.file.size,
              fileType = action.file.fileType,
              sourcePackageId = Some(action.pkg.nodeId),
              id = Some(action.file.uuid),
              s3VersionId = action.s3VersionId
            ) :: accum
          case action: KeepAction =>
            FileManifest(
              name = action.file.name,
              path = action.fileKey,
              size = action.file.size,
              fileType = action.file.fileType,
              sourcePackageId = Some(action.pkg.nodeId),
              id = Some(action.file.uuid),
              s3VersionId = action.s3VersionId
            ) :: accum
          case _ => accum // do nothing
        }
    )

  def accumulatePackageFileList(
    implicit
    container: PublishContainer
  ): Sink[PackageFile, Future[List[PackageFile]]] =
    Sink.fold(Nil: List[PackageFile])(
      (accum, item: PackageFile) => item :: accum
    )

  def transformPackageFileToManifest(
    implicit
    container: PublishContainer
  ): Sink[PackageFile, Future[List[FileManifest]]] =
    Sink.fold(Nil: List[FileManifest])(
      (accum, packageFile: PackageFile) =>
        FileManifest(
          name = packageFile.file.name,
          path = packageFile.fileKey,
          size = packageFile.file.size,
          fileType = packageFile.file.fileType,
          sourcePackageId = Some(packageFile.`package`.nodeId),
          id = Some(packageFile.file.uuid)
        ) :: accum
    )

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
  ): Sink[FileAction, Future[PackageExternalIdMap]] =
    Sink.fold(Map.empty: PackageExternalIdMap)(
      (accum, action: FileAction) =>
        action match {
          case action: CopyAction =>
            accum ++ Map(
              ExternalId.nodeId(action.pkg.nodeId) -> action.packageKey,
              ExternalId.intId(action.pkg.id) -> action.packageKey
            )
          case action: KeepAction =>
            accum ++ Map(
              ExternalId.nodeId(action.pkg.nodeId) -> action.packageKey,
              ExternalId.intId(action.pkg.id) -> action.packageKey
            )
          case _ => accum
        }
    )

  def buildFileActionList(
    implicit
    container: PublishContainer
  ): Sink[FileAction, Future[List[FileAction]]] =
    Sink.fold(Nil: List[FileAction])(
      (accum, action: FileAction) => action :: accum
    )

  def computeFileActions(
    previousFiles: List[FileManifest],
    currentFiles: List[FileManifest],
    currentPackageFileList: List[PackageFile]
  )(implicit
    ec: ExecutionContext,
    system: ActorSystem,
    container: PublishContainer
  ): Seq[FileAction] = {

    def samePackage(manifest1: FileManifest, manifest2: FileManifest): Boolean =
      manifest1.sourcePackageId.get.equals(manifest2.sourcePackageId.get)

    def copyAction(
      packageFile: PackageFile,
      s3VersionId: Option[String] = None
    ) =
      CopyAction(
        pkg = packageFile.`package`,
        file = packageFile.file,
        toBucket = container.s3Bucket,
        baseKey = container.s3Key,
        fileKey = packageFile.fileKey,
        packageKey = packageFile.packageKey,
        s3VersionId = s3VersionId
      )

    def keepAction(packageFile: PackageFile, manifest: FileManifest) =
      KeepAction(
        pkg = packageFile.`package`,
        file = packageFile.file,
        bucket = container.s3Bucket,
        baseKey = container.s3Key,
        fileKey = packageFile.fileKey,
        packageKey = packageFile.packageKey,
        s3VersionId = manifest.s3VersionId
      )

    val previousPathManifest =
      previousFiles.groupBy(_.path).map(f => f._1 -> f._2.head)
    val currentPathManifest =
      currentFiles.groupBy(_.path).map(f => f._1 -> f._2.head)
    val currentPathToPackageFile =
      currentPackageFileList.groupBy(_.fileKey).map(f => f._1 -> f._2.head)

    // find deleted paths/files (present in previous, but absent from current)
    val deleteActions: Iterable[FileAction] = previousPathManifest
      .filterNot(p => currentPathManifest.contains(p._1))
      .map {
        case (path, manifest) =>
          val action = DeleteAction(
            fromBucket = container.s3Bucket,
            baseKey = container.s3Key,
            fileKey = manifest.path,
            s3VersionId = manifest.s3VersionId
          )
          logger.info(s"computeFileActions() action: ${action}")
          action
      }

    val fileActions: Iterable[FileAction] = currentPathManifest.map {
      case (currentPath, currentManifest) =>
        logger.info(
          s"computeFileActions() currentPath: ${currentPath} currentManifest: ${currentManifest}"
        )
        val action = previousPathManifest.get(currentPath) match {
          case Some(previousManifest) =>
            // the current path was published in the previous version
            samePackage(currentManifest, previousManifest) match {
              case true =>
                // the current path is being published by the same package, it can be considered unchanged
                keepAction(
                  currentPathToPackageFile.get(currentPath).get,
                  currentManifest
                )
              case false =>
                // the current path is being published by a different package
                copyAction(
                  currentPathToPackageFile.get(currentPath).get,
                  previousManifest.s3VersionId
                )
            }
          case None =>
            // the current path was not published in the previous version
            // whether the Package Ids match is not relevant, both resolve to copying the current file
            copyAction(currentPathToPackageFile.get(currentPath).get)
        }
        logger.info(s"computeFileActions() action: ${action}")
        action
    }

    (deleteActions ++ fileActions).toList
  }

}
