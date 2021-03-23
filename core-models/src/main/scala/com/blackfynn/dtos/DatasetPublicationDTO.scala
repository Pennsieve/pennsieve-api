/*
 * Copyright 2021 University of Pennsylvania
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pennsieve.dtos

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import java.time.{ LocalDate, OffsetDateTime }

import com.pennsieve.models.{
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
