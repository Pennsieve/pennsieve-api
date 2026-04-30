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
