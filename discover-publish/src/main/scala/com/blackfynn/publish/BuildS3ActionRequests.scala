package com.pennsieve.publish

import akka.NotUsed
import akka.stream.scaladsl.{ Flow, Source }
import cats.data._
import cats.implicits._
import com.pennsieve.core.utilities
import com.pennsieve.models.{ DatasetIgnoreFile, File, Package, PublishedFile }
import com.pennsieve.publish.models._
import com.pennsieve.publish.utils.joinKeys
import com.pennsieve.models.Utilities._
import com.pennsieve.publish.Publish.downloadFromS3

import scala.concurrent.{ ExecutionContext, Future }
import org.apache.commons.io.FilenameUtils

/**
  * Flow that takes a package and outputs the requests that needs to be
  * run against S3 in order to copy all the source files of the package.
  */
object BuildS3ActionRequests {

  def apply(
    previousFiles: List[PublishedFile]
  )(implicit
    container: PublishContainer,
    ec: ExecutionContext
  ): Flow[PackagePath, S3Action, NotUsed] = {
    val futureFlow: Future[Flow[PackagePath, S3Action, NotUsed]] = for {
      ignoreFiles <- container.datasetManager
        .getIgnoreFiles(container.dataset)
        .value
        .flatMap(_.fold(Future.failed(_), Future.successful(_)))

    } yield
      Flow[PackagePath]
        .flatMapConcat {
          case (pkg, path) =>
            Source.futureSource(
              filesSource(pkg, path, ignoreFiles.map(_.fileName), previousFiles)
            )
        }
    // Resolve the Future Flow to return a Flow with the PackagePath excluding ignored files
    Flow.lazyFutureFlow(() => futureFlow).mapMaterializedValue(_ => NotUsed)
  }

  def filesSource(
    pkg: Package,
    parentPath: Seq[String],
    ignoreFiles: Seq[String],
    previousFiles: List[PublishedFile]
  )(implicit
    container: PublishContainer,
    ec: ExecutionContext
  ): Future[Source[S3Action, NotUsed]] = {
    container.fileManager
      .getSources(pkg, None, None, excludePending = true)
      .map(sourceFiles => {
        Source(
          buildCopyAndNoOpActions(
            pkg,
            parentPath,
            sourceFiles,
            ignoreFiles,
            previousFiles
          ).to[collection.immutable.Seq]
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
  def buildCopyAndNoOpActions(
    pkg: Package,
    parentPath: Seq[String],
    files: Seq[File],
    ignoreFiles: Seq[String],
    previousFiles: List[PublishedFile]
  )(implicit
    container: PublishContainer
  ): Seq[S3Action] = {
    val fileDirectory = "files"
    val actions =
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
                  .map(escapeName)
              )

            val maybePreviousFile = previousFiles.find(previousFile => {
              previousFile.sourceFileId.getOrElse("") == file.uuid
            })

            if (maybePreviousFile
                .map(_.s3Key)
                .getOrElse("") contains packageKey) {
              new NoOpAction(
                pkg = pkg,
                file = file,
                baseKey = container.s3Key,
                fileKey = packageKey,
                packageKey = packageKey,
                s3version = maybePreviousFile.flatMap(_.s3VersionId)
              )
            } else {
              new CopyAction(
                pkg = pkg,
                file = file,
                toBucket = container.s3Bucket,
                baseKey = container.s3Key,
                fileKey = packageKey,
                packageKey = packageKey
              )
            }
          })
      else
        files
          .map(file => {
            val packageKey =
              joinKeys(
                (fileDirectory +: parentPath :+ pkg.name).map(escapeName)
              )

            val fileKey = joinKeys(packageKey, escapeName(file.fileName))

            val maybePreviousFile = previousFiles.find(previousFile => {
              previousFile.sourceFileId.getOrElse("") == file.uuid
            })

            if (maybePreviousFile.map(_.s3Key).getOrElse("") contains fileKey) {
              new NoOpAction(
                pkg = pkg,
                file = file,
                baseKey = container.s3Key,
                fileKey = fileKey,
                packageKey = packageKey,
                s3version = maybePreviousFile.flatMap(_.s3VersionId)
              )
            } else {
              new CopyAction(
                pkg = pkg,
                file = file,
                toBucket = container.s3Bucket,
                baseKey = container.s3Key,
                fileKey = fileKey,
                packageKey = packageKey
              )
            }
          })

    actions.filterNot { s3Action =>
      ignoreFiles.contains(FilenameUtils.getName(s3Action.fileKey))
    }
  }
}
