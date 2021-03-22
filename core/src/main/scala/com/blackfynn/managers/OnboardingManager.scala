// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.managers

import cats.data.EitherT
import com.blackfynn.core.utilities.FutureEitherHelpers.implicits._
import com.blackfynn.db.OnboardingEventsMapper
import com.blackfynn.models.{ OnboardingEvent, OnboardingEventType }
import com.blackfynn.traits.PostgresProfile.api._
import com.blackfynn.db._
import com.blackfynn.domain.CoreError

import scala.concurrent.{ ExecutionContext, Future }

class OnboardingManager(db: Database) {

  /**
    * Return a list of all onboarding events the given user has initiated.
    *
    * Note: All events are unique per user, meaning there will be 0 or 1 event type per user.
    *
    * @param userId
    * @param ec
    * @return
    */
  def getEvents(
    userId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[OnboardingEventType]] = {
    db.run(OnboardingEventsMapper.getEvents(userId)).toEitherT
  }

  /**
    * Associates a new onboarding event for the specified user.
    *
    * @param userId
    * @param event
    * @param ec
    * @return
    */
  def addEvent(
    userId: Int,
    event: OnboardingEventType
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] = {
    db.run(
        OnboardingEventsMapper.insertOrUpdate(OnboardingEvent(userId, event))
      )
      .toEitherT
  }
}
