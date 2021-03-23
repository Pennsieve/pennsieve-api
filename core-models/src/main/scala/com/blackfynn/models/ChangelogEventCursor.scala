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
import cats.data._
import cats.implicits._
import io.circe._
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

/**
  * Cursor through individual events in a single event group.
  */
case class ChangelogEventCursor(
  eventType: ChangelogEventName,
  start: Option[ZonedDateTime],
  end: Option[(ZonedDateTime, Int)], // time + next event ID
  userId: Option[Int]
)

object ChangelogEventCursor extends Cursor[ChangelogEventCursor] {

  implicit val encoder: Encoder[ChangelogEventCursor] =
    deriveEncoder[ChangelogEventCursor]
  implicit val Decoder: Decoder[ChangelogEventCursor] =
    deriveDecoder[ChangelogEventCursor]

  def apply(
    eventGroup: ChangelogEventGroup,
    nextEvent: ChangelogEventAndType,
    userId: Option[Int]
  ): ChangelogEventCursor =
    ChangelogEventCursor(
      eventType = eventGroup.eventType,
      start = eventGroup.timeRange.start.some,
      end = Some((eventGroup.timeRange.end, nextEvent.id)),
      userId = userId
    )
}
