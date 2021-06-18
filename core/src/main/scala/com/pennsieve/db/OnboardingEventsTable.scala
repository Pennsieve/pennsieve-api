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

import com.pennsieve.models.{ OnboardingEvent, OnboardingEventType }
import com.pennsieve.traits.PostgresProfile.api._

final class OnboardingEventsTable(tag: Tag)
    extends Table[OnboardingEvent](tag, Some("pennsieve"), "onboarding_events") {
  def userId = column[Int]("user_id")
  def event = column[OnboardingEventType]("event")
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def pk = primaryKey("onboarding_events_pk", (userId, event))
  def * = (userId, event, createdAt).mapTo[OnboardingEvent]
}

object OnboardingEventsMapper extends TableQuery(new OnboardingEventsTable(_)) {
  def getEvents(userId: Int): DBIO[Seq[OnboardingEventType]] =
    this.filter(_.userId === userId).map(_.event).result
}
