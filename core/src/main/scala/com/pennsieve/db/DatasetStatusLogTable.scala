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

import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.models._
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
