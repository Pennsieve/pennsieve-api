package com.pennsieve.models

import java.util.UUID

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

case class PublishedFile(
  s3Key: String,
  s3VersionId: Option[String] = None,
  sourceFileId: Option[UUID] = None
)
object PublishedFile {

  implicit val decoder: Decoder[PublishedFile] =
    deriveDecoder[PublishedFile]
  implicit val encoder: Encoder[PublishedFile] =
    deriveEncoder[PublishedFile]
}
