// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.models

import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.{ Decoder, Encoder }

case class Manifest(
  `type`: PayloadType,
  importId: JobId,
  organizationId: Int,
  content: Payload
)

object Manifest {

  private val discriminator = "__manifestType__"

  implicit val configuration: Configuration =
    Configuration.default.withDiscriminator(discriminator)

  implicit val encodeManifest: Encoder[Manifest] =
    deriveEncoder[Manifest].mapJson(_ mapObject (_ remove discriminator))

  implicit val decodeManifest: Decoder[Manifest] = deriveDecoder[Manifest]
}
