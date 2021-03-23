package com.pennsieve.db

import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.models._

import java.time.ZonedDateTime

import scala.concurrent.ExecutionContext

final class DatasetStatusTable(schema: String, tag: Tag)
    extends Table[DatasetStatus](tag, Some(schema), "dataset_status") {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def name = column[String]("name")
  def displayName = column[String]("display_name")
  def color = column[String]("color")
  def originalName = column[Option[DefaultDatasetStatus]]("original_name")
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)

  def * =
    (name, displayName, color, originalName, createdAt, updatedAt, id)
      .mapTo[DatasetStatus]
}

class DatasetStatusMapper(val organization: Organization)
    extends TableQuery(new DatasetStatusTable(organization.schemaId, _))
