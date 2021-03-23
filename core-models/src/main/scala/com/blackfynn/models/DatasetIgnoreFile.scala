package com.pennsieve.models

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

final case class DatasetIgnoreFile(
  datasetId: Int,
  fileName: String,
  id: Int = 0
)

object DatasetIgnoreFile {
  implicit val decoder: Decoder[DatasetIgnoreFile] =
    deriveDecoder[DatasetIgnoreFile]
  implicit val encoder: Encoder[DatasetIgnoreFile] =
    deriveEncoder[DatasetIgnoreFile]

  /*
   * This is required by slick when using a companion object on a case
   * class that defines a database table
   */
  val tupled = (this.apply _).tupled
}
