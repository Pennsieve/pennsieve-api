package com.blackfynn.dtos

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import java.time.{ LocalDate, OffsetDateTime }

import com.blackfynn.models.{
  DatasetPublicationStatus,
  PublicationStatus,
  PublicationType
}

case class DatasetPublicationDTO(
  publishedDataset: Option[DiscoverPublishedDatasetDTO],
  status: PublicationStatus,
  `type`: Option[PublicationType],
  embargoReleaseDate: Option[LocalDate] = None
)

object DatasetPublicationDTO {
  implicit val encoder: Encoder[DatasetPublicationDTO] =
    deriveEncoder[DatasetPublicationDTO]
  implicit val decoder: Decoder[DatasetPublicationDTO] =
    deriveDecoder[DatasetPublicationDTO]

  def apply(
    publishedDataset: Option[DiscoverPublishedDatasetDTO],
    status: Option[DatasetPublicationStatus]
  ): DatasetPublicationDTO =
    DatasetPublicationDTO(
      publishedDataset,
      status.map(_.publicationStatus).getOrElse(PublicationStatus.Draft),
      status.map(_.publicationType),
      status.flatMap(_.embargoReleaseDate)
    )
}
