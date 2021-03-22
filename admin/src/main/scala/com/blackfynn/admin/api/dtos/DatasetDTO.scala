package com.blackfynn.admin.api.dtos

import com.blackfynn.models.Dataset
import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

case class SimpleDatasetDTO(nodeId: String, name: String, id: Int)

object SimpleDatasetDTO {
  def apply(dataset: Dataset): SimpleDatasetDTO = {
    SimpleDatasetDTO(
      nodeId = dataset.nodeId,
      name = dataset.name,
      id = dataset.id
    )
  }

  implicit val encoder: Encoder[SimpleDatasetDTO] =
    deriveEncoder[SimpleDatasetDTO]
  implicit val decoder: Decoder[SimpleDatasetDTO] =
    deriveDecoder[SimpleDatasetDTO]

}
