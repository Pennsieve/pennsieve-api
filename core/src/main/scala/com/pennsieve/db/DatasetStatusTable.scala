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
