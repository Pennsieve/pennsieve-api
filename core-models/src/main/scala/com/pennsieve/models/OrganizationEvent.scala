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

package com.pennsieve.models

import java.time.ZonedDateTime
import io.circe._

/**
  * See docs/changelog-events.md Part 2 for the full design (pennsieve-api#405).
  *
  * `actorUserId`/`subjectUserId` are both nullable -- see that doc's Schema
  * section for why: some events have no single human actor (workspace-wide
  * broadcasts), and not every event has a single subject (e.g. CREATE_TEAM).
  *
  * No `organizationId` field: this table lives in the per-organization
  * schema, so the organization is already identified by which schema the
  * row is in (mirrors `changelog_events`, which has no such column either).
  */
case class OrganizationEvent(
  actorUserId: Option[Int],
  subjectUserId: Option[Int],
  eventType: OrganizationEventName,
  detail: Json,
  createdAt: ZonedDateTime = ZonedDateTime.now(),
  id: Int = 0
)
