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

import cats.data._
import cats.implicits._
import com.pennsieve.core.utilities
import com.pennsieve.models.{ DatasetIgnoreFile, File, Package }
import com.pennsieve.publish.models.{ CopyAction, PackagePath }
import com.pennsieve.publish.utils.joinKeys
import com.pennsieve.models.Utilities._

import scala.concurrent.{ ExecutionContext, Future }

import org.apache.commons.io.FilenameUtils

/**
  * Flow that takes a package and outputs the requests that needs to be
  * run against S3 in order to copy all the source files of the package.
  */
object BuildCopyRequests {

  def apply(
  )(implicit
    container: PublishContainer,
    ec: ExecutionContext
  ): Flow[PackagePath, CopyAction, NotUsed] = {
    val futureFlow: Future[Flow[PackagePath, CopyAction, NotUsed]] = for {
      ignoreFiles <- container.datasetManager
        .getIgnoreFiles(container.dataset)
        .value
        .flatMap(_.fold(Future.failed(_), Future.successful(_)))
    } yield
      Flow[PackagePath]
        .flatMapConcat {
          case (pkg, path) =>
            Source.futureSource(
              filesSource(pkg, path, ignoreFiles.map(_.fileName))
            )
        }
    // Resolve the Future Flow to return a Flow with the PackagePath excluding ignored files
    Flow.lazyFutureFlow(() => futureFlow).mapMaterializedValue(_ => NotUsed)
  }

  def filesSource(
    pkg: Package,
    parentPath: Seq[String],
    ignoreFiles: Seq[String]
  )(implicit
    container: PublishContainer,
    ec: ExecutionContext
  ): Future[Source[CopyAction, NotUsed]] = {
    container.fileManager
      .getSources(pkg, None, None, excludePending = true)
      .map(sourceFiles => {
        Source(
          buildCopyActions(pkg, parentPath, sourceFiles)
            .to[collection.immutable.Seq]
            .filterNot(
              copyAction =>
                ignoreFiles.contains(FilenameUtils.getName(copyAction.fileKey))
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

  /**
    * TODO: Rename identical sources, deduplicate clashes between packages and
    * containing collections?
    *
    * Three cases:
    *
    * 1) The Package has a single source. The source should be renamed with the
    * filename (maintaining the extension, and placed directly in the parent
    * collection.
    *
    * 2) The package has multiple sources. The sources are placed in a new
    * directory which contains the name of the package. The sources contain
    * their original names.
    *
    * 3) The package has no sources (eg, a collection). Do nothing - the path of
    * the collection has already been added to the paths of its children.
    */
  def buildCopyActions(
    pkg: Package,
    parentPath: Seq[String],
    files: Seq[File]
  )(implicit
    container: PublishContainer
  ): Seq[CopyAction] = {
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

          new CopyAction(
            pkg,
            file,
            container.s3Bucket,
            container.s3Key,
            packageKey,
            packageKey
          )
        })
    else
      files
        .map(file => {
          val packageKey =
            joinKeys((fileDirectory +: parentPath :+ pkg.name).map(cleanS3Key))

          val fileKey = joinKeys(packageKey, cleanS3Key(file.fileName))

          new CopyAction(
            pkg,
            file,
            container.s3Bucket,
            container.s3Key,
            fileKey,
            packageKey
          )
        })

  }
}
