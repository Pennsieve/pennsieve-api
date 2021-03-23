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

import java.sql.Timestamp
import java.time.format.DateTimeFormatter
import java.time.{
  Instant,
  LocalDateTime,
  OffsetDateTime,
  ZoneOffset,
  ZonedDateTime
}

import cats.implicits._

/**
  * Convert a value into a DateVersion
  *
  * @tparam D
  */
trait ToDateVersion[D] {
  def toDateVersion(dt: D): DateVersion
}

/**
  * Custom terms of service are versioned by a date string formatted as "yyyyMMddHHmmss", e.g. "20190130151011"
  * (according to https://docs.oracle.com/javase/8/docs/api/java/time/format/DateTimeFormatter.html formatting specifiers)
  */
object DateVersion {

  final val format: String = "yyyyMMddHHmmss"

  implicit val localDateTimeToDateVersion: ToDateVersion[LocalDateTime] = ldt =>
    DateVersion(ldt.toEpochSecond(ZoneOffset.UTC))

  implicit val zdtToDateVersion: ToDateVersion[ZonedDateTime] = zdt =>
    DateVersion(zdt.toEpochSecond)

  implicit val odtToDateVersion: ToDateVersion[OffsetDateTime] = odt =>
    DateVersion(odt.toEpochSecond)

  implicit val sqlTimestampToDateVersion: ToDateVersion[Timestamp] = ts =>
    DateVersion(ts.getTime)

  implicit val instantToDateVersion: ToDateVersion[Instant] = instant =>
    DateVersion(instant.getEpochSecond)

  implicit val dateVersionToDateVersion: ToDateVersion[DateVersion] = dv => dv

  implicit class DateVersionConverter[V](
    item: V
  )(implicit
    tdv: ToDateVersion[V]
  ) {
    def toDateVersion = tdv.toDateVersion(item)
  }

  /**
    * Validates that the given string is effectively a date formatted the same as DateVersion.format.
    *
    * @param value
    * @return
    */
  def from(value: String): Either[Throwable, DateVersion] = {
    Either
      .catchNonFatal(
        LocalDateTime.parse(value, DateTimeFormatter.ofPattern(format))
      )
      .map((dt: LocalDateTime) => DateVersion(dt.toEpochSecond(ZoneOffset.UTC)))
  }

  def from[D](
    value: D
  )(implicit
    tdv: ToDateVersion[D]
  ): Either[Throwable, DateVersion] =
    Right(tdv.toDateVersion(value))
}

final case class DateVersion private[models] (epochTimestamp: Long)
    extends Ordered[DateVersion] {

  private val formatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern(DateVersion.format)

  private val state: LocalDateTime =
    LocalDateTime.ofEpochSecond(epochTimestamp, 0, ZoneOffset.UTC)

  private val repr: String = formatter.format(state)

  override def toString: String = repr

  def compare(that: DateVersion): Int =
    this.epochTimestamp.compareTo(that.epochTimestamp)

  def toTimestamp: Timestamp = new Timestamp(epochTimestamp)

  def toOffsetDateTime: OffsetDateTime =
    OffsetDateTime.ofInstant(
      Instant.ofEpochSecond(epochTimestamp),
      ZoneOffset.UTC
    )

  def toZonedDateTime: ZonedDateTime =
    ZonedDateTime.ofInstant(
      Instant.ofEpochSecond(epochTimestamp),
      ZoneOffset.UTC
    )
}
