package com.pennsieve.admin.api.dtos

import com.pennsieve.models.Organization
import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

case class SimpleOrganizationDTO(nodeId: String, name: String, id: Int)

object SimpleOrganizationDTO {
  def apply(organization: Organization): SimpleOrganizationDTO = {
    SimpleOrganizationDTO(
      nodeId = organization.nodeId,
      name = organization.name,
      id = organization.id
    )
  }

  implicit val encoder: Encoder[SimpleOrganizationDTO] =
    deriveEncoder[SimpleOrganizationDTO]
  implicit val decoder: Decoder[SimpleOrganizationDTO] =
    deriveDecoder[SimpleOrganizationDTO]
}
