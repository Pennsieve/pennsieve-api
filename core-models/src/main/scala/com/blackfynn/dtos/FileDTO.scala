// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

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
        file.checksum
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
