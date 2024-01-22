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

import com.pennsieve.models.{ Dataset, DatasetRegistration, Organization }
import com.pennsieve.traits.PostgresProfile.api._
import slick.dbio.Effect

import java.time.ZonedDateTime
import scala.concurrent.ExecutionContext

final class DatasetRegistrationTable(schema: String, tag: Tag)
    extends Table[DatasetRegistration](
      tag,
      Some(schema),
      "dataset_registration"
    ) {

  def datasetId = column[Int]("dataset_id")
  def registry = column[String]("registry")
  def registryId = column[Option[String]]("registry_id")
  def category = column[Option[String]]("category")
  def value = column[String]("value")
  def url = column[Option[String]]("url")
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)

  def * =
    (
      datasetId,
      registry,
      registryId,
      category,
      value,
      url,
      createdAt,
      updatedAt
    ).mapTo[DatasetRegistration]
}

class DatasetRegistrationMapper(organization: Organization)
    extends TableQuery(new DatasetRegistrationTable(organization.schemaId, _)) {

  def get(
    dataset: Dataset,
    registry: String
  )(implicit
    ec: ExecutionContext
  ): DBIO[Option[DatasetRegistration]] =
    this
      .filter(_.datasetId === dataset.id)
      .filter(_.registry === registry)
      .result
      .headOption

  def getBy(
    dataset: Dataset,
    registry: String
  ): Query[DatasetRegistrationTable, DatasetRegistration, Seq] =
    this
      .filter(_.datasetId === dataset.id)
      .filter(_.registry === registry)

  def add(
    registration: DatasetRegistration
  )(implicit
    ec: ExecutionContext
  ): DBIOAction[
    DatasetRegistration,
    NoStream,
    Effect.Write with Effect.Transactional with Effect
  ] = (this returning this) += registration

  def update(
    registration: DatasetRegistration
  )(implicit
    ec: ExecutionContext
  ): DBIOAction[
    DatasetRegistration,
    NoStream,
    Effect.Read with Effect.Write with Effect.Transactional with Effect
  ] = {
    for {
      _ <- this
        .filter(_.datasetId === registration.datasetId)
        .filter(_.registry === registration.registry)
        .update(registration)

      updated <- this
        .filter(_.datasetId === registration.datasetId)
        .filter(_.registry === registration.registry)
        .result
        .head
    } yield updated
  }

  // TODO: implement delete()

}
