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

abstract class AbstractDataCanvasTable[T](
  schema: String,
  tag: Tag,
  tableName: String
) extends Table[T](tag, Some(schema), tableName) {

  def id: Rep[Int] = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def name: Rep[String] = column[String]("name")
  def description: Rep[String] = column[String]("description")
  def nodeId: Rep[String] = column[String]("node_id")
  def permissionBit: Rep[Int] = column[Int]("permission_bit")
  def role: Rep[Option[String]] = column[Option[String]]("role")
  def statusId: Rep[Int] = column[Int]("status_id")
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)
  def isPublic: Rep[Boolean] = column[Boolean]("is_public")

  val dataCanvasesSelect =
    (
      id,
      name,
      description,
      createdAt,
      updatedAt,
      nodeId,
      permissionBit,
      role,
      statusId,
      isPublic
    )
}

final class DataCanvasTable(schema: String, tag: Tag)
    extends AbstractDataCanvasTable[DataCanvas](schema, tag, "datacanvases") {
  def * = dataCanvasesSelect.mapTo[DataCanvas]
}

final class AllDataCanvasesView(tag: Tag)
    extends AbstractDataCanvasTable[(Int, DataCanvas)](
      "pennsieve",
      tag,
      "all_datacanvases"
    ) {
  def organizationId: Rep[Int] = column[Int]("organization_id")

  def * =
    (organizationId, dataCanvasesSelect) <> ({
      case (organizationId, values) =>
        (organizationId, DataCanvas.tupled(values))
    }, { _: (Int, DataCanvas) =>
      None
    })
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

  def getAll(): Query[DataCanvasTable, DataCanvas, Seq] = this

  def getById(id: Int): Query[DataCanvasTable, DataCanvas, Seq] =
    this.filter(_.id === id)

  def nameExists(name: String): DBIO[Boolean] =
    this
      .filter(_.name === name)
      .exists
      .result
}

class AllDataCanvasesViewMapper extends TableQuery(new AllDataCanvasesView(_)) {
  def get(nodeId: String): Query[AllDataCanvasesView, (Int, DataCanvas), Seq] =
    this.filter(_.nodeId === nodeId)
}
