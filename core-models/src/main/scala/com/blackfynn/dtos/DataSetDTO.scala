package com.pennsieve.dtos

import java.net.URL
import java.time.ZonedDateTime

import cats.implicits._
import com.pennsieve.models._

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.java8.time._
import java.net.URL

case class DataSetDTO(
  content: WrappedDataset,
  organization: String,
  children: Option[List[PackageDTO]],
  owner: String,
  collaboratorCounts: CollaboratorCounts,
  storage: Option[Long],
  status: DatasetStatusDTO,
  publication: DatasetPublicationDTO,
  properties: List[ModelPropertiesDTO] = List.empty,
  canPublish: Boolean,
  locked: Boolean,
  bannerPresignedUrl: Option[URL] = None
)

object DataSetDTO {
  implicit val encoder: Encoder[DataSetDTO] = deriveEncoder[DataSetDTO]
  implicit val decoder: Decoder[DataSetDTO] = deriveDecoder[DataSetDTO]

  implicit val urlDecoder: Decoder[URL] = Decoder.decodeString.emap { str =>
    Either.catchNonFatal(new URL(str)).leftMap(_ => "URL")
  }
  implicit val urlEncoder: Encoder[URL] =
    Encoder.encodeString.contramap[URL](_.toString)
}
