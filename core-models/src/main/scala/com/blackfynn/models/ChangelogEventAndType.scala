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

/**
  * Composite of a ChangelogEvent and a ChangelogEventType
  *
  * The name of the event type is required to deserialize the event detail to a
  * ChangelogEventDetail.
  */
case class ChangelogEventAndType(
  datasetId: Int,
  userId: Int,
  eventType: ChangelogEventName,
  detail: ChangelogEventDetail,
  createdAt: ZonedDateTime,
  id: Int
)

object ChangelogEventAndType {

  def from(
    t: (ChangelogEvent, ChangelogEventType)
  ): Either[io.circe.Error, ChangelogEventAndType] =
    ChangelogEventAndType.from(t._1, t._2)

  def from(
    event: ChangelogEvent,
    eventType: ChangelogEventType
  ): Either[io.circe.Error, ChangelogEventAndType] =
    event.detail
      .as(ChangelogEventDetail.decoder(eventType.name))
      .map(
        detail =>
          ChangelogEventAndType(
            datasetId = event.datasetId,
            userId = event.userId,
            eventType = eventType.name,
            detail = detail,
            createdAt = event.createdAt,
            id = event.id
          )
      )

}
