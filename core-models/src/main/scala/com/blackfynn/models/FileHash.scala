package com.pennsieve.models

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

final case class FileHash(hash: String) extends AnyVal

object FileHash {
  implicit val encoder: Encoder[FileHash] = deriveEncoder[FileHash]

  private val objectDecoder: Decoder[FileHash] = deriveDecoder[FileHash]
  private val stringDecoder: Decoder[FileHash] = Decoder[String].map { hash =>
    FileHash(hash)
  }
  implicit def decoder: Decoder[FileHash] =
    List[Decoder[FileHash]](objectDecoder, stringDecoder)
      .reduceLeft(_ or _)
}
