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
  ExternalRepository,
  ExternalRepositoryStatus,
  ExternalRepositoryType,
  SynchrnonizationSettings
}
import com.pennsieve.traits.PostgresProfile.api._
import slick.dbio.Effect
import slick.lifted.MappedToBase.mappedToIsomorphism

import java.time.ZonedDateTime
import java.util.UUID

final class ExternalRepositoriesTable(tag: Tag)
    extends Table[ExternalRepository](
      tag,
      Some("pennsieve"),
      "external_repositories"
    ) {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def origin = column[String]("origin")
  def `type` = column[ExternalRepositoryType]("type")
  def url = column[String]("url")
  def organizationId = column[Int]("organization_id")
  def userId = column[Int]("user_id")
  def datasetId = column[Option[Int]]("dataset_id")
  def applicationId = column[Option[UUID]]("application_id")
  def status = column[ExternalRepositoryStatus]("status")
  def autoProcess = column[Boolean]("auto_process")
  def synchronize = column[Option[SynchrnonizationSettings]]("synchronize")
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)

  def * =
    (
      id,
      origin,
      `type`,
      url,
      organizationId,
      userId,
      datasetId,
      applicationId,
      status,
      autoProcess,
      synchronize,
      createdAt,
      updatedAt
    ).mapTo[ExternalRepository]
}

class ExternalRepositoryMapper
    extends TableQuery(new ExternalRepositoriesTable(_)) {
  def add(extRepo: ExternalRepository): DBIOAction[
    ExternalRepository,
    NoStream,
    Effect.Write with Effect.Transactional with Effect
  ] =
    (this returning this) += extRepo

  def getExternalRepository(
    organizationId: Int,
    datasetId: Int
  ): DBIO[Option[ExternalRepository]] =
    this
      .filter(_.organizationId === organizationId)
      .filter(_.datasetId === datasetId)
      .result
      .headOption
}
