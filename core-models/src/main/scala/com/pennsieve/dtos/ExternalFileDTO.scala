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

import java.time.ZonedDateTime
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.{ Decoder, Encoder }

final case class ExternalFileDTO(
  packageId: Int,
  location: String,
  description: Option[String],
  createdAt: ZonedDateTime,
  updatedAt: ZonedDateTime
)

object ExternalFileDTO {

  implicit val encoder: Encoder[ExternalFileDTO] =
    deriveEncoder[ExternalFileDTO]
  implicit val decoder: Decoder[ExternalFileDTO] =
    deriveDecoder[ExternalFileDTO]
}
