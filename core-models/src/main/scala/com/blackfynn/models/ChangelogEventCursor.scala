package com.blackfynn.models

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
