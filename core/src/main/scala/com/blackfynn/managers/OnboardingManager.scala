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

package com.pennsieve.managers

import cats.data.EitherT
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.db.OnboardingEventsMapper
import com.pennsieve.models.{ OnboardingEvent, OnboardingEventType }
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.db._
import com.pennsieve.domain.CoreError

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
