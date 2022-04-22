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

import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.{ Decoder, Encoder }
import java.time.ZonedDateTime

import com.pennsieve.models.{ Degree, Role }

case class UserDTO(
  id: String,
  email: String,
  firstName: String,
  middleInitial: Option[String],
  lastName: String,
  degree: Option[Degree],
  credential: String,
  color: String,
  url: String,
  authyId: Int,
  isSuperAdmin: Boolean,
  isIntegrationUser: Boolean,
  createdAt: ZonedDateTime,
  updatedAt: ZonedDateTime,
  preferredOrganization: Option[String],
  orcid: Option[OrcidDTO],
  pennsieveTermsOfService: Option[PennsieveTermsOfServiceDTO],
  customTermsOfService: Seq[CustomTermsOfServiceDTO],
  isOwner: Option[Boolean] = None, //set to Some(true) if user was invited as owner of an org
  storage: Option[Long],
  role: Option[Role] = None,
  isPublisher: Option[Boolean] = None,
  intId: Int
)

object UserDTO {
  implicit val encoder: Encoder[UserDTO] = deriveEncoder[UserDTO]
  implicit val decoder: Decoder[UserDTO] = deriveDecoder[UserDTO]
}
