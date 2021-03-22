package com.blackfynn.models

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

case class PublishedCollection(name: String)
object PublishedCollection {

  implicit val decoder: Decoder[PublishedCollection] =
    deriveDecoder[PublishedCollection]
  implicit val encoder: Encoder[PublishedCollection] =
    deriveEncoder[PublishedCollection]
}
