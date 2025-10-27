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

case class Record(
  sortKey: Long,
  id: UUID,
  datasetId: Int,
  modelId: UUID,
  modelVersion: Int,
  value: Json,
  valueEncrypted: Option[Array[Byte]],
  validFrom: ZonedDateTime,
  validTo: Option[ZonedDateTime],
  isCurrent: Boolean,
  provenanceId: UUID,
  createdAt: ZonedDateTime,
  keyHash: Option[String]
)

final class RecordsTable(schema: String, tag: Tag)
    extends Table[Record](tag, Some(schema), "records") {

  def sortKey = column[Long]("sort_key", O.PrimaryKey, O.AutoInc)
  def id = column[UUID]("id")
  def datasetId = column[Int]("dataset_id")
  def modelId = column[UUID]("model_id")
  def modelVersion = column[Int]("model_version")
  def value = column[Json]("value")
  def valueEncrypted = column[Option[Array[Byte]]]("value_encrypted")
  def validFrom = column[ZonedDateTime]("valid_from")
  def validTo = column[Option[ZonedDateTime]]("valid_to")
  def isCurrent = column[Boolean]("is_current")
  def provenanceId = column[UUID]("provenance_id")
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def keyHash = column[Option[String]]("key_hash")

  def * =
    (
      sortKey,
      id,
      datasetId,
      modelId,
      modelVersion,
      value,
      valueEncrypted,
      validFrom,
      validTo,
      isCurrent,
      provenanceId,
      createdAt,
      keyHash
    ).mapTo[Record]
}

class RecordsMapper(val organization: Organization)
    extends TableQuery(new RecordsTable(organization.schemaId, _)) {}
