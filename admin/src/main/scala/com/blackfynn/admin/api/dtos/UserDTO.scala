package com.blackfynn.admin.api.dtos

import com.blackfynn.dtos.{
  CustomTermsOfServiceDTO,
  PennsieveTermsOfServiceDTO
}
import com.blackfynn.models.User
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
