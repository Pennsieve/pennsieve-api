package com.blackfynn.models

import java.time.ZonedDateTime

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

final case class DatasetStatusLog(
  datasetId: Int,
  statusId: Option[Int],
  statusName: String,
  statusDisplayName: String,
  userId: Option[Int],
  createdAt: ZonedDateTime = ZonedDateTime.now
)

object DatasetStatusLog {
  implicit val decoder: Decoder[DatasetStatusLog] =
    deriveDecoder[DatasetStatusLog]
  implicit val encoder: Encoder[DatasetStatusLog] =
    deriveEncoder[DatasetStatusLog]

  /*
   * This is required by slick when using a companion object on a case
   * class that defines a database table
   */
  val tupled = (this.apply _).tupled
}
