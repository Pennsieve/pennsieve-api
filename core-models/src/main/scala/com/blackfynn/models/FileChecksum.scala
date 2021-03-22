package com.blackfynn.models
import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

final case class FileChecksum(chunkSize: Long, checksum: String)

object FileChecksum {
  implicit val encoder: Encoder[FileChecksum] = deriveEncoder[FileChecksum]
  implicit val decoder: Decoder[FileChecksum] = deriveDecoder[FileChecksum]
}
