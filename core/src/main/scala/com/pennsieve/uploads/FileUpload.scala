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

import com.pennsieve.core.utilities
import com.pennsieve.models.{ FileExtensions, FileType, FileTypeInfo }
import com.pennsieve.models.Utilities._

case class FileUpload(
  uploadId: Option[Int], // not present during manifest upload request
  fileName: String,
  escapedFileName: Option[String] = None,
  fileType: FileType,
  baseName: String,
  escapedBaseName: Option[String] = None,
  extension: String,
  info: FileTypeInfo,
  isMasterFile: Boolean,
  size: Option[Long], // not present during manifest upload request
  annotations: List[FileUpload] = Nil
)

object FileUpload {
  def apply(
    fileName: String,
    uploadId: Option[Int],
    size: Option[Long]
  ): FileUpload = {
    val (baseName, extension) = utilities.splitFileName(fileName)
    val escapedFileName = escapeName(fileName)
    val escapedBaseName = escapeName(baseName)

    val fileType = utilities.getFileType(extension)
    val info = FileTypeInfo.get(fileType)

    val isMasterFile: Boolean =
      info.masterExtension match {
        case Some(e) => e == extension
        case None => false
      }

    FileUpload(
      uploadId,
      fileName,
      Some(escapedFileName),
      fileType,
      baseName,
      Some(escapedBaseName),
      extension,
      info,
      isMasterFile,
      size
    )
  }

  def apply(fileName: String): FileUpload =
    FileUpload(fileName, None, None)

  def apply(fileName: String, uploadId: Int, size: Long): FileUpload =
    FileUpload(fileName, Some(uploadId), Some(size))

  def apply(file: S3File): FileUpload =
    FileUpload(file.fileName, file.uploadId, file.size)
}
