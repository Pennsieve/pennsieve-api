package com.pennsieve.models

import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.{ Decoder, Encoder }

case class CollectionUpload(
  id: String,
  name: String,
  parentId: Option[String],
  depth: Int
)

object CollectionUpload {
  implicit val decoder: Decoder[CollectionUpload] =
    deriveDecoder[CollectionUpload]
  implicit val encoder: Encoder[CollectionUpload] =
    deriveEncoder[CollectionUpload]

}
