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

import com.pennsieve.models.{ DataCanvas, Organization, User }
import com.pennsieve.traits.PostgresProfile.api._
import slick.lifted.{ PrimaryKey, TableQuery, Tag }

import java.time.ZonedDateTime
import scala.concurrent.ExecutionContext

final class DataCanvasTable(schema: String, tag: Tag)
    extends Table[DataCanvas](tag, Some(schema), "datacanvases") {

  def id: Rep[Int] = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def name: Rep[String] = column[String]("name")
  def description: Rep[String] = column[String]("description")
  def nodeId: Rep[String] = column[String]("node_id")
  def permissionBit: Rep[Int] = column[Int]("permission_bit")
  def role: Rep[String] = column[String]("role")
  def statusId: Rep[Int] = column[Int]("status_id")
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)

  def * =
    (
      id,
      name,
      description,
      createdAt,
      updatedAt,
      nodeId,
      permissionBit,
      role,
      statusId
    ).mapTo[DataCanvas]
}

class DataCanvasMapper(val organization: Organization)
    extends TableQuery(new DataCanvasTable(organization.schemaId, _)) {

  def isLocked(
    dataCanvas: DataCanvas,
    user: User
  )(implicit
    executionContext: ExecutionContext
  ): DBIO[Boolean] =
    this
      .getById(dataCanvas.id)
      .map {
        case _ =>
          true
      }
      .take(1)
      .result
      .headOption
      .map(_.getOrElse(false))

  def getById(id: Int): Query[DataCanvasTable, DataCanvas, Seq] =
    this.filter(_.id === id)
}
