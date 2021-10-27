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

  /**
    * Returns the event type ids that match the event names in `targetEvents`.
    * [[String]]s in `targetEvents` that do not name existing event types are ignored.
    *
    * @param targetEvents the event type names to look up
    * @return the ids corresponding to the given names if any
    */
  def getTargetEventIds(targetEvents: List[String]): DBIO[Seq[Int]] = {
    this.filter(_.eventName inSetBind targetEvents).map(_.id).result
  }
}
