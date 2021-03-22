// Copyright (c) 2019 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.models

import java.time.ZonedDateTime
import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.java8.time._

final case class Contributor(
  firstName: Option[String],
  lastName: Option[String],
  middleInitial: Option[String],
  degree: Option[Degree],
  email: Option[String],
  orcid: Option[String],
  userId: Option[Int] = None,
  updatedAt: ZonedDateTime = ZonedDateTime.now(),
  createdAt: ZonedDateTime = ZonedDateTime.now(),
  id: Int = 0
)

object Contributor {

  implicit val encoder: Encoder[Contributor] = deriveEncoder[Contributor]
  implicit val decoder: Decoder[Contributor] = deriveDecoder[Contributor]

  /*
   * This is required by slick when using a companion object on a case
   * class that defines a database table
   */
  val tupled = (this.apply _).tupled
}
