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

package com.pennsieve.managers

import cats.data.EitherT
import cats.implicits._
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.core.utilities.{ checkOrErrorT, FutureEitherHelpers }
import com.pennsieve.db.FilesTable.{ OrderByColumn, OrderByDirection }
import com.pennsieve.db.{ FilesMapper, FilesTable, PackagesMapper }
import com.pennsieve.domain.{ CoreError, NotFound, PredicateError }
import com.pennsieve.models.FileObjectType.{ Source, View, File => FileT }
import com.pennsieve.models.FileProcessingState.{ Processed, Unprocessed }
import com.pennsieve.models.Utilities.isNameValid
import com.pennsieve.models.{
  File,
  FileChecksum,
  FileObjectType,
  FileProcessingState,
  FileState,
  FileType,
  Organization,
  Package,
  User
}
import io.circe.Json
import java.util.UUID
import com.pennsieve.traits.PostgresProfile.api._

import scala.concurrent.{ ExecutionContext, Future }
import scala.collection.compat._

object FileManager {

  final case class UploadSourceFile(
    name: String,
    fileType: FileType,
    s3Key: String,
    size: Long,
    checksum: Option[FileChecksum] = None
  )

  // Maximum size for properties JSON field (1MB)
  val MAX_PROPERTIES_SIZE_BYTES: Int = 1024 * 1024
}

/** A file manager
  *
  *  @param packageManager A package manager.
  *  @param organization The organization that the file is associated with.
  */
class FileManager(packageManager: PackageManager, organization: Organization) {
  import FileManager._

  val packages: PackagesMapper = packageManager.packagesMapper
  val files: FilesMapper = new FilesMapper(organization)

  val db: Database = packageManager.db
  val actor: User = packageManager.actor

  /** Creates a new file with the given values.
    *
    *  @param name The name of the file.
    *  @param type The media type of the file.
    *  @param package The package the file is contained within.
    *  @param s3Bucket The AWS S3 bucket the file resides in.
    *  @param s3Key The key used to fetch the file from AWS S3.
    *  @param objectType The presentation type of the file (source, view, file).
    *  @param size The size of the file in bytes.
    *  @param fileChecksum The upload chunk size and the AWS S3 hash associated with the file.
    */
  def create(
    name: String,
    `type`: FileType,
    `package`: Package,
    s3Bucket: String,
    s3Key: String,
    objectType: FileObjectType,
    processingState: FileProcessingState,
    size: Long = 0,
    fileChecksum: Option[FileChecksum] = None,
    uploadedState: Option[FileState] = None,
    properties: Option[Json] = None,
    assetType: Option[String] = None,
    provenanceId: Option[UUID] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, File] = {
    // Precondition: the containing dataset is readable and the containing package is writable

    val isValidProcessingState: Boolean = (objectType, processingState) match {
      case (FileObjectType.Source, FileProcessingState.Unprocessed) => true
      case (FileObjectType.Source, FileProcessingState.Processed) => true
      case (FileObjectType.File, FileProcessingState.NotProcessable) => true
      case (FileObjectType.View, FileProcessingState.NotProcessable) => true
      case _ => false
    }

    // Validate properties JSON size
    val propertiesSize =
      properties.map(_.noSpaces.getBytes("UTF-8").length).getOrElse(0)

    val file =
      File(
        `package`.id,
        name,
        `type`,
        s3Bucket,
        s3Key,
        objectType,
        processingState,
        size,
        fileChecksum,
        uploadedState = uploadedState,
        properties = properties,
        assetType = assetType,
        provenanceId = provenanceId
      )

    if (!isNameValid(name)) {
      EitherT
        .leftT[Future, File](
          PredicateError(
            s"Invalid file name, please follow the naming conventions"
          )
        )
    } else if (propertiesSize > MAX_PROPERTIES_SIZE_BYTES) {
      EitherT
        .leftT[Future, File](
          PredicateError(
            s"Properties JSON exceeds maximum size of 1MB (actual: ${propertiesSize} bytes)"
          )
        )
    } else if (isValidProcessingState) {
      db.run(files.returning(files) += file).toEitherT
    } else {
      EitherT
        .leftT[Future, File](
          PredicateError(
            s"Invalid file object type (${objectType.entryName}) and processing state (${processingState.entryName}) combination"
          )
        )
    }

  }

  /**
    * Given a list of file names, generate a batch of unprocessed source files for each file provided.
    */
  def generateSourcesFiles(
    packageId: Int,
    s3Bucket: String,
    files: List[UploadSourceFile],
    initialFileState: FileState
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Option[Int]] = {
    val sourceFiles: Seq[File] = files.map { src: UploadSourceFile =>
      {
        File(
          packageId = packageId,
          name = src.name,
          fileType = src.fileType,
          s3Bucket = s3Bucket,
          s3Key = src.s3Key,
          objectType = FileObjectType.Source,
          processingState = FileProcessingState.Unprocessed,
          size = src.size,
          checksum = src.checksum,
          uploadedState = Some(initialFileState)
        )
      }
    }
    db.run(this.files ++= sourceFiles).toEitherT
  }

  /** Deletes files from a package.
    *
    *  @param package The containing package.
    *  @param onlyIds Only file identifiers matching those in the Set will be
    *  deleted.
    */
  def delete(
    `package`: Package,
    onlyIds: Option[Set[Int]] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] =
    db.run(
        files
          .filter(
            c => c.packageId === `package`.id && files.matchIds(onlyIds, c.id)
          )
          .delete
      )
      .toEitherT

  // `EitherT.orElse` is not sufficient as an empty list is a case of
  // success/Right, but we want to consider a case of failure in order to
  // trigger the next database fetch.
  private def orElseIfEmpty[T](
    action: EitherT[Future, CoreError, Seq[T]],
    next: EitherT[Future, CoreError, Seq[T]]
  )(implicit
    ec: ExecutionContext
  ) = {
    action.flatMap(
      values =>
        if (values.isEmpty) next else Future.successful(values).toEitherT
    )
  }

  private def _getFrom(
    `package`: Package,
    objectType: Option[FileObjectType],
    limit: Option[Int],
    offset: Option[Int],
    orderBy: Option[(OrderByColumn, OrderByDirection)],
    excludePending: Boolean = false
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[File]] =
    db.run(
        files
          .getFrom(
            `packages` = Seq(`package`),
            objectType = objectType,
            limit = limit,
            offset = offset,
            orderBy = orderBy,
            excludePending = excludePending
          )
          .result
      )
      .toEitherT

  // If `matchExact` is true, the typical fall-through behavior for matching
  // on `objectType` will be disabled.
  //
  // Note: this function does not perform any checks to see if the current
  // user can read from the package's dataset
  private def _getFromWithExact(
    `package`: Package,
    objectType: Option[FileObjectType],
    matchExact: Boolean,
    limit: Option[Int],
    offset: Option[Int],
    orderBy: Option[(OrderByColumn, OrderByDirection)] = None,
    excludePending: Boolean = false
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[File]] = {
    // Expectation: the containing package can be read from and was fetched securely beforehand
    if (matchExact) {
      return _getFrom(
        `package`,
        objectType,
        limit,
        offset,
        orderBy,
        excludePending = excludePending
      )
    }
    lazy val views = _getFrom(`package`, Some(View), limit, offset, orderBy)
    lazy val files = _getFrom(`package`, Some(FileT), limit, offset, orderBy)
    lazy val sources = _getFrom(`package`, Some(Source), limit, offset, orderBy)
    objectType match {
      case Some(View) =>
        orElseIfEmpty(views, orElseIfEmpty(files, sources))
      case Some(FileT) => orElseIfEmpty(files, sources)
      case Some(Source) => sources
      case _ => _getFrom(`package`, None, limit, offset, orderBy)
    }
  }

  def getTotalSourceCount(
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] =
    db.run(
        files
          .filter(
            f =>
              f.objectType === (FileObjectType.Source: FileObjectType) && f.packageId === `package`.id
          )
          .length
          .result
      )
      .toEitherT

  /** Fetches a file from within a package, given its identifier.
    *
    *  @param id The file identifier.
    *  @param package The containing package.
    */
  def get(
    id: Int,
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, File] =
    db.run(
        files
          .filter(f => f.id === id && f.packageId === `package`.id)
          .result
          .headOption
      )
      .whenNone[CoreError](NotFound(s"File ($id)"))

  /** Fetches all files from within a package for a given set of ObjectType.
    *
    *  @param package The containing package.
    *  @param objectTypes Only files with object types matching those in the
    *  given Set will be returned.
    */
  def getByType(
    `package`: Package,
    objectTypes: Set[FileObjectType]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[File]] = {
    db.run(
        files
          .filter(
            f => f.packageId === `package`.id && f.objectType.inSet(objectTypes)
          )
          .result
      )
      .toEitherT
  }

  /** Fetches all view in the given package.
    *
    *  @param package The containing package.
    *  @param matchExact If true, only entries which are actually marked as
    *  views will be returned. If omitted or false, files will be returned in
    *  lieu of a view, or failing that, sources.
    */
  def getViews(
    `package`: Package,
    limit: Option[Int] = None,
    offset: Option[Int] = None,
    matchExact: Boolean = false
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[File]] =
    _getFromWithExact(`package`, Some(View), matchExact, limit, offset)(ec)

  /** Fetches all files in the given package.
    *
    *  @param package The containing package.
    *  @param matchExact If true, only entries which are actually marked as
    *  files will be returned. If omitted or false, sources will be returned
    *  in lieu of a file if no files exist.
    */
  def getFiles(
    `package`: Package,
    limit: Option[Int] = None,
    offset: Option[Int] = None,
    matchExact: Boolean = false
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[File]] =
    _getFromWithExact(`package`, Some(FileT), matchExact, limit, offset)(ec)

  /** Fetches all sources in the given package.
    *
    *  @param package The containing package.
    */
  def getSources(
    `package`: Package,
    limit: Option[Int] = None,
    offset: Option[Int] = None,
    orderBy: Option[(OrderByColumn, OrderByDirection)] = None,
    excludePending: Boolean = false
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[File]] =
    _getFrom(
      `package`,
      Some(Source),
      limit,
      offset,
      orderBy,
      excludePending = excludePending
    )(ec)

  /**
    * If a package is backed by a single source file, return the file.
    *
    * @param `package`
    * @param limit
    * @param offset
    * @param orderBy
    * @param excludePending
    * @param ec
    * @return
    */
  def getSingleSource(
    `package`: Package,
    limit: Option[Int] = None,
    offset: Option[Int] = None,
    orderBy: Option[(OrderByColumn, OrderByDirection)] = None,
    excludePending: Boolean = false
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Option[File]] = {
    getSources(
      `package` = `package`,
      limit = limit,
      offset = offset,
      orderBy = orderBy,
      excludePending = excludePending
    ).map { files =>
      if (files.size == 1) {
        files.headOption
      } else {
        None
      }
    }
  }

  def getSingleSourceMap(
    `packages`: Seq[Package],
    limit: Option[Int] = None,
    offset: Option[Int] = None,
    orderBy: Option[(OrderByColumn, OrderByDirection)] = None,
    excludePending: Boolean = false
  )(implicit
    ec: ExecutionContext
  ): Future[Map[Int, Option[File]]] = {
    db.run(
        files
          .getFrom(
            `packages` = `packages`,
            objectType = Some(Source),
            limit = limit,
            offset = offset,
            orderBy = orderBy,
            excludePending = excludePending
          )
          .result
      )
      .map(
        _.groupBy(_.packageId).view
          .mapValues(files => {
            if (files.size == 1) {
              files.headOption
            } else {
              None
            }
          })
          .toMap
      ) // toMap for Scala 2.13
  }

  /** Fetches all unprocessed sources in the given package.
    *
    *  @param package The containing package.
    */
  def getUnprocessedSources(
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[File]] = {
    db.run(
        files
          .filter(_.packageId === `package`.id)
          .filter(_.objectType === (Source: FileObjectType))
          .filter(_.processingState === (Unprocessed: FileProcessingState))
          .result
      )
      .toEitherT
  }

  /** Sets all unprocessed sources in the given package to processed.
    *
    * @param sources
    * @param ec
    * @return
    */
  def setSourcesToProcessed(
    sources: List[File]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Boolean] = {
    for {
      _ <- checkOrErrorT(
        sources
          .forall(
            file =>
              file.objectType == Source && file.processingState == Unprocessed
          )
      )(PredicateError("can only set unprocessed Source files to processed"))
      result <- db
        .run(
          files
            .filter(_.id inSet sources.map(_.id))
            .map(_.processingState)
            .update((Processed: FileProcessingState))
        )
        .toEitherT
    } yield (result == sources.length)
  }

  /** Returns the file type of the FIRST source file in the given package.
    *
    *  @param package The containing package.
    */
  def getPackageFileType(
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Option[FileType]] =
    db.run(files.getPackageFileType(`package`)).toEitherT

  def renameFile(
    `file`: File,
    newName: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, File] = {
    val fileToUpdate = `file`.copy(name = newName)

    for {
      currentUpdatedAt <- db
        .run(for {
          _ <- files
            .assertNameIsUnique(fileToUpdate.name, fileToUpdate.packageId)
          _ <- files
            .get(fileToUpdate.id)
            .map(_.name)
            .update(newName)
          updatedAt <- files
            .get(fileToUpdate.id)
            .map(_.updatedAt)
            .result
            .headOption
        } yield updatedAt)
        .toEitherT

    } yield
      fileToUpdate.copy(
        updatedAt = currentUpdatedAt.getOrElse(fileToUpdate.updatedAt)
      )
  }

}
