package com.pennsieve.models

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

case class UserFile(
  uploadId: Int,
  fileName: String,
  size: Long,
  filePath: Option[FilePath] = None
)

object UserFile {
  implicit val decoder: Decoder[UserFile] = deriveDecoder[UserFile]
  implicit val encoder: Encoder[UserFile] = deriveEncoder[UserFile]
}
