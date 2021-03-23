package com.pennsieve.models

import java.time.ZonedDateTime

import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.{ Decoder, Encoder }
import io.circe.java8.time._

final case class Channel(
  nodeId: String,
  packageId: Int,
  name: String,
  start: Long,
  end: Long,
  unit: String,
  rate: Double,
  `type`: String,
  group: Option[String],
  lastAnnotation: Long,
  spikeDuration: Option[Long] = None,
  properties: List[ModelProperty] = Nil,
  createdAt: ZonedDateTime = ZonedDateTime.now(),
  id: Int = 0
)

object Channel {
  implicit val encoder: Encoder[Channel] = deriveEncoder[Channel]
  implicit val decoder: Decoder[Channel] = deriveDecoder[Channel]

  /*
   * This is required by slick when using a companion object on a case
   * class that defines a database table
   */
  val tupled = (this.apply _).tupled
}
