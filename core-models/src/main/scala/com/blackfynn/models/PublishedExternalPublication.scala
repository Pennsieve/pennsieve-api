package com.blackfynn.models

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

case class PublishedExternalPublication(
  doi: Doi,
  relationshipType: Option[RelationshipType] = None
)

object PublishedExternalPublication {

  def apply(
    externalPublication: ExternalPublication
  ): PublishedExternalPublication =
    PublishedExternalPublication(
      doi = externalPublication.doi,
      relationshipType = Some(externalPublication.relationshipType)
    )

  implicit val decoder: Decoder[PublishedExternalPublication] =
    deriveDecoder[PublishedExternalPublication]
  implicit val encoder: Encoder[PublishedExternalPublication] =
    deriveEncoder[PublishedExternalPublication]
}
