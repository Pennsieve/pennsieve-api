package com.blackfynn.dtos

import com.blackfynn.models.ModelProperty
import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

object ModelPropertyRO {
  def fromRequestObject(p: ModelPropertyRO): ModelProperty = {
    ModelProperty(
      p.key,
      p.value,
      p.dataType.getOrElse("String"),
      p.category.getOrElse("Pennsieve"),
      p.fixed.getOrElse(false),
      p.hidden.getOrElse(false)
    )
  }

  def fromRequestObject(ps: List[ModelPropertyRO]): List[ModelProperty] = {
    ps map fromRequestObject
  }

  implicit val encoder: Encoder[ModelPropertyRO] =
    deriveEncoder[ModelPropertyRO]
  implicit val decoder: Decoder[ModelPropertyRO] =
    deriveDecoder[ModelPropertyRO]
}

case class ModelPropertyRO(
  key: String,
  value: String,
  dataType: Option[String],
  category: Option[String],
  fixed: Option[Boolean],
  hidden: Option[Boolean]
)
