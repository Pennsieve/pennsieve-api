package com.pennsieve.concepts

import java.util.UUID

import cats.implicits._
import com.pennsieve.models.{ ExternalId, NodeId }
import enumeratum.{ CirceEnum, Enum, EnumEntry }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.syntax._
import io.circe.{ Decoder, Encoder }
import io.circe.shapes._
import io.circe.java8.time._
import shapeless.{ :+:, CNil }

import java.util.{ Date, UUID }
import scala.collection.immutable

case class ProxyTargetLinkRequest(
  linkTarget: String,
  relationshipType: String,
  relationshipDirection: types.ProxyRelationshipDirection
)
object ProxyTargetLinkRequest {
  implicit val encoder: Encoder[ProxyTargetLinkRequest] =
    deriveEncoder[ProxyTargetLinkRequest]
  implicit val decoder: Decoder[ProxyTargetLinkRequest] =
    deriveDecoder[ProxyTargetLinkRequest]
}

package object types {

  // InstanceValues are the types our instances/records support
  type InstanceValue = String :+: Set[String] :+:
    Boolean :+: Set[Boolean] :+:
    Long :+: Set[Long] :+:
    Int :+: Set[Int] :+:
    Double :+: Set[Double] :+:
    CNil

  type InstanceDataPayload = List[InstanceDatumPayload]

  sealed trait ProxyRelationshipDirection extends EnumEntry

  object ProxyRelationshipDirection
      extends Enum[ProxyRelationshipDirection]
      with CirceEnum[ProxyRelationshipDirection] {
    val values: immutable.IndexedSeq[ProxyRelationshipDirection] = findValues
    case object FromTarget extends ProxyRelationshipDirection
    case object ToTarget extends ProxyRelationshipDirection
  }

  sealed abstract class ProxyType(override val entryName: String)
      extends EnumEntry

  object ProxyType extends Enum[ProxyType] with CirceEnum[ProxyType] {
    val values: immutable.IndexedSeq[ProxyType] = findValues
    case object Package extends ProxyType("package")
  }

  sealed trait ProxyLinkTarget

  object ProxyLinkTarget {

    implicit val encoder: Encoder[ProxyLinkTarget] =
      deriveEncoder[ProxyLinkTarget]
    implicit val decoder: Decoder[ProxyLinkTarget] =
      deriveDecoder[ProxyLinkTarget]

    case class ConceptInstance(id: UUID) extends ProxyLinkTarget
    case class ProxyInstance(`type`: ProxyType, externalId: ExternalId)
        extends ProxyLinkTarget
  }

  case class InstanceDatumPayload(name: String, value: Option[InstanceValue])

  object InstanceDatumPayload {
    implicit val encoder: Encoder[InstanceDatumPayload] =
      deriveEncoder[InstanceDatumPayload]
    implicit val decoder: Decoder[InstanceDatumPayload] =
      deriveDecoder[InstanceDatumPayload]
  }

  case class ProxyTarget(
    direction: ProxyRelationshipDirection,
    linkTarget: ProxyLinkTarget,
    relationshipType: String, // "has_a", "belongs_to" (canonical value), "is_a" ...
    relationshipData: InstanceDataPayload
  )

  object ProxyTarget {
    implicit val encoder: Encoder[ProxyTarget] =
      deriveEncoder[ProxyTarget]
    implicit val decoder: Decoder[ProxyTarget] =
      deriveDecoder[ProxyTarget]
  }

  case class CreateProxyInstancePayload(
    externalId: ExternalId,
    targets: List[ProxyTarget]
  )

  object CreateProxyInstancePayload {
    implicit val encoder: Encoder[CreateProxyInstancePayload] =
      deriveEncoder[CreateProxyInstancePayload]
    implicit val decoder: Decoder[CreateProxyInstancePayload] =
      deriveDecoder[CreateProxyInstancePayload]
  }
}
