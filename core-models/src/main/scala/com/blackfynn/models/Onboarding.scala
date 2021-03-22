// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.models

import java.time.ZonedDateTime

import enumeratum._

/**
  * An onboarding event is an event that is recorded as part of the user "onboarding" experience. It serves to
  * track a checklist of events the user has initiated, such as "first time log in", "first uploaded dataset", "first
  * file upload", etc. so as to get/compel them to become productive using the Pennsieve platform.
  *
  * By tracking these events, we effectively "check them off" the list of first-time actions which we initially
  * display to the user.
  *
  * BIG NOTE: These events are specific for onboarding and will be migrated to a more full-featured and robust
  * event tracking system if and when it is designed.
  */
sealed trait OnboardingEventType extends EnumEntry

object OnboardingEventType
    extends Enum[OnboardingEventType]
    with CirceEnum[OnboardingEventType] {
  val values = findValues

  case object FirstTimeSignOn extends OnboardingEventType
  case object LaunchCarousel extends OnboardingEventType
  case object CompletedCarousel extends OnboardingEventType
  case object CreatedDataset extends OnboardingEventType
  case object CreatedModel extends OnboardingEventType
  case object AddedFile extends OnboardingEventType
  case object CreatedRecord extends OnboardingEventType
  case object CreatedRelationshipType extends OnboardingEventType
}

final case class OnboardingEvent(
  userId: Int,
  event: OnboardingEventType,
  createdAt: ZonedDateTime = ZonedDateTime.now()
)
