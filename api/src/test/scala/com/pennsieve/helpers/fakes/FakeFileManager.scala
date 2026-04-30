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

package com.pennsieve.helpers.fakes

import cats.data.EitherT
import com.pennsieve.db.FilesTable.{ OrderByColumn, OrderByDirection }
import com.pennsieve.domain.{ CoreError, NotFound, PredicateError }
import com.pennsieve.managers.{ FileManager, PackageManager }
import com.pennsieve.models.{
  File,
  FileChecksum,
  FileObjectType,
  FileObjectType => FileObjType,
  FileProcessingState,
  FileState,
  FileType,
  Organization,
  Package
}
import io.circe.Json

import scala.concurrent.{ ExecutionContext, Future }

/**
  * In-memory fake of `FileManager`. Reads `state.files`.
  */
class FakeFileManager(
  val packageManager: PackageManager,
  override val organization: Organization
) extends FileManager {

  private def state: InMemoryState =
    packageManager
      .asInstanceOf[FakePackageManager]
      .datasetManager
      .asInstanceOf[FakeDatasetManager]
      .state

  private def filesForPackage(packageId: Int): Iterable[File] =
    state.files.collect {
      case ((orgId, _), f)
          if orgId == organization.id && f.packageId == packageId =>
        f
    }

  override def create(
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
    provenanceId: Option[java.util.UUID] = None,
    publishedS3VersionId: Option[String] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, File] = {
    val isValidProcessingState = (objectType, processingState) match {
      case (FileObjectType.Source, FileProcessingState.Unprocessed) => true
      case (FileObjectType.Source, FileProcessingState.Processed) => true
      case (FileObjectType.File, FileProcessingState.NotProcessable) => true
      case (FileObjectType.View, FileProcessingState.NotProcessable) => true
      case _ => false
    }
    if (!isValidProcessingState)
      EitherT.leftT[Future, File](
        PredicateError(
          s"Invalid file object type (${objectType.entryName}) and processing state (${processingState.entryName}) combination"
        ): CoreError
      )
    else {
      val id = state.newId()
      // Match Postgres-side timestamps without named zones — JSON encode
      // round-trip drops the zone, so equality checks would fail otherwise.
      val now = java.time.ZonedDateTime
        .now()
        .withZoneSameInstant(
          java.time.ZoneOffset.ofTotalSeconds(
            java.time.ZonedDateTime.now().getOffset.getTotalSeconds
          )
        )
      val file = File(
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
        provenanceId = provenanceId,
        publishedS3VersionId = publishedS3VersionId,
        createdAt = now,
        updatedAt = now,
        id = id
      )
      state.files.put((organization.id, id), file)
      EitherT.rightT[Future, CoreError](file)
    }
  }

  override def get(
    id: Int,
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, File] =
    state.files.get((organization.id, id)) match {
      case Some(f) if f.packageId == `package`.id => EitherT.rightT(f)
      case _ => EitherT.leftT[Future, File](NotFound(s"File ($id)"): CoreError)
    }

  override def getSources(
    `package`: Package,
    limit: Option[Int] = None,
    offset: Option[Int] = None,
    orderBy: Option[(OrderByColumn, OrderByDirection)] = None,
    excludePending: Boolean = false
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[File]] = {
    val all = filesForPackage(`package`.id)
      .filter(_.objectType == FileObjectType.Source)
      .filterNot(
        f =>
          excludePending && f.uploadedState
            .contains(com.pennsieve.models.FileState.PENDING)
      )
      .toSeq
    val sorted = orderBy match {
      case Some((OrderByColumn.Name, dir)) =>
        val asc = all.sortBy(_.name)
        if (dir == OrderByDirection.Desc) asc.reverse else asc
      case Some((OrderByColumn.Size, dir)) =>
        val asc = all.sortBy(_.size)
        if (dir == OrderByDirection.Desc) asc.reverse else asc
      case Some((OrderByColumn.CreatedAt, dir)) =>
        val asc = all.sortBy(_.createdAt.toInstant)
        if (dir == OrderByDirection.Desc) asc.reverse else asc
      case _ => all.sortBy(_.id)
    }
    val withOffset = offset.fold(sorted)(o => sorted.drop(o))
    val withLimit = limit.fold(withOffset)(l => withOffset.take(l))
    EitherT.rightT(withLimit)
  }

  private def filesByType(
    `package`: Package,
    t: FileObjectType,
    limit: Option[Int],
    offset: Option[Int]
  ): Seq[File] = {
    val all = filesForPackage(`package`.id)
      .filter(_.objectType == t)
      .toSeq
      .sortBy(_.id)
    val withOffset = offset.fold(all)(o => all.drop(o))
    limit.fold(withOffset)(l => withOffset.take(l))
  }

  override def getViews(
    `package`: Package,
    limit: Option[Int],
    offset: Option[Int],
    matchExact: Boolean
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[File]] = {
    if (matchExact)
      EitherT.rightT(filesByType(`package`, FileObjectType.View, limit, offset))
    else {
      // Mirror real fall-through: Views, else Files, else Sources.
      val views = filesByType(`package`, FileObjectType.View, limit, offset)
      if (views.nonEmpty) EitherT.rightT(views)
      else {
        val files = filesByType(`package`, FileObjectType.File, limit, offset)
        if (files.nonEmpty) EitherT.rightT(files)
        else
          EitherT.rightT(
            filesByType(`package`, FileObjectType.Source, limit, offset)
          )
      }
    }
  }

  override def getFiles(
    `package`: Package,
    limit: Option[Int],
    offset: Option[Int],
    matchExact: Boolean
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[File]] = {
    if (matchExact)
      EitherT.rightT(filesByType(`package`, FileObjectType.File, limit, offset))
    else {
      val files = filesByType(`package`, FileObjectType.File, limit, offset)
      if (files.nonEmpty) EitherT.rightT(files)
      else
        EitherT.rightT(
          filesByType(`package`, FileObjectType.Source, limit, offset)
        )
    }
  }

  override def getByType(
    `package`: Package,
    objectTypes: Set[FileObjectType]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[File]] =
    EitherT.rightT(
      filesForPackage(`package`.id)
        .filter(f => objectTypes.contains(f.objectType))
        .toSeq
        .sortBy(_.id)
    )

  override def setPublished(
    `package`: Package,
    published: Boolean,
    s3Key: Option[String] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] = {
    val toUpdate = state.files.collect {
      case ((o, fid), f)
          if o == organization.id && f.packageId == `package`.id &&
            s3Key.forall(_ == f.s3Key) =>
        (fid, f)
    }
    toUpdate.foreach {
      case (fid, f) =>
        state.files.put((organization.id, fid), f.copy(published = published))
    }
    EitherT.rightT(toUpdate.size)
  }

  override def setFilePublished(
    file: File,
    s3Bucket: String,
    s3Key: String,
    s3VersionId: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] =
    if (s3VersionId.isEmpty)
      EitherT.leftT[Future, Int](
        PredicateError("Published S3 versionId cannot be empty string")
      )
    else
      state.files.get((organization.id, file.id)) match {
        case Some(f) if f.packageId == file.packageId =>
          state.files.put(
            (organization.id, file.id),
            f.copy(
              s3Bucket = s3Bucket,
              s3Key = s3Key,
              publishedS3VersionId = Some(s3VersionId),
              published = true
            )
          )
          EitherT.rightT(1)
        case _ =>
          EitherT.leftT(
            NotFound(s"File for package ${file.packageId} with id ${file.id}"): CoreError
          )
      }

  override def setFileUnpublished(
    file: File,
    s3Bucket: String,
    s3Key: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] =
    state.files.get((organization.id, file.id)) match {
      case Some(f) if f.packageId == file.packageId =>
        state.files.put(
          (organization.id, file.id),
          f.copy(
            s3Bucket = s3Bucket,
            s3Key = s3Key,
            publishedS3VersionId = None,
            published = false
          )
        )
        EitherT.rightT(1)
      case _ =>
        EitherT.leftT(
          NotFound(s"File for package ${file.packageId} with id ${file.id}"): CoreError
        )
    }

  override def renameFile(
    `file`: File,
    newName: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, File] = {
    val updated = `file`.copy(name = newName)
    state.files.put((organization.id, `file`.id), updated)
    EitherT.rightT(updated)
  }

  override def getTotalSourceCount(
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] =
    EitherT.rightT(
      filesForPackage(`package`.id)
        .count(_.objectType == FileObjectType.Source)
    )

  override def getSingleSourceMap(
    `packages`: Seq[Package],
    limit: Option[Int] = None,
    offset: Option[Int] = None,
    orderBy: Option[(OrderByColumn, OrderByDirection)] = None,
    excludePending: Boolean = false
  )(implicit
    ec: ExecutionContext
  ): Future[Map[Int, Option[File]]] =
    Future.successful(`packages`.map { p =>
      val sources = filesForPackage(p.id)
        .filter(_.objectType == FileObjType.Source)
        .filterNot(
          f =>
            excludePending && f.uploadedState
              .contains(com.pennsieve.models.FileState.PENDING)
        )
        .toSeq
      val single = if (sources.size == 1) sources.headOption else None
      p.id -> single
    }.toMap)
}
