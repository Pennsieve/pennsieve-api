package com.blackfynn.db

import com.blackfynn.traits.PostgresProfile.api._
import com.blackfynn.models._
import java.time.ZonedDateTime

import scala.concurrent.ExecutionContext

import cats.Semigroup
import cats.implicits._
import java.util.UUID
import java.time.LocalDate

import com.blackfynn.domain.SqlError

final class DatasetPublicationStatusTable(schema: String, tag: Tag)
    extends Table[DatasetPublicationStatus](
      tag,
      Some(schema),
      "dataset_publication_log"
    ) {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def datasetId = column[Int]("dataset_id")
  def publicationStatus = column[PublicationStatus]("publication_status")
  def publicationType = column[PublicationType]("publication_type")
  def comments = column[Option[String]]("comments")
  def embargoReleaseDate = column[Option[LocalDate]]("embargo_release_date")
  def createdBy = column[Option[Int]]("created_by")
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)

  def pk = primaryKey("simple_pk", id)

  def * =
    (
      datasetId,
      publicationStatus,
      publicationType,
      createdBy,
      comments,
      embargoReleaseDate,
      createdAt,
      id
    ).mapTo[DatasetPublicationStatus]
}

class DatasetPublicationStatusMapper(organization: Organization)
    extends TableQuery(
      new DatasetPublicationStatusTable(organization.schemaId, _)
    ) {

  def get(
    id: Int
  ): Query[DatasetPublicationStatusTable, DatasetPublicationStatus, Seq] =
    this.filter(_.id === id)

  def getByDataset(
    datasetId: Int,
    sortAscending: Boolean = false
  ): Query[DatasetPublicationStatusTable, DatasetPublicationStatus, Seq] =
    if (sortAscending)
      this
        .filter(_.datasetId === datasetId)
        .sortBy(_.createdAt.asc)
    else
      this
        .filter(_.datasetId === datasetId)
        .sortBy(_.createdAt.desc)
}
