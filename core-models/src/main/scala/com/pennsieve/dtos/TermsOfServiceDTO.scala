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

import com.pennsieve.models.DateVersion

import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.{ Decoder, Encoder }

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

case class CustomTermsOfServiceDTO(version: String, organizationId: String)

object CustomTermsOfServiceDTO {
  def apply(
    dateVersion: ZonedDateTime,
    organizationId: String
  ): CustomTermsOfServiceDTO = CustomTermsOfServiceDTO(
    dateVersion.format(DateTimeFormatter.ofPattern(DateVersion.format)),
    organizationId
  )

  implicit val encoder: Encoder[CustomTermsOfServiceDTO] =
    deriveEncoder[CustomTermsOfServiceDTO]
  implicit val decoder: Decoder[CustomTermsOfServiceDTO] =
    deriveDecoder[CustomTermsOfServiceDTO]

}

case class PennsieveTermsOfServiceDTO(version: String)

object PennsieveTermsOfServiceDTO {
  def apply(dateVersion: ZonedDateTime): PennsieveTermsOfServiceDTO =
    PennsieveTermsOfServiceDTO(
      dateVersion.format(DateTimeFormatter.ofPattern(DateVersion.format))
    )

  implicit val encoder: Encoder[PennsieveTermsOfServiceDTO] =
    deriveEncoder[PennsieveTermsOfServiceDTO]
  implicit val decoder: Decoder[PennsieveTermsOfServiceDTO] =
    deriveDecoder[PennsieveTermsOfServiceDTO]

}
