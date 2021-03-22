// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.db

import java.time.ZonedDateTime

import com.blackfynn.models.{ OnboardingEvent, OnboardingEventType }
import com.blackfynn.traits.PostgresProfile.api._

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
