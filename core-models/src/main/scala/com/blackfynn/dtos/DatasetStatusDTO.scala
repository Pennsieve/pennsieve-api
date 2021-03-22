package com.pennsieve.dtos

import com.pennsieve.models.{ DatasetStatus, DatasetStatusInUse }

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

case class DatasetStatusDTO(
  id: Int,
  name: String,
  displayName: String,
  color: String,
  inUse: DatasetStatusInUse
)

object DatasetStatusDTO {
  def apply(
    status: DatasetStatus,
    inUse: DatasetStatusInUse
  ): DatasetStatusDTO =
    DatasetStatusDTO(
      id = status.id,
      name = status.name,
      displayName = status.displayName,
      color = status.color,
      inUse = inUse
    )

  def apply(
    statusAndUsage: (DatasetStatus, DatasetStatusInUse)
  ): DatasetStatusDTO =
    DatasetStatusDTO(statusAndUsage._1, statusAndUsage._2)

  implicit val encoder: Encoder[DatasetStatusDTO] =
    deriveEncoder[DatasetStatusDTO]
  implicit val decoder: Decoder[DatasetStatusDTO] =
    deriveDecoder[DatasetStatusDTO]
}
