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
import com.pennsieve.models.{
  Annotation,
  ModelProperty,
  Organization,
  PathElement
}
import java.time.ZonedDateTime

final class AnnotationsTable(schema: String, tag: Tag)
    extends Table[Annotation](tag, Some(schema), "annotations") {

  // set by the database
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)

  def layerId = column[Int]("layer_id")
  def creatorId = column[Int]("creator_id")
  def description = column[String]("description")
  def attributes = column[List[ModelProperty]]("attributes")
  def path = column[List[PathElement]]("path")

  def * =
    (
      creatorId,
      layerId,
      description,
      path,
      attributes,
      createdAt,
      updatedAt,
      id
    ).mapTo[Annotation]
}

class AnnotationsMapper(val organization: Organization)
    extends TableQuery(new AnnotationsTable(organization.schemaId, _))
