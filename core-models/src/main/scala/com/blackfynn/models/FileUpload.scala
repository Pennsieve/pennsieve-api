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

import com.pennsieve.models.FileExtensions.fileTypeMap
import com.pennsieve.models.FileType.GenericData
import org.apache.commons.io.FilenameUtils

case class FileUpload(
  uploadId: Int,
  fileName: String,
  fileType: FileType,
  filePath: Option[FilePath],
  baseName: String,
  extension: String,
  info: FileTypeInfo,
  isMasterFile: Boolean,
  size: Long,
  parent: Option[CollectionUpload] = None,
  depth: Option[Int] = None,
  annotations: List[FileUpload] = Nil
)

object FileUpload {
  def apply(
    fileName: String,
    uploadId: Int,
    size: Long,
    filePath: Option[FilePath],
    parent: Option[CollectionUpload],
    depth: Option[Int]
  ): FileUpload = {

    def getFileType: String => FileType = fileTypeMap getOrElse (_, GenericData)

    def splitFileName(fileName: String): (String, String) = {
      // if one exists, return the first extension from the map that the file name ends with
      // otherwise return no extension (the empty string)
      val extension = Utilities.getPennsieveExtension(fileName)

      // strip the extension and directories off the file name
      val baseName = FilenameUtils.getName(fileName.dropRight(extension.length))

      (baseName, extension)
    }

    val (baseName, extension) = splitFileName(fileName)
    val fileType = getFileType(extension)
    val info = FileTypeInfo.get(fileType)

    val isMasterFile: Boolean =
      info.masterExtension match {
        case Some(e) => e == extension
        case None => false
      }

    FileUpload(
      uploadId,
      fileName,
      fileType,
      filePath,
      baseName,
      extension,
      info,
      isMasterFile,
      size,
      parent,
      depth
    )
  }

  def apply(file: UserFile): FileUpload =
    FileUpload(
      file.fileName,
      file.uploadId,
      file.size,
      file.filePath,
      None,
      None
    )
}
