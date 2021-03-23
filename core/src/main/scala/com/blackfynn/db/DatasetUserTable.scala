package com.pennsieve.db

import java.time.ZonedDateTime

import com.pennsieve.models._
import com.pennsieve.traits.PostgresProfile.api._
import scala.concurrent.ExecutionContext

class DatasetUserTable(schema: String, tag: Tag)
    extends Table[DatasetUser](tag, Some(schema), "dataset_user") {

  def datasetId = column[Int]("dataset_id")
  def userId = column[Int]("user_id")

  def permission = column[DBPermission]("permission_bit")
  def role = column[Option[Role]]("role")

  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)

  def pk = primaryKey("combined_pk", (datasetId, userId))

  def * =
    (datasetId, userId, permission, role, createdAt, updatedAt)
      .mapTo[DatasetUser]
}

class DatasetUserMapper(organization: Organization)
    extends TableQuery(new DatasetUserTable(organization.schemaId, _)) {

  def joinUsers(
    datasetId: Int
  ): Query[(DatasetUserTable, UserTable), (DatasetUser, User), Seq] =
    this
      .join(UserMapper)
      .on(_.userId === _.id)
      .filter {
        case (datasetUserTable, _) =>
          datasetUserTable.datasetId === datasetId
      }

  // get all users except for the owner
  def collaborators(
    datasetId: Int
  ): Query[(DatasetUserTable, UserTable), (DatasetUser, User), Seq] =
    this.joinUsers(datasetId).filter {
      case (datasetUser, _) =>
        datasetUser.role =!= (Role.Owner: Role)
    }

  def maxRoles(
    userId: Int
  )(implicit
    ec: ExecutionContext
  ): DBIOAction[Map[Int, Option[Role]], NoStream, Effect.Read] =
    this
      .filter(_.userId === userId)
      .map { row =>
        row.datasetId -> row.role
      }
      .distinct
      .result
      .map { result =>
        result
          .groupBy(_._1)
          .map {
            case (resultDatasetId, group) =>
              resultDatasetId -> group.map(_._2).max
          }
      }
  def getBy(datasetId: Int): Query[DatasetUserTable, DatasetUser, Seq] =
    this.filter(_.datasetId === datasetId)

  def getBy(
    userId: Int,
    datasetId: Int
  ): Query[DatasetUserTable, DatasetUser, Seq] =
    this
      .filter(_.userId === userId)
      .filter(_.datasetId === datasetId)

  def getUsersBy(
    datasetId: Int
  ): Query[(DatasetUserTable, UserTable), (DatasetUser, User), Seq] =
    this
      .getBy(datasetId)
      .join(UserMapper)
      .on(_.userId === _.id)
}
