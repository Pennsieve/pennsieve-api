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

import com.pennsieve.domain.PredicateError
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.models._

import scala.concurrent.{ ExecutionContext, Future }

final class WebhookEventTypesTable(schema: String, tag: Tag)
    extends Table[WebhookEventType](tag, Some(schema), "webhook_event_types") {

  // set by the database
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)

  def eventName = column[String]("event_name")

  def * =
    (eventName, id).mapTo[WebhookEventType]
}

class WebhookEventTypesMapper(val organization: Organization)
    extends TableQuery(new WebhookEventTypesTable(organization.schemaId, _)) {

  def getNameById(id: Int): DBIO[Option[String]] =
    this.filter(_.id === id).map(_.eventName).result.headOption

  def getTargetEventTypes(
    targetEvents: List[String]
  ): DBIO[Seq[WebhookEventType]] = {
    this.filter(_.eventName inSetBind targetEvents).result
  }

  def getTargetEventIds(targetEvents: List[String]): DBIO[Seq[Int]] = {
    this.filter(_.eventName inSetBind targetEvents).map(_.id).result
  }

  def getTargetEvents(
    targetEvents: List[String]
  ): DBIO[Seq[WebhookEventType]] = {
    this.filter(_.eventName inSetBind targetEvents).result
  }

  def getTargetEventTypesQuery(
    targetEvents: List[String]
  ): Query[WebhookEventTypesTable, WebhookEventType, Seq] = {
    this.filter(_.eventName inSetBind targetEvents)
  }
}
