package com.blackfynn.db

import com.blackfynn.traits.PostgresProfile.api._
import com.blackfynn.models._
import java.time.ZonedDateTime

import scala.concurrent.ExecutionContext

final class DatasetStatusLogTable(schema: String, tag: Tag)
    extends Table[DatasetStatusLog](tag, Some(schema), "dataset_status_log") {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def datasetId = column[Int]("dataset_id")
  def statusId = column[Option[Int]]("status_id")
  def statusName = column[String]("status_name")
  def statusDisplayName = column[String]("status_display_name")
  def userId = column[Option[Int]]("user_id")
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)

  def pk = primaryKey("simple_pk", id)

  def * =
    (datasetId, statusId, statusName, statusDisplayName, userId, createdAt)
      .mapTo[DatasetStatusLog]
}

class DatasetStatusLogMapper(val organization: Organization)
    extends TableQuery(new DatasetStatusLogTable(organization.schemaId, _)) {

  val datasetStatusMapper = new DatasetStatusMapper(organization)

  def getStatusLogs(datasetId: Int): Query[
    (DatasetStatusLogTable, Rep[Option[UserTable]]),
    (DatasetStatusLog, Option[User]),
    Seq
  ] =
    this
      .joinLeft(UserMapper)
      .on(_.userId === _.id)
      .filter {
        case (datasetStatusLogTable, _) =>
          datasetStatusLogTable.datasetId === datasetId
      }
      .sortBy(_._1.createdAt.desc.nullsFirst)

}
