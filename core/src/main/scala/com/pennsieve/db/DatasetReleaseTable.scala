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

import com.pennsieve.models.{
  DatasetReference,
  DatasetRelease,
  NameValueProperty,
  Organization,
  ReleaseOrigin
}
import com.pennsieve.traits.PostgresProfile.api._

import java.time.ZonedDateTime

class DatasetReleaseTable(schema: String, tag: Tag)
    extends Table[DatasetRelease](tag, Some(schema), "dataset_release") {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def datasetId = column[Int]("dataset_id")
  def origin = column[ReleaseOrigin]("origin")
  def url = column[String]("url")
  def label = column[String]("label")
  def marker = column[Option[String]]("marker")
  def releaseDate = column[Option[ZonedDateTime]]("release_date")
  def properties = column[Option[List[NameValueProperty]]]("properties")
  def tags = column[Option[List[String]]]("tags")
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)

  def * =
    (
      id,
      datasetId,
      origin,
      url,
      label,
      marker,
      releaseDate,
      properties,
      tags,
      createdAt,
      updatedAt
    ).mapTo[DatasetRelease]

}

class DatasetReleaseMapper(organization: Organization)
    extends TableQuery(new DatasetReleaseTable(organization.schemaId, _)) {
  def get(id: Int): Query[DatasetReleaseTable, DatasetRelease, Seq] =
    this.filter(_.id === id)

  def getForDataset(
    datasetId: Int
  ): Query[DatasetReleaseTable, DatasetRelease, Seq] =
    this.filter(_.datasetId === datasetId)

}
