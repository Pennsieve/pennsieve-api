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
import com.pennsieve.models.FileManifest
import com.pennsieve.publish.models.{
  CopyAction,
  DeleteAction,
  FileAction,
  KeepAction
}
import com.typesafe.scalalogging.LazyLogging
import scala.concurrent.ExecutionContext

object ComputeFileActions extends LazyLogging {
  def apply(
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
                // the KeepAction will preserve based on the previous manifest
                keepAction(
                  currentPathToPackageFile.get(currentPath).get,
                  previousManifest
                )
              case false =>
                // the current path is being published by a different package, the path will be overwritten
                // the CopyAction will preserve based on the current manifest and the previous S3 VersionId
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
