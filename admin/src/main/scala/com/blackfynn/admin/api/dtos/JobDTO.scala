package com.pennsieve.admin.api.dtos
import java.util.UUID

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

case class JobDTO(importId: Option[UUID], s3Path: String, logs: String)

object JobDTO {
  implicit val encoder: Encoder[JobDTO] =
    deriveEncoder[JobDTO]
  implicit val decoder: Decoder[JobDTO] =
    deriveDecoder[JobDTO]
}
