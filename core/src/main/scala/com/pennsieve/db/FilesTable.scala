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

package com.pennsieve.db

import com.pennsieve.domain.{ NotFound, PredicateError }
import com.pennsieve.models.FileObjectType.{ Source, View, File => FileT }
import com.pennsieve.models.{
  File,
  FileChecksum,
  FileObjectType,
  FileProcessingState,
  FileState,
  FileType,
  Organization,
  Package
}
import com.pennsieve.traits.PostgresProfile.api._

import java.time.ZonedDateTime
import java.util.UUID
import com.pennsieve.db.FilesTable.{ OrderByColumn, OrderByDirection }
import enumeratum.{ Enum, EnumEntry }
import slick.ast.Ordering
import slick.dbio.Effect
import slick.lifted.ColumnOrdered
import slick.sql.SqlAction

import scala.collection.immutable
import scala.concurrent.ExecutionContext

object FilesTable {

  sealed trait OrderByColumn extends EnumEntry

  object OrderByColumn extends Enum[OrderByColumn] {

    val values: immutable.IndexedSeq[OrderByColumn] = findValues

    case object Name extends OrderByColumn
    case object Size extends OrderByColumn
    case object CreatedAt extends OrderByColumn
  }

  sealed trait OrderByDirection extends EnumEntry {

    def toSlick[T](column: Rep[T]) = OrderByDirection.toSlick(this, column)
  }

  object OrderByDirection extends Enum[OrderByDirection] {

    val values: immutable.IndexedSeq[OrderByDirection] = findValues

    case object Asc extends OrderByDirection
    case object Desc extends OrderByDirection

    def toSlick[T](direction: OrderByDirection, column: Rep[T]) =
      direction match {
        case Asc => ColumnOrdered(column, Ordering(Ordering.Asc))
        case Desc => ColumnOrdered(column, Ordering(Ordering.Desc))
      }
  }
}

abstract class AbstractFilesTable[T](
  schema: String,
  tag: Tag,
  tableName: String
) extends Table[T](tag, Some(schema), tableName) {

  // set by the database
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)

  def packageId = column[Int]("package_id")
  def name = column[String]("name")
  def fileType = column[FileType]("file_type")
  def s3bucket = column[String]("s3_bucket")
  def s3key = column[String]("s3_key")
  def objectType = column[FileObjectType]("object_type")
  def processingState = column[FileProcessingState]("processing_state")
  def size = column[Long]("size")
  def checksum = column[Option[FileChecksum]]("checksum")
  def uuid = column[UUID]("uuid")
  def uploadedState = column[Option[FileState]]("uploaded_state")

  val filesSelect = (
    packageId,
    name,
    fileType,
    s3bucket,
    s3key,
    objectType,
    processingState,
    size,
    checksum,
    createdAt,
    updatedAt,
    uuid,
    uploadedState,
    id
  )
}

class FilesTable(schema: String, tag: Tag)
    extends AbstractFilesTable[File](schema, tag, "files") {

  def * = filesSelect.mapTo[File]
}

class AllFilesView(tag: Tag)
    extends AbstractFilesTable[(Int, File)]("pennsieve", tag, "all_files") {

  def organizationId = column[Int]("organization_id")

  def * =
    (organizationId, filesSelect) <> ({
      case (organizationId, values) =>
        (organizationId, File.tupled(values))
    }, { _: (Int, File) =>
      None
    })
}

class FilesMapper(val organization: Organization)
    extends TableQuery(new FilesTable(organization.schemaId, _)) {

  def get(id: Int) = this.filter(_.id === id)

  def getByPackage(`package`: Package) =
    this.filter(_.packageId === `package`.id)

  def getByPackageId(packageId: Int) = this.filter(_.packageId === packageId)

  def insert(file: File) = (this returning this.map(_.id) += file)

  def matchIds(ids: Option[Set[Int]], t: Rep[Int]): Rep[Boolean] =
    ids match {
      case Some(ids) => t inSet ids
      case None => true
    }

  def matchObjectType(
    objectType: Option[FileObjectType],
    t: Rep[FileObjectType]
  ): Rep[Boolean] =
    objectType match {
      case Some(View) => t === (View: FileObjectType)
      case Some(FileT) => t === (FileT: FileObjectType)
      case Some(Source) => t === (Source: FileObjectType)
      case None => true
    }

  def getPackageFileType(
    `package`: Package
  ): SqlAction[Option[FileType], NoStream, Effect.Read] = {
    this
      .filter(_.packageId === `package`.id)
      .filter(_.objectType === (FileObjectType.Source: FileObjectType))
      .take(1)
      .map(_.fileType)
      .result
      .headOption
  }

  def assertNameIsUnique(
    name: String,
    packageId: Int
  )(implicit
    ec: ExecutionContext
  ): DBIO[Unit] =
    for {
      nameExistInPackage <- this
        .filter(_.packageId === packageId)
        .filter(_.name === `name`)
        .exists
        .result

      _ <- assert(!nameExistInPackage)(
        PredicateError("File name must be unique within Package")
      )

    } yield ()

  def getByS3bucketAndS3Key(
    s3bucket: String,
    s3key: String
  )(implicit
    executionContext: ExecutionContext
  ): DBIOAction[File, NoStream, Effect.Read] = {
    this
      .filter(_.s3bucket === s3bucket)
      .filter(_.s3key === s3key)
      .result
      .headOption
      .flatMap {
        case Some(file) => DBIO.successful(file)
        case None => DBIO.failed(NotFound(s"File s3://$s3bucket/$s3key"))
      }
  }

  def getFrom(
    `packages`: Seq[Package],
    objectType: Option[FileObjectType],
    limit: Option[Int],
    offset: Option[Int],
    orderBy: Option[(OrderByColumn, OrderByDirection)],
    excludePending: Boolean = false
  )(implicit
    ec: ExecutionContext
  ): Query[FilesTable, File, Seq] = {
    val packageIds = `packages`.map(_.id).toSet
    val query: Query[FilesTable, File, Seq] = this
      .filter(
        f =>
          (f.packageId inSet packageIds) && 
          this.matchObjectType(objectType, f.objectType)
      )
      .filterIf(excludePending)(
        _.uploadedState
          .map(_ === (FileState.UPLOADED: FileState))
          .getOrElse(true)
      )

    val queryWithOffset = offset.foldLeft(query) { (query, offset) =>
      query.drop(offset)
    }

    val queryWithLimitAndOffset = limit.foldLeft(queryWithOffset) {
      (query, limit) =>
        query.take(limit)
    }

    val queryWithOrderBy = orderBy.foldLeft(queryWithLimitAndOffset) {
      (query, orderBy) =>
        query.sortBy { table =>
          orderBy match {
            case (OrderByColumn.Name, direction) =>
              direction.toSlick(table.name)
            case (OrderByColumn.Size, direction) =>
              direction.toSlick(table.size)
            case (OrderByColumn.CreatedAt, direction) =>
              direction.toSlick(table.createdAt)
          }
        }
    }

    queryWithOrderBy
  }
}
