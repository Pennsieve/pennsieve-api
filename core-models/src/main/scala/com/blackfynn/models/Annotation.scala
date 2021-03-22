// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.models

import java.time.ZonedDateTime

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.java8.time._

final case class PathElement(elementType: String, data: List[Double])

object PathElement {
  implicit val encoder: Encoder[PathElement] = deriveEncoder[PathElement]
  implicit val decoder: Decoder[PathElement] = deriveDecoder[PathElement]
}

final case class Annotation(
  creatorId: Int,
  layerId: Int,
  description: String,
  path: List[PathElement] = Nil,
  attributes: List[ModelProperty] = Nil,
  createdAt: ZonedDateTime = ZonedDateTime.now(),
  updatedAt: ZonedDateTime = ZonedDateTime.now(),
  id: Int = 0
)

object Annotation {
  implicit val encoder: Encoder[Annotation] = deriveEncoder[Annotation]
  implicit val decoder: Decoder[Annotation] = deriveDecoder[Annotation]

  /*
   * This is required by slick when using a companion object on a case
   * class that defines a database table
   */
  val tupled = (this.apply _).tupled
}
