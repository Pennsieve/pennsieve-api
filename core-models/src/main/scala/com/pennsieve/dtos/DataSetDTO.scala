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

import java.net.URL
import java.time.ZonedDateTime

import cats.implicits._
import com.pennsieve.models._

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

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
  bannerPresignedUrl: Option[URL] = None,
  role: Option[Role] = None,
  packageTypeCounts: Option[Map[String, Long]] = None
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
