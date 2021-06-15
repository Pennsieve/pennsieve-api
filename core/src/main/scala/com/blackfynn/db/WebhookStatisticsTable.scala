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

import scala.concurrent.{ ExecutionContext, Future }

final class WebhookStatisticsTable(schema: String, tag: Tag)
    extends Table[WebhookStatistic](tag, Some(schema), "webhook_statistics") {

  // set by the database
  def webhookId = column[Int]("webhook_id", O.PrimaryKey)
  def successes = column[Int]("successes", O.Default(0))
  def failures = column[Int]("failures", O.Default(0))
  def date =
    column[ZonedDateTime]("date", O.AutoInc) // set by the database on insert

  def * =
    (webhookId, successes, failures, date).mapTo[WebhookStatistic]
}

class WebhookStatisticsMapper(val organization: Organization)
    extends TableQuery(new WebhookStatisticsTable(organization.schemaId, _))
