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
import io.circe.Json

import java.time.ZonedDateTime
import java.util.UUID

case class ModelVersion(
  modelId: UUID,
  version: Int,
  schema: Json,
  schemaHash: String,
  keyProperties: List[String],
  sensitiveProperties: List[String],
  createdAt: ZonedDateTime,
  modelTemplateId: Option[UUID],
  modelTemplateVersion: Option[Int]
)

final class ModelVersionsTable(schema: String, tag: Tag)
    extends Table[ModelVersion](tag, Some(schema), "model_versions") {

  def modelId = column[UUID]("model_id")
  def version = column[Int]("version")
  def schema = column[Json]("schema")
  def schemaHash = column[String]("schema_hash")
  def keyProperties = column[List[String]]("key_properties")
  def sensitiveProperties = column[List[String]]("sensitive_properties")
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def modelTemplateId = column[Option[UUID]]("model_template_id")
  def modelTemplateVersion = column[Option[Int]]("model_template_version")

  def pk = primaryKey("model_versions_pkey", (modelId, version))

  def * =
    (
      modelId,
      version,
      schema,
      schemaHash,
      keyProperties,
      sensitiveProperties,
      createdAt,
      modelTemplateId,
      modelTemplateVersion
    ).mapTo[ModelVersion]
}

class ModelVersionsMapper(val organization: Organization)
    extends TableQuery(new ModelVersionsTable(organization.schemaId, _)) {}
