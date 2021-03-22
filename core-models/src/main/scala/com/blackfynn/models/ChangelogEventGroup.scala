package com.blackfynn.models

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
