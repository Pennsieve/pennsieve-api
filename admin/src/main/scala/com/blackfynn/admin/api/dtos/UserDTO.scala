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

package com.pennsieve.admin.api.dtos

import com.pennsieve.dtos.{
  CustomTermsOfServiceDTO,
  PennsieveTermsOfServiceDTO
}
import com.pennsieve.models.User
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.{ Decoder, Encoder }
import io.circe.java8.time._
import java.time.ZonedDateTime

case class UserDTO(
  nodeId: String,
  email: String,
  firstName: String,
  lastName: String,
  color: String,
  url: String,
  isSuperAdmin: Boolean,
  preferredOrganizationId: Option[Int],
  pennsieveTermsOfService: Option[PennsieveTermsOfServiceDTO],
  customTermsOfService: Seq[CustomTermsOfServiceDTO],
  status: Boolean,
  updatedAt: ZonedDateTime,
  createdAt: ZonedDateTime,
  id: Int
)

object UserDTO {
  def apply(user: User): UserDTO = {
    UserDTO(
      nodeId = user.nodeId,
      email = user.email,
      firstName = user.firstName,
      lastName = user.lastName,
      color = user.color,
      url = user.url,
      isSuperAdmin = user.isSuperAdmin,
      preferredOrganizationId = user.preferredOrganizationId,
      pennsieveTermsOfService = None,
      customTermsOfService = Seq.empty,
      status = user.status,
      updatedAt = user.updatedAt,
      createdAt = user.createdAt,
      id = user.id
    )
  }

  def apply(
    user: User,
    pennsieveTermsOfService: Option[PennsieveTermsOfServiceDTO],
    customTermsOfService: Seq[CustomTermsOfServiceDTO]
  ): UserDTO = {
    UserDTO(
      nodeId = user.nodeId,
      email = user.email,
      firstName = user.firstName,
      lastName = user.lastName,
      color = user.color,
      url = user.url,
      isSuperAdmin = user.isSuperAdmin,
      preferredOrganizationId = user.preferredOrganizationId,
      pennsieveTermsOfService = pennsieveTermsOfService,
      customTermsOfService = customTermsOfService,
      status = user.status,
      updatedAt = user.updatedAt,
      createdAt = user.createdAt,
      id = user.id
    )
  }
  implicit val bfTermsEncoder: Encoder[PennsieveTermsOfServiceDTO] =
    deriveEncoder[PennsieveTermsOfServiceDTO]
  implicit val bfTermsDecoder: Decoder[PennsieveTermsOfServiceDTO] =
    deriveDecoder[PennsieveTermsOfServiceDTO]

  implicit val customTermsEncoder: Encoder[CustomTermsOfServiceDTO] =
    deriveEncoder[CustomTermsOfServiceDTO]
  implicit val customTermsDecoder: Decoder[CustomTermsOfServiceDTO] =
    deriveDecoder[CustomTermsOfServiceDTO]

  implicit val encoder: Encoder[UserDTO] = deriveEncoder[UserDTO]
  implicit val decoder: Decoder[UserDTO] = deriveDecoder[UserDTO]

}
