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

import com.pennsieve.models.Organization
import com.pennsieve.traits.PostgresProfile.api._

import java.time.ZonedDateTime
import java.util.UUID

case class Model(
  id: UUID,
  datasetId: Int,
  name: String,
  displayName: String,
  description: String,
  createdAt: ZonedDateTime,
  updatedAt: ZonedDateTime
)

final class ModelsTable(schema: String, tag: Tag)
    extends Table[Model](tag, Some(schema), "models") {

  def id = column[UUID]("id", O.PrimaryKey)
  def datasetId = column[Int]("dataset_id")
  def name = column[String]("name")
  def displayName = column[String]("display_name")
  def description = column[String]("description")
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)

  def * =
    (id, datasetId, name, displayName, description, createdAt, updatedAt)
      .mapTo[Model]
}

class ModelsMapper(val organization: Organization)
    extends TableQuery(new ModelsTable(organization.schemaId, _)) {}
