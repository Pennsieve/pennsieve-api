package com.blackfynn.dtos

import java.time.ZonedDateTime
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.{ Decoder, Encoder }

final case class ExternalFileDTO(
  packageId: Int,
  location: String,
  description: Option[String],
  createdAt: ZonedDateTime,
  updatedAt: ZonedDateTime
)

object ExternalFileDTO {
  import io.circe.java8.time._

  implicit val encoder: Encoder[ExternalFileDTO] =
    deriveEncoder[ExternalFileDTO]
  implicit val decoder: Decoder[ExternalFileDTO] =
    deriveDecoder[ExternalFileDTO]
}
