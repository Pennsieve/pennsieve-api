/*
 * Copyright 2021 University of Pennsylvania
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pennsieve.uploads
import com.pennsieve.models.FileHash
import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import com.pennsieve.models.Utilities._

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
    S3File(file.uploadId, file.fileName, cleanS3Key(file.fileName), file.size)

  def apply(uploadId: Int, fileName: String, size: Long): S3File =
    S3File(Some(uploadId), fileName, cleanS3Key(fileName), Some(size))

  def apply(
    uploadId: Option[Int],
    fileName: String,
    size: Option[Long]
  ): S3File =
    S3File(uploadId, fileName, cleanS3Key(fileName), size)

  implicit val decodeS3File: Decoder[S3File] =
    deriveDecoder[S3File]
  implicit val encodeS3File: Encoder[S3File] =
    deriveEncoder[S3File]

}
