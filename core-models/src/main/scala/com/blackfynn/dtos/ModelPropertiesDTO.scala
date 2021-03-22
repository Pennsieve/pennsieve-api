package com.blackfynn.dtos

import com.blackfynn.models.ModelProperty
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.{ Decoder, Encoder }

case class ModelPropertiesDTO(
  category: String,
  properties: List[ModelPropertyDTO]
)

object ModelPropertiesDTO {

  implicit val encoder: Encoder[ModelPropertiesDTO] =
    deriveEncoder[ModelPropertiesDTO]
  implicit val decoder: Decoder[ModelPropertiesDTO] =
    deriveDecoder[ModelPropertiesDTO]

  def fromModelProperties(
    properties: List[ModelProperty]
  ): List[ModelPropertiesDTO] = {
    properties
      .groupBy(_.category)
      .map(
        group =>
          ModelPropertiesDTO(
            group._1,
            group._2.map(gnp => ModelPropertyDTO(gnp))
          )
      )
      .toList
  }
}
