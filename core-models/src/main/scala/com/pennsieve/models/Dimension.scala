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
import java.time.ZonedDateTime

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.java8.time._

final case class Dimension(
  packageId: Int,
  name: String,
  length: Long,
  resolution: Option[Double],
  unit: Option[String],
  assignment: DimensionAssignment,
  createdAt: ZonedDateTime = ZonedDateTime.now(),
  updatedAt: ZonedDateTime = ZonedDateTime.now(),
  id: Int = 0
)
object Dimension {
  implicit val encoder: Encoder[Dimension] = deriveEncoder[Dimension]
  implicit val decoder: Decoder[Dimension] = deriveDecoder[Dimension]

  /*
   * This is required by slick when using a companion object on a case
   * class that defines a database table
   */
  val tupled = (this.apply _).tupled
}

sealed trait DimensionAssignment extends EnumEntry with Snakecase

object DimensionAssignment
    extends Enum[DimensionAssignment]
    with CirceEnum[DimensionAssignment] {
  val values = findValues

  case object SpatialX extends DimensionAssignment
  case object SpatialY extends DimensionAssignment
  case object SpatialZ extends DimensionAssignment
  case object Time extends DimensionAssignment
  case object Color extends DimensionAssignment
  case object Other extends DimensionAssignment

}

case class DimensionProperties(
  name: String,
  length: Long,
  resolution: Option[Double],
  unit: Option[String],
  assignment: DimensionAssignment
)
object DimensionProperties {
  implicit val encoder: Encoder[DimensionProperties] =
    deriveEncoder[DimensionProperties]
  implicit val decoder: Decoder[DimensionProperties] =
    deriveDecoder[DimensionProperties]
}
