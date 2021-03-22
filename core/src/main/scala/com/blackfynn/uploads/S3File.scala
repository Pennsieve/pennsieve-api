package com.blackfynn.uploads
import com.blackfynn.models.FileHash
import com.blackfynn.models.FileHash._
import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import com.blackfynn.models.Utilities._

case class S3File(
  uploadId: Option[Int],
  fileName: String,
  escapedFileName: String,
  size: Option[Long],
  fileHash: Option[FileHash] = None,
  chunkSize: Option[Long] = None
)

object S3File {
  def apply(file: FileUpload): S3File =
    S3File(file.uploadId, file.fileName, escapeName(file.fileName), file.size)

  def apply(uploadId: Int, fileName: String, size: Long): S3File =
    S3File(Some(uploadId), fileName, escapeName(fileName), Some(size))

  def apply(
    uploadId: Option[Int],
    fileName: String,
    size: Option[Long]
  ): S3File =
    S3File(uploadId, fileName, escapeName(fileName), size)

  implicit val decodeS3File: Decoder[S3File] =
    deriveDecoder[S3File]
  implicit val encodeS3File: Encoder[S3File] =
    deriveEncoder[S3File]

}
