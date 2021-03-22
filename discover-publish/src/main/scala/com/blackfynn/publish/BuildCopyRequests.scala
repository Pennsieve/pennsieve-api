package com.blackfynn.publish

import akka.NotUsed
import akka.stream.scaladsl.{ Flow, Source }

import cats.data._
import cats.implicits._
import com.blackfynn.core.utilities
import com.blackfynn.models.{ DatasetIgnoreFile, File, Package }
import com.blackfynn.publish.models.{ CopyAction, PackagePath }
import com.blackfynn.publish.utils.joinKeys
import com.blackfynn.models.Utilities._

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
            Source.fromFutureSource(
              filesSource(pkg, path, ignoreFiles.map(_.fileName))
            )
        }
    // Resolve the Future Flow to return a Flow with the PackagePath excluding ignored files
    Flow.lazyInitAsync(() => futureFlow).mapMaterializedValue(_ => NotUsed)
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
                .map(escapeName)
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
            joinKeys((fileDirectory +: parentPath :+ pkg.name).map(escapeName))

          val fileKey = joinKeys(packageKey, escapeName(file.fileName))

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
