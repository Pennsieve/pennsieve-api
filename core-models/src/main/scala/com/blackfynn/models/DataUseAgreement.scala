package com.blackfynn.models

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

import java.time.ZonedDateTime

case class DataUseAgreement(
  name: String,
  description: String,
  body: String,
  isDefault: Boolean,
  createdAt: ZonedDateTime = ZonedDateTime.now(),
  id: Int = 0
)

object DataUseAgreement {
  /*
   * This is required by slick when using a companion object on a case
   * class that defines a database table
   */
  val tupled = (this.apply _).tupled
}
