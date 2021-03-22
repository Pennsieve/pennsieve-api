package com.blackfynn.models

import java.time.ZonedDateTime
import java.util.UUID

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.java8.time._

final case class DatasetAsset(
  name: String,
  s3Bucket: String,
  s3Key: String,
  datasetId: Int,
  createdAt: ZonedDateTime = ZonedDateTime.now(),
  updatedAt: ZonedDateTime = ZonedDateTime.now(),
  id: UUID = UUID.randomUUID()
) {

  def etag: ETag = ETag(updatedAt)

  def s3Url: String = s"s3://$s3Bucket/$s3Key"
}

object DatasetAsset {
  implicit val decoder: Decoder[DatasetAsset] =
    deriveDecoder[DatasetAsset]
  implicit val encoder: Encoder[DatasetAsset] =
    deriveEncoder[DatasetAsset]

  /*
   * This is required by slick when using a companion object on a case
   * class that defines a database table
   */
  val tupled = (this.apply _).tupled
}
