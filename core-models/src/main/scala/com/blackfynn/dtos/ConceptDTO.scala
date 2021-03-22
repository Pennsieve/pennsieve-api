package com.blackfynn.dtos

import java.time.OffsetDateTime
import java.util.UUID

import com.blackfynn.concepts.types._
import com.blackfynn.models.NodeId
import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.java8.time._
import io.circe.shapes._

case class ConceptDTO(
  name: String,
  displayName: String,
  description: String,
  createdBy: NodeId,
  updatedBy: NodeId,
  locked: Boolean,
  id: UUID,
  count: Int,
  createdAt: OffsetDateTime,
  updatedAt: OffsetDateTime
)

object ConceptDTO {
  implicit val encoder: Encoder[ConceptDTO] =
    deriveEncoder[ConceptDTO]
  implicit val decoder: Decoder[ConceptDTO] =
    deriveDecoder[ConceptDTO]
}

case class ConceptInstanceDTO(
  id: UUID,
  `type`: String,
  values: List[InstanceDatumDTO],
  createdAt: OffsetDateTime,
  updatedAt: OffsetDateTime,
  createdBy: NodeId,
  updatedBy: NodeId
)

object ConceptInstanceDTO {
  implicit val encoder: Encoder[ConceptInstanceDTO] =
    deriveEncoder[ConceptInstanceDTO]
  implicit val decoder: Decoder[ConceptInstanceDTO] =
    deriveDecoder[ConceptInstanceDTO]
}

case class InstanceDatumDTO(
  name: String,
  displayName: String,
  value: InstanceValue, //this can be more than just String
  locked: Boolean,
  required: Boolean,
  default: Boolean,
  conceptTitle: Boolean
)

object InstanceDatumDTO {
  implicit val encoder: Encoder[InstanceDatumDTO] =
    deriveEncoder[InstanceDatumDTO]
  implicit val decoder: Decoder[InstanceDatumDTO] =
    deriveDecoder[InstanceDatumDTO]
}
