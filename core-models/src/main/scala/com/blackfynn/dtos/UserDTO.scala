package com.blackfynn.dtos

import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.{ Decoder, Encoder }
import io.circe.java8.time._
import java.time.ZonedDateTime

import com.blackfynn.models.{ Degree, Role }

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
