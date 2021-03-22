package com.pennsieve.dtos

import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.{ Decoder, Encoder }

case class OrcidDTO(name: String, orcid: String)
object OrcidDTO {
  implicit val encoder: Encoder[OrcidDTO] = deriveEncoder[OrcidDTO]
  implicit val decoder: Decoder[OrcidDTO] = deriveDecoder[OrcidDTO]
}
