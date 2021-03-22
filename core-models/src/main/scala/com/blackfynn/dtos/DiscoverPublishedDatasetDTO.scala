package com.pennsieve.dtos

import java.time.OffsetDateTime

import cats.implicits._
import com.pennsieve.models._
import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

case class DiscoverPublishedDatasetDTO(
  id: Option[Int],
  version: Int,
  lastPublishedDate: Option[OffsetDateTime]
)

object DiscoverPublishedDatasetDTO {
  implicit val encoder: Encoder[DiscoverPublishedDatasetDTO] =
    deriveEncoder[DiscoverPublishedDatasetDTO]
  implicit val decoder: Decoder[DiscoverPublishedDatasetDTO] =
    deriveDecoder[DiscoverPublishedDatasetDTO]
}
