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

class DatasetContributorTable(schema: String, tag: Tag)
    extends Table[DatasetContributor](tag, Some(schema), "dataset_contributor") {

  def datasetId = column[Int]("dataset_id")
  def contributorId = column[Int]("contributor_id")
  def order = column[Int]("contributor_order")

  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)

  def pk = primaryKey("combined_pk", (datasetId, contributorId))

  def * =
    (datasetId, contributorId, order, createdAt, updatedAt)
      .mapTo[DatasetContributor]
}

class DatasetContributorMapper(organization: Organization)
    extends TableQuery(new DatasetContributorTable(organization.schemaId, _)) {

  implicit val contributorMapper: ContributorMapper = new ContributorMapper(
    organization
  )

  def getContributors(dataset: Dataset): Query[
    (ContributorTable, Rep[Option[UserTable]]),
    (Contributor, Option[User]),
    Seq
  ] =
    this
      .filter(_.datasetId === dataset.id)
      .join(contributorMapper)
      .on(_.contributorId === _.id)
      .joinLeft(UserMapper)
      .on(_._2.userId === _.id)
      .sortBy(_._1._1.order.asc.nullsFirst)
      .map {
        case ((_, c), u) => (c, u)
      }

  def getByDataset(
    dataset: Dataset
  ): Query[DatasetContributorTable, DatasetContributor, Seq] =
    this.filter(_.datasetId === dataset.id)

  def getBy(
    dataset: Dataset,
    contributorId: Int
  ): Query[DatasetContributorTable, DatasetContributor, Seq] =
    this
      .filter(_.contributorId === contributorId)
      .filter(_.datasetId === dataset.id)

  def updateContributorOrder(
    dataset: Dataset,
    contributor: DatasetContributor,
    newOrder: Int
  ): DBIO[Int] =
    this
      .filter(_.contributorId === contributor.contributorId)
      .filter(_.datasetId === dataset.id)
      .map(_.order)
      .update(newOrder)
}
