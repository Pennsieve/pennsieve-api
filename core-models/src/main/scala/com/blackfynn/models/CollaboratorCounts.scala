package com.pennsieve.models

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

case class CollaboratorCounts(users: Int, organizations: Int, teams: Int)

object CollaboratorCounts {
  implicit val encoder: Encoder[CollaboratorCounts] =
    deriveEncoder[CollaboratorCounts]
  implicit val decoder: Decoder[CollaboratorCounts] =
    deriveDecoder[CollaboratorCounts]
}
