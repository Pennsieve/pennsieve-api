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

import java.time.ZonedDateTime
import com.pennsieve.models._
import com.pennsieve.traits.PostgresProfile.api._
import scala.concurrent.ExecutionContext

class DataCanvasUserTable(schema: String, tag: Tag)
    extends Table[DataCanvasUser](tag, Some(schema), "datacanvas_user") {

  def datacanvasId: Rep[Int] = column[Int]("datacanvas_id")
  def userId: Rep[Int] = column[Int]("user_id")
  def permission: Rep[DBPermission] = column[DBPermission]("permission_bit")
  def role: Rep[Option[Role]] = column[Option[Role]]("role")
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)

  def pk = primaryKey("combined_pk", (datacanvasId, userId))

  def * =
    (datacanvasId, userId, permission, role, createdAt, updatedAt)
      .mapTo[DataCanvasUser]
}

class DataCanvasUserMapper(organization: Organization)
    extends TableQuery(new DataCanvasUserTable(organization.schemaId, _)) {

  def getByUserId(
    userId: Int
  ): Query[DataCanvasUserTable, DataCanvasUser, Seq] =
    this.filter(_.userId === userId)

  def getByCanvasId(
    datacanvasId: Int
  ): Query[DataCanvasUserTable, DataCanvasUser, Seq] =
    this.filter(_.datacanvasId === datacanvasId)

  def get(
    userId: Int,
    datacanvasId: Int
  ): Query[DataCanvasUserTable, DataCanvasUser, Seq] =
    this
      .filter(_.userId === userId)
      .filter(_.datacanvasId === datacanvasId)

  def getUsersFor(
    datacanvasId: Int
  ): Query[(DataCanvasUserTable, UserTable), (DataCanvasUser, User), Seq] =
    this
      .getByCanvasId(datacanvasId)
      .join(UserMapper)
      .on(_.userId === _.id)

  def maxRoles(
    userId: Int
  )(implicit
    ec: ExecutionContext
  ): DBIOAction[Map[Int, Option[Role]], NoStream, Effect.Read] =
    this
      .filter(_.userId === userId)
      .map { row =>
        row.datacanvasId -> row.role
      }
      .distinct
      .result
      .map { result =>
        result
          .groupBy(_._1)
          .map {
            case (resultDataCanvasId, group) =>
              resultDataCanvasId -> group.map(_._2).max
          }
      }
}
