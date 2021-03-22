package com.blackfynn.dtos

import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.{ Decoder, Encoder }

import com.blackfynn.models.DatasetIgnoreFile

case class DatasetIgnoreFilesDTO(
  datasetId: Int,
  ignoreFiles: Seq[DatasetIgnoreFile]
)

object DatasetIgnoreFilesDTO {
  implicit val encoder: Encoder[DatasetIgnoreFilesDTO] =
    deriveEncoder[DatasetIgnoreFilesDTO]
  implicit val decoder: Decoder[DatasetIgnoreFilesDTO] =
    deriveDecoder[DatasetIgnoreFilesDTO]
}
