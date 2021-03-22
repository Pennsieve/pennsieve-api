package com.blackfynn.models

import java.time.ZonedDateTime
import cats.data._
import cats.implicits._
import io.circe._
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import java.time.{ LocalDate, ZonedDateTime }

/**
  * Cursor through event groups in the timeline
  */
case class ChangelogEventGroupCursor(
  // rough grouping bucket of next event to return
  timeBucket: LocalDate,
  // timestamp of next event to return, breaks ties with timeBucket
  endTime: ZonedDateTime,
  // type of next event to return, breaks ties with endTime
  eventType: ChangelogEventName,
  // largest ID in Postgres at time of initial timeline query
  maxEventId: Int
)

object ChangelogEventGroupCursor extends Cursor[ChangelogEventGroupCursor] {
  implicit val encoder: Encoder[ChangelogEventGroupCursor] =
    deriveEncoder[ChangelogEventGroupCursor]
  implicit val Decoder: Decoder[ChangelogEventGroupCursor] =
    deriveDecoder[ChangelogEventGroupCursor]
}
