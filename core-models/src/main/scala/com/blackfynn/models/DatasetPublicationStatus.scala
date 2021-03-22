package com.pennsieve.models

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

import java.time.{ LocalDate, ZonedDateTime }

final case class DatasetPublicationStatus(
  datasetId: Int,
  publicationStatus: PublicationStatus,
  publicationType: PublicationType,
  createdBy: Option[Int],
  comments: Option[String] = None,
  embargoReleaseDate: Option[LocalDate] = None,
  createdAt: ZonedDateTime = ZonedDateTime.now,
  id: Int = 0
)

object DatasetPublicationStatus {
  implicit val decoder: Decoder[DatasetPublicationStatus] =
    deriveDecoder[DatasetPublicationStatus]
  implicit val encoder: Encoder[DatasetPublicationStatus] =
    deriveEncoder[DatasetPublicationStatus]

  /*
   * This is required by slick when using a companion object on a case
   * class that defines a database table
   */
  val tupled = (this.apply _).tupled
}
