package com.pennsieve.models

import java.time.ZonedDateTime
import cats.data._
import cats.implicits._
import io.circe._

case class ChangelogEvent(
  datasetId: Int,
  userId: Int,
  eventTypeId: Int,
  detail: Json,
  createdAt: ZonedDateTime = ZonedDateTime.now(),
  id: Int = 0
)
