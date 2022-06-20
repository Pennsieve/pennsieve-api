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

import com.pennsieve.models.{ DataCanvasFolder, Organization, User }
import com.pennsieve.traits.PostgresProfile.api._
import slick.lifted.Tag

import java.time.ZonedDateTime
import scala.concurrent.ExecutionContext

final class DataCanvasFolderTable(schema: String, tag: Tag)
    extends Table[DataCanvasFolder](tag, Some(schema), "datacanvas_folder") {

  def id: Rep[Int] = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def parentId: Rep[Int] = column[Int]("praent_id")
  def dataCanvasId: Rep[Int] = column[Int]("datacanvas_id")
  def name: Rep[String] = column[String]("name")
  def nodeId: Rep[String] = column[String]("node_id")
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)

  def * =
    (id, parentId, dataCanvasId, name, nodeId, createdAt, updatedAt)
      .mapTo[DataCanvasFolder]
}

class DataCanvasFolderMapper(val organization: Organization)
    extends TableQuery(new DataCanvasFolderTable(organization.schemaId, _)) {

  def isLocked(
    folder: DataCanvasFolder,
    user: User
  )(implicit
    executionContext: ExecutionContext
  ): DBIO[Boolean] =
    this
      .get(folder.id)
      .map {
        case _ =>
          true
      }
      .take(1)
      .result
      .headOption
      .map(_.getOrElse(false))

  def get(id: Int): Query[DataCanvasFolderTable, DataCanvasFolder, Seq] =
    this.filter(_.id === id)
}
