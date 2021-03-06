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

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

import enumeratum.EnumEntry.{ Snakecase, UpperSnakecase }
import enumeratum._

import java.time.ZonedDateTime

/**
  * The `name` column is a backwards-compatible identifier containing the old
  * enumerated status options. For new status options, `name` is generated by
  * converting `displayName` to UPPER_SNAKE_CASE.
  *
  * The default dataset status options are tied back to Blackfynn`s hardcoded
  * defaults via the `originalName` column.
  */
case class DatasetStatus(
  name: String,
  displayName: String,
  color: String,
  originalName: Option[DefaultDatasetStatus],
  createdAt: ZonedDateTime = ZonedDateTime.now(),
  updatedAt: ZonedDateTime = ZonedDateTime.now(),
  id: Int = 0
)

case class DatasetStatusInUse(value: Boolean) extends AnyVal

object DatasetStatusInUse {
  implicit val inUseEncoder: Encoder[DatasetStatusInUse] =
    Encoder.encodeBoolean.contramap[DatasetStatusInUse](_.value)
  implicit val inUseDecoder: Decoder[DatasetStatusInUse] =
    Decoder.decodeBoolean.map(DatasetStatusInUse(_))
}

sealed trait DefaultDatasetStatus extends EnumEntry with UpperSnakecase

object DefaultDatasetStatus
    extends Enum[DefaultDatasetStatus]
    with CirceEnum[DefaultDatasetStatus] {
  val values = findValues
  case object NoStatus extends DefaultDatasetStatus
  case object WorkInProgress extends DefaultDatasetStatus
  case object Completed extends DefaultDatasetStatus
  case object InReview extends DefaultDatasetStatus
}
