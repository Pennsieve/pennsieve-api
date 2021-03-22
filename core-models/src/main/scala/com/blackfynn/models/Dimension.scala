// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

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
