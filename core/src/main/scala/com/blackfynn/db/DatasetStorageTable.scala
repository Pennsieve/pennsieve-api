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

import com.pennsieve.models._
import com.pennsieve.traits.PostgresProfile.api._

import scala.concurrent.ExecutionContext

final class DatasetStorageTable(schema: String, tag: Tag)
    extends Table[DatasetStorage](tag, Some(schema), "dataset_storage") {

  def datasetId = column[Int]("dataset_id", O.PrimaryKey)
  def size = column[Option[Long]]("size")

  def * = (datasetId, size).mapTo[DatasetStorage]
}

class DatasetStorageMapper(val organization: Organization)
    extends TableQuery(new DatasetStorageTable(organization.schemaId, _)) {

  def incrementDataset(
    datasetId: Int,
    size: Long
  )(implicit
    ec: ExecutionContext
  ): DBIO[Int] =
    sql"""
       INSERT INTO "#${organization.schemaId}".dataset_storage
       AS dataset_storage (dataset_id, size)
       VALUES ($datasetId, $size)
       ON CONFLICT (dataset_id)
       DO UPDATE SET size = COALESCE(dataset_storage.size, 0) + EXCLUDED.size
       """
      .as[Int]
      .map(_.headOption.getOrElse(0))

  def setDataset(
    datasetId: Int,
    size: Long
  )(implicit
    ec: ExecutionContext
  ): DBIO[Int] =
    sql"""
       INSERT INTO "#${organization.schemaId}".dataset_storage (dataset_id, size)
       VALUES ($datasetId, $size)
       ON CONFLICT (dataset_id)
       DO UPDATE SET size = EXCLUDED.size
       """
      .as[Int]
      .map(_.headOption.getOrElse(0))
}
