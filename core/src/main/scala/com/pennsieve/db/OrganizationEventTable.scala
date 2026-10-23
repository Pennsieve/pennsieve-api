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
import java.time.ZonedDateTime

import io.circe.Json
import io.circe.syntax._

import scala.concurrent.ExecutionContext

/**
  * See docs/changelog-events.md Part 2 for the full design (pennsieve-api#405).
  *
  * Mirrors `ChangelogEventTable`'s shape, but `organization_events` stores
  * `event_type` directly as an enum column rather than via a lookup-table FK
  * -- see that doc's "Schema" section for why (low volume, mirrors
  * `dataset_publication_log`'s existing direct-enum-column precedent).
  */
final class OrganizationEventTable(schema: String, tag: Tag)
    extends Table[OrganizationEvent](tag, Some(schema), "organization_events") {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def actorUserId = column[Option[Int]]("actor_user_id")
  def subjectUserId = column[Option[Int]]("subject_user_id")
  def eventType = column[OrganizationEventName]("event_type")
  def detail = column[Json]("detail")
  def createdAt = column[ZonedDateTime]("created_at")

  def * =
    (actorUserId, subjectUserId, eventType, detail, createdAt, id)
      .mapTo[OrganizationEvent]
}

class OrganizationEventMapper(val organization: Organization)
    extends TableQuery(new OrganizationEventTable(organization.schemaId, _)) {

  def logEvent(
    actorUserId: Option[Int],
    subjectUserId: Option[Int],
    detail: OrganizationEventDetail,
    timestamp: ZonedDateTime = ZonedDateTime.now()
  )(implicit
    ec: ExecutionContext
  ): DBIO[OrganizationEvent] =
    (this returning this) +=
      OrganizationEvent(
        actorUserId = actorUserId,
        subjectUserId = subjectUserId,
        eventType = detail.eventType,
        detail = detail.asJson,
        createdAt = timestamp
      )
}
