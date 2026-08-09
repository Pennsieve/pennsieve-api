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

package com.pennsieve.models

import java.sql.Timestamp
import java.time.{ ZoneOffset, ZonedDateTime }
import java.util.UUID

import io.circe.derivation._
import io.circe.{ derivation, Decoder, Json }

/**
  * Malware-scan state for a file, written by scan-service directly to the
  * per-organization `files` table.
  *
  * These four columns are grouped rather than flattened onto `File` for a
  * mechanical reason: Slick projects a nested tuple as a single element, so
  * `filesSelect` stays within the 22-element ceiling and `File.tupled` keeps
  * working. Flattening all four would push `File` to 23 params, where
  * `(this.apply _).tupled` no longer compiles (there is no Function23).
  * Grouping also leaves room for further scan columns.
  *
  * `status` vocabulary: pending / scanning / clean / format_validated /
  * unscanned / infected / failed / not_required. See
  * scan-service/docs/developer.md §7.
  *
  * All fields are Option to tolerate pre-migration rows; the DB defines
  * scan_status as NOT NULL DEFAULT 'pending', and the default here matches
  * so inserts through filesSelect don't violate the constraint.
  *
  * `skipReason` is free text — it carries policy reasons ("size") but also
  * raw scanner error strings, so it is not safe to switch on.
  */
final case class FileScanInfo(
  status: Option[String] = Some("pending"),
  scannedAt: Option[ZonedDateTime] = None,
  engine: Option[String] = None,
  skipReason: Option[String] = None
)

object FileScanInfo {
  import File.zonedDateTimeDecoder

  implicit val decoder: Decoder[FileScanInfo] =
    deriveDecoder[FileScanInfo](derivation.renaming.snakeCase)
}

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
  properties: Option[Json] = None,
  assetType: Option[String] = None,
  provenanceId: Option[UUID] = None,
  published: Boolean = false,
  publishedS3VersionId: Option[String] = None,
  // Malware-scan state (scan_status, scanned_at, scan_engine,
  // scan_skip_reason). See FileScanInfo for why these are grouped.
  scan: FileScanInfo = FileScanInfo(),
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

  def isPublished: Boolean = publishedS3VersionId.nonEmpty

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
