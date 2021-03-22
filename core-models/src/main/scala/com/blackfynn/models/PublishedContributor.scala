package com.blackfynn.models

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

case class PublishedContributor(
  first_name: String,
  last_name: String,
  orcid: Option[String],
  middle_initial: Option[String] = None,
  degree: Option[Degree] = None
)
object PublishedContributor {

  implicit val decoder: Decoder[PublishedContributor] =
    deriveDecoder[PublishedContributor]
  implicit val encoder: Encoder[PublishedContributor] =
    deriveEncoder[PublishedContributor]
}
