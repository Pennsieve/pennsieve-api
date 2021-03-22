package com.blackfynn.uploads

import com.blackfynn.core.utilities
import com.blackfynn.models.{ FileExtensions, FileType, FileTypeInfo }
import com.blackfynn.models.Utilities._

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
