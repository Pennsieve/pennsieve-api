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

package com.pennsieve.publish.models

import com.pennsieve.models.{ File, FileType, Package }
import com.pennsieve.publish.utils.joinKeys
import enumeratum.{ CirceEnum, Enum, EnumEntry }
import enumeratum.EnumEntry.Camelcase
import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

import scala.collection.immutable
import com.typesafe.scalalogging.LazyLogging

sealed trait FileAction {
  def fileKey: String
}

/**
  * Container that describes the source and destination of an S3 file to copy.
  *
  * The `packageKey` is the "natural" path to the package that the file
  * belongs to.  If the package has a single source file, this is the source
  * file itself; otherwise, this is the directory that contains the source
  * file and its siblings.
  */
case class CopyAction(
  pkg: Package,
  file: File,
  toBucket: String, // S3 Bucket name
  baseKey: String, // folder on S3 into which the file will be copied
  fileKey: String, // path to file
  packageKey: String, // path to folder or file (see above)
  s3VersionId: Option[String] = None
) extends FileAction {
  def copyToKey: String = joinKeys(baseKey, fileKey)
}

case class KeepAction(
  pkg: Package,
  file: File,
  bucket: String, // S3 Bucket name
  baseKey: String, // folder on S3 into which the file will be copied
  fileKey: String, // path to file
  packageKey: String, // path to folder or file (see above)
  s3VersionId: Option[String] = None
) extends FileAction

case class DeleteAction(
  fromBucket: String,
  baseKey: String,
  fileKey: String,
  s3VersionId: Option[String] = None
) extends FileAction

sealed trait FileActionType extends EnumEntry with Camelcase

object FileActionType
    extends Enum[FileActionType]
    with CirceEnum[FileActionType] {

  val values: immutable.IndexedSeq[FileActionType] = findValues

  case object CopyFile extends FileActionType
  case object KeepFile extends FileActionType
  case object DeleteFile extends FileActionType
}

case class FileActionItem(
  action: FileActionType,
  bucket: String,
  path: String,
  versionId: Option[String]
)

object FileActionItem {
  implicit val encoder: Encoder[FileActionItem] = deriveEncoder[FileActionItem]
  implicit val decoder: Decoder[FileActionItem] = deriveDecoder[FileActionItem]
}

case class FileActionList(fileActionList: Seq[FileActionItem])

object FileActionList extends LazyLogging {
  def path(prefix: String, suffix: String): String =
    prefix.endsWith("/") match {
      case true => s"${prefix}${suffix}"
      case false => s"${prefix}/${suffix}"
    }

  def from(fileActions: Seq[FileAction]): FileActionList =
    new FileActionList(fileActionList = fileActions.map { fileAction =>
      val fileActionItem = fileAction match {
        case copyAction: CopyAction =>
          FileActionItem(
            action = FileActionType.CopyFile,
            bucket = copyAction.toBucket,
            path = path(copyAction.baseKey, copyAction.fileKey),
            versionId = copyAction.s3VersionId
          )
        case keepAction: KeepAction =>
          FileActionItem(
            action = FileActionType.KeepFile,
            bucket = keepAction.bucket,
            path = path(keepAction.baseKey, keepAction.fileKey),
            versionId = keepAction.s3VersionId
          )
        case deleteAction: DeleteAction =>
          FileActionItem(
            action = FileActionType.DeleteFile,
            bucket = deleteAction.fromBucket,
            path = path(deleteAction.baseKey, deleteAction.fileKey),
            versionId = deleteAction.s3VersionId
          )
      }
      logger.info(
        s"FileActionList.from() fileAction: ${fileAction} -> fileActionItem: ${fileActionItem}"
      )
      fileActionItem
    })

  implicit val encoder: Encoder[FileActionList] = deriveEncoder[FileActionList]
  implicit val decoder: Decoder[FileActionList] = deriveDecoder[FileActionList]
}
