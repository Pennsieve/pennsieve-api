package com.pennsieve.models

import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._
import io.circe.{ Decoder, Encoder }

final case class OrcidAuthorization(
  name: String,
  accessToken: String,
  expiresIn: Long,
  tokenType: String,
  orcid: String,
  scope: String,
  refreshToken: String
)

object OrcidAuthorization {

  implicit val customConfig: Configuration =
    Configuration.default.withSnakeCaseMemberNames.withDefaults
  implicit val snakyEncoder: Encoder[OrcidAuthorization] = deriveEncoder
  implicit val snakyDecoder: Decoder[OrcidAuthorization] = deriveDecoder
}
