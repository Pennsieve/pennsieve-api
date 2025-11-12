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

import cats.Semigroup
import cats.implicits._
import java.util.UUID
import java.time.LocalDate

import com.pennsieve.domain.SqlError

final class DatasetPublicationStatusTable(schema: String, tag: Tag)
    extends Table[DatasetPublicationStatus](
      tag,
      Some(schema),
      "dataset_publication_log"
    ) {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def datasetId = column[Int]("dataset_id")
  def publicationStatus = column[PublicationStatus]("publication_status")
  def publicationType = column[PublicationType]("publication_type")
  def comments = column[Option[String]]("comments")
  def embargoReleaseDate = column[Option[LocalDate]]("embargo_release_date")
  def createdBy = column[Option[Int]]("created_by")
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)

  def pk = primaryKey("simple_pk", id)

  def * =
    (
      datasetId,
      publicationStatus,
      publicationType,
      createdBy,
      comments,
      embargoReleaseDate,
      createdAt,
      id
    ).mapTo[DatasetPublicationStatus]
}

class DatasetPublicationStatusMapper(organization: Organization)
    extends TableQuery(
      new DatasetPublicationStatusTable(organization.schemaId, _)
    ) {

  def get(
    id: Int
  ): Query[DatasetPublicationStatusTable, DatasetPublicationStatus, Seq] =
    this.filter(_.id === id)

  def getByDataset(
    datasetId: Int,
    sortAscending: Boolean = false
  ): Query[DatasetPublicationStatusTable, DatasetPublicationStatus, Seq] =
    if (sortAscending)
      this
        .filter(_.datasetId === datasetId)
        .sortBy(_.createdAt.asc)
    else
      this
        .filter(_.datasetId === datasetId)
        .sortBy(_.createdAt.desc)

  /**
    * Returns a subquery that contains only the latest publication status
    * for each dataset (the one with the most recent created_at timestamp).
    * This can be used in joins to get the current publication status.
    */
  def latestByDataset
    : Query[DatasetPublicationStatusTable, DatasetPublicationStatus, Seq] = {
    // Get the max created_at for each dataset_id
    val latestCreatedAt = this
      .groupBy(_.datasetId)
      .map {
        case (datasetId, group) => (datasetId, group.map(_.createdAt).max)
      }

    // Join with the original table to get the full records
    this
      .join(latestCreatedAt)
      .on {
        case (status, (datasetId, maxCreatedAt)) =>
          status.datasetId === datasetId && status.createdAt === maxCreatedAt
      }
      .map(_._1)
  }
}
