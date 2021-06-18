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

class DatasetCollectionTable(schema: String, tag: Tag)
    extends Table[DatasetCollection](tag, Some(schema), "dataset_collection") {

  def datasetId = column[Int]("dataset_id")
  def collectionId = column[Int]("collection_id")

  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)

  def pk = primaryKey("combined_pk", (datasetId, collectionId))

  def * =
    (datasetId, collectionId, createdAt, updatedAt)
      .mapTo[DatasetCollection]
}

class DatasetCollectionMapper(organization: Organization)
    extends TableQuery(new DatasetCollectionTable(organization.schemaId, _)) {

  implicit val collectionMapper: CollectionMapper = new CollectionMapper(
    organization
  )

  def getBy(
    dataset: Dataset,
    collectionId: Int
  ): Query[DatasetCollectionTable, DatasetCollection, Seq] =
    this
      .filter(_.collectionId === collectionId)
      .filter(_.datasetId === dataset.id)

  def getCollections(
    dataset: Dataset
  ): Query[CollectionTable, Collection, Seq] =
    this
      .filter(_.datasetId === dataset.id)
      .join(collectionMapper)
      .on(_.collectionId === _.id)
      .map {
        case (d, c) => c
      }
}
