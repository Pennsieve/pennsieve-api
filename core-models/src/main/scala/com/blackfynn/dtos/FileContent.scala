package com.blackfynn.dtos

import java.time.ZonedDateTime

import com.blackfynn.models.{ FileChecksum, FileObjectType, FileType }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.{ Decoder, Encoder }

case class FileContent(
  packageId: String,
  name: String,
  fileType: FileType,
  filename: String,
  s3bucket: String,
  s3key: String,
  objectType: FileObjectType,
  size: Long,
  createdAt: ZonedDateTime,
  updatedAt: ZonedDateTime,
  id: Int,
  md5: Option[String] = None,
  checksum: Option[FileChecksum] = None
)

final case class SimpleFileContent(
  packageId: String,
  name: String,
  fileType: FileType,
  createdAt: ZonedDateTime,
  updatedAt: ZonedDateTime,
  checksum: Option[FileChecksum] = None,
  id: Int,
  filename: String,
  size: Long
)

object FileContent {
  import io.circe.java8.time._

  implicit val encoder: Encoder[FileContent] = deriveEncoder[FileContent]
  implicit val decoder: Decoder[FileContent] = deriveDecoder[FileContent]
}

object SimpleFileContent {
  import io.circe.java8.time._

  implicit val encoder: Encoder[SimpleFileContent] =
    deriveEncoder[SimpleFileContent]
  implicit val decoder: Decoder[SimpleFileContent] =
    deriveDecoder[SimpleFileContent]
}
