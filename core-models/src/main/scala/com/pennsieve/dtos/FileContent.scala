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

package com.pennsieve.dtos

import java.time.ZonedDateTime

import com.pennsieve.models.{ FileChecksum, FileObjectType, FileType }
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

  implicit val encoder: Encoder[FileContent] = deriveEncoder[FileContent]
  implicit val decoder: Decoder[FileContent] = deriveDecoder[FileContent]
}

object SimpleFileContent {

  implicit val encoder: Encoder[SimpleFileContent] =
    deriveEncoder[SimpleFileContent]
  implicit val decoder: Decoder[SimpleFileContent] =
    deriveDecoder[SimpleFileContent]
}
