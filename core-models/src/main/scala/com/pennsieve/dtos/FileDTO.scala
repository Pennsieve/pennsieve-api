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

import com.pennsieve.models.{ File, Package }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.{ Decoder, Encoder }

final case class FileDTO(content: FileContent)

final case class SimpleFileDTO(content: SimpleFileContent)

object FileDTO {
  type TypeToFileDTO = Map[String, List[FileDTO]]

  implicit val encoder: Encoder[FileDTO] = deriveEncoder[FileDTO]
  implicit val decoder: Decoder[FileDTO] = deriveDecoder[FileDTO]

  def apply(
    file: File,
    parent: Package,
    md5: Option[String] = None
  ): FileDTO = {
    new FileDTO(
      content = FileContent(
        parent.nodeId,
        file.name,
        file.fileType,
        file.fileName,
        file.s3Bucket,
        file.s3Key,
        file.objectType,
        file.size,
        file.createdAt,
        file.updatedAt,
        file.id,
        md5,
        file.checksum,
        file.properties,
        file.assetType,
        file.provenanceId,
        file.published
      )
    )
  }
}

object SimpleFileDTO {
  type TypeToSimpleFile = Map[String, List[SimpleFileDTO]]

  implicit val encoder: Encoder[SimpleFileDTO] = deriveEncoder[SimpleFileDTO]
  implicit val decoder: Decoder[SimpleFileDTO] = deriveDecoder[SimpleFileDTO]

  def apply(file: File, parent: Package): SimpleFileDTO =
    SimpleFileDTO(
      content = SimpleFileContent(
        parent.nodeId,
        file.name,
        file.fileType,
        file.createdAt,
        file.updatedAt,
        file.checksum,
        file.id,
        file.fileName,
        file.size
      )
    )
}
