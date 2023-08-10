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

import akka.NotUsed
import akka.stream.scaladsl.{ Flow, Source }
import com.pennsieve.core.utilities
import com.pennsieve.models.Utilities.{ cleanS3Key, getFullExtension }
import com.pennsieve.models.{ File, Package }
import com.pennsieve.publish.models.PackagePath
import com.pennsieve.publish.utils.joinKeys
import org.apache.commons.io.FilenameUtils

import scala.concurrent.{ ExecutionContext, Future }

case class PackageFile(
  `package`: Package,
  file: File,
  packageKey: String,
  fileKey: String
)

object BuildPackageFiles {
  def apply(
  )(implicit
    container: PublishContainer,
    ec: ExecutionContext
  ): Flow[PackagePath, PackageFile, NotUsed] = {
    val futureFlow: Future[Flow[PackagePath, PackageFile, NotUsed]] = for {
      ignoreFiles <- container.datasetManager
        .getIgnoreFiles(container.dataset)
        .value
        .flatMap(_.fold(Future.failed(_), Future.successful(_)))
    } yield
      Flow[PackagePath]
        .flatMapConcat {
          case (pkg, path) =>
            Source.futureSource(
              buildPackageFile(pkg, path, ignoreFiles.map(_.fileName))
            )
        }
    // Resolve the Future Flow to return a Flow with the PackagePath excluding ignored files
    Flow.lazyFutureFlow(() => futureFlow).mapMaterializedValue(_ => NotUsed)
  }

  def buildPackageFile(
    pkg: Package,
    parentPath: Seq[String],
    ignoreFiles: Seq[String]
  )(implicit
    container: PublishContainer,
    ec: ExecutionContext
  ): Future[Source[PackageFile, NotUsed]] = {
    container.fileManager
      .getSources(pkg, None, None, excludePending = true)
      .map(sourceFiles => {
        Source(
          generatePackageFile(pkg, parentPath, sourceFiles)
            .to(collection.immutable.Seq)
            .filterNot(
              packageFile =>
                ignoreFiles.contains(FilenameUtils.getName(packageFile.fileKey))
            )
        )
      })
      .value
      .flatMap {
        case Right(r) => {
          Future.successful(r)
        }
        case Left(error) => {
          Future.failed(error)
        }
      }
  }

  def generatePackageFile(
    pkg: Package,
    parentPath: Seq[String],
    files: Seq[File]
  )(implicit
    container: PublishContainer
  ): Seq[PackageFile] = {
    val fileDirectory = "files"
    if (files.length == 1)
      files
        .map(file => {
          val (_, extension) = utilities.splitFileName(file.fileName)

          val packageKey =
            joinKeys(
              (fileDirectory +: parentPath :+ s"${pkg.name}${if (getFullExtension(pkg.name) == None) {
                extension
              } else {
                ' '
              }}".trim)
                .map(cleanS3Key)
            )
          PackageFile(pkg, file, packageKey, packageKey)
        })
    else
      files
        .map(file => {
          val packageKey =
            joinKeys((fileDirectory +: parentPath :+ pkg.name).map(cleanS3Key))

          val fileKey = joinKeys(packageKey, cleanS3Key(file.fileName))

          PackageFile(pkg, file, packageKey, fileKey)
        })
  }
}
