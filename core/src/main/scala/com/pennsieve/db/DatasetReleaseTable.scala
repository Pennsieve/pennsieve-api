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
  Dataset,
  DatasetRelease,
  ModelProperty,
  Organization
}
import com.pennsieve.traits.PostgresProfile.api._
import slick.dbio.Effect

import scala.concurrent.ExecutionContext
import java.time.ZonedDateTime

final class DatasetReleaseTable(schema: String, tag: Tag)
    extends Table[DatasetRelease](tag, Some(schema), "dataset_release") {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def datasetId = column[Int]("dataset_id")
  def origin = column[String]("origin")
  def url = column[String]("url")
  def label = column[Option[String]]("label")
  def marker = column[Option[String]]("marker")
  def releaseDate = column[Option[ZonedDateTime]]("release_date")
  def properties = column[List[ModelProperty]]("properties")
  def tags = column[List[String]]("tags")
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)
  def releaseStatus = column[String]("release_status")
  def publishingStatus = column[String]("publishing_status")

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
      updatedAt,
      releaseStatus,
      publishingStatus
    ).mapTo[DatasetRelease]
}

class DatasetReleaseMapper(organization: Organization)
    extends TableQuery(new DatasetReleaseTable(organization.schemaId, _)) {
  def get(
    datasetId: Int
  )(implicit
    ec: ExecutionContext
  ): DBIO[Seq[DatasetRelease]] =
    this
      .filter(_.datasetId === datasetId)
      .result

  def getLatest(
    datasetId: Int
  )(implicit
    ec: ExecutionContext
  ): DBIO[Option[DatasetRelease]] =
    this
      .filter(_.datasetId === datasetId)
      .sortBy(_.createdAt.desc)
      .take(1)
      .result
      .headOption

  def add(release: DatasetRelease)(implicit ec: ExecutionContext): DBIOAction[
    DatasetRelease,
    NoStream,
    Effect.Write with Effect.Transactional with Effect
  ] =
    (this returning this) += release

  def update(
    release: DatasetRelease
  )(implicit
    ec: ExecutionContext
  ): DBIOAction[
    DatasetRelease,
    NoStream,
    Effect.Read with Effect.Write with Effect.Transactional with Effect
  ] =
    for {
      _ <- this
        .filter(_.datasetId === release.datasetId)
        .update(release)

      updated <- this
        .filter(_.datasetId === release.datasetId)
        .result
        .head
    } yield updated
}
