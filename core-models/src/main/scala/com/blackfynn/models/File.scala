// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.models

import java.sql.Timestamp
import java.time.{ ZoneOffset, ZonedDateTime }
import java.util.UUID

import io.circe.derivation._
import io.circe.{ derivation, Decoder }

final case class File(
  packageId: Int,
  name: String,
  fileType: FileType,
  s3Bucket: String,
  s3Key: String,
  objectType: FileObjectType,
  processingState: FileProcessingState,
  size: Long,
  checksum: Option[FileChecksum] = None,
  createdAt: ZonedDateTime = ZonedDateTime.now(),
  updatedAt: ZonedDateTime = ZonedDateTime.now(),
  uuid: UUID = UUID.randomUUID(),
  uploadedState: Option[FileState] = None,
  id: Int = 0
) {

  /**
    * The upload/ETL process strips the file extension from the `name` field.
    * s3Key has the right extension but may have a different encoding for fileName
    * This method returns the full filename with the correct naming convention, eg "notes with spaces.txt"
    * rather than "notes_with_spaces.txt" (s3Key).
    */
  def fileName: String = {
    val extension = Utilities.getFullExtension(s3Key).getOrElse("")
    name.endsWith(extension) match {
      case true => name
      case _ => s"${name}.${extension}"
    }
  }

  /**
    * Return the extension of a file based on its S3 key
    * */
  def fileExtension: Option[String] =
    Utilities.getFullExtension(s3Key)

}

object File {
  implicit val zonedDateTimeDecoder: Decoder[ZonedDateTime] =
    Decoder[String]
      .map { timestampString =>
        ZonedDateTime.ofInstant(
          Timestamp.valueOf(timestampString.replace('T', ' ')).toInstant,
          ZoneOffset.UTC
        )
      }

  implicit val decoder: Decoder[File] =
    deriveDecoder[File](derivation.renaming.snakeCase)

  /*
   * This is required by slick when using a companion object on a case
   * class that defines a database table
   */
  val tupled = (this.apply _).tupled
}
