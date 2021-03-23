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

import enumeratum._
import enumeratum.EnumEntry._

import java.time.{ LocalDate, ZonedDateTime }
import scala.collection.immutable

case class ChangelogEventGroup(
  datasetId: Int,
  userIds: List[Int],
  eventType: ChangelogEventName,
  totalCount: Long,
  timeBucket: LocalDate,
  period: EventGroupPeriod,
  timeRange: ChangelogEventGroup.TimeRange
)

object ChangelogEventGroup {
  case class TimeRange(start: ZonedDateTime, end: ZonedDateTime)
}

sealed trait EventGroupPeriod extends EnumEntry with UpperSnakecase

object EventGroupPeriod
    extends Enum[EventGroupPeriod]
    with CirceEnum[EventGroupPeriod] {

  val values: immutable.IndexedSeq[EventGroupPeriod] = findValues

  case object DAY extends EventGroupPeriod
  case object WEEK extends EventGroupPeriod
  case object MONTH extends EventGroupPeriod
}
