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

import com.pennsieve.domain.Error
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.models._

import java.time.ZonedDateTime
import scala.concurrent.{ ExecutionContext, Future }

final class DatasetIntegrationsTable(schema: String, tag: Tag)
    extends Table[DatasetIntegration](tag, Some(schema), "dataset_integrations") {

  // set by the database
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)

  def webhookId = column[Int]("webhook_id")

  def datasetId = column[Int]("dataset_id")

  def enabledBy = column[Int]("enabled_by")

  def enabledOn =
    column[ZonedDateTime]("enabled_on", O.AutoInc) // set by the database on insert

  def * =
    (webhookId, datasetId, enabledBy, enabledOn, id).mapTo[DatasetIntegration]
}

class DatasetIntegrationsMapper(val organization: Organization)
    extends TableQuery(new DatasetIntegrationsTable(organization.schemaId, _)) {

  def getByDatasetAndWebhookId(
    datasetId: Int,
    webhookId: Int
  ): Query[DatasetIntegrationsTable, DatasetIntegration, Seq] =
    this
      .filter(_.datasetId === datasetId)
      .filter(_.webhookId === webhookId)

  def getByDatasetId(
    datasetId: Int
  ): Query[DatasetIntegrationsTable, DatasetIntegration, Seq] =
    this
      .filter(_.datasetId === datasetId)

  def getOrCreate(
    webhookId: Int,
    datasetId: Int,
    user: User
  )(implicit
    ec: ExecutionContext
  ): DBIO[DatasetIntegration] = {
    val lookUp = this
      .filter(x => x.datasetId === datasetId && x.webhookId === webhookId)
      .result
    for {
      existing <- lookUp.headOption
      row = existing getOrElse DatasetIntegration(webhookId, datasetId, user.id)
      _ <- this.insertOrUpdate(row)
      populatedRow <- lookUp.head
    } yield populatedRow
  }

}
