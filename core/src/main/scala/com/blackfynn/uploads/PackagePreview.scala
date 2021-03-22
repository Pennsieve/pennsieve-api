package com.blackfynn.uploads

import cats.implicits._
import cats.Eval
import cats.data.NonEmptyList
import com.blackfynn.domain.{ CoreError, Error }
import com.blackfynn.models._
import com.blackfynn.models.Utilities._
import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

case class PackagePreview(
  packageName: String,
  packageType: PackageType,
  packageSubtype: String,
  fileType: FileType,
  files: List[S3File],
  warnings: Iterable[String],
  groupSize: Long,
  hasWorkflow: Boolean,
  importId: String,
  icon: String,
  parent: Option[CollectionUpload] = None,
  ancestors: Option[List[CollectionUpload]] = None,
  escapedPackageName: Option[String] = None
)

object PackagePreview {

  // If there is one, use the fileType of the first non-Annotation file
  // Otherwise, use the fileType of the first Annotation file
  def getFileType(files: NonEmptyList[FileUpload]): FileType = {
    files
      .find(_.info.grouping != FileTypeGrouping.Annotation)
      .getOrElse(files.head)
      .fileType
  }

  def fromFiles(
    fileUploads: List[FileUpload],
    importId: String
  ): Either[CoreError, PackagePreview] = {

    def getWarnings(files: NonEmptyList[FileUpload]): List[String] =
      files
        .filter(_.info.validate)
        .groupBy(_.fileType)
        .map {
          case (fileType, fileUploads) =>
            fileType -> fileUploads.filter(_.isMasterFile)
        }
        .filter {
          case (_, masterFiles) =>
            masterFiles.isEmpty
        }
        .flatMap {
          case (fileType, _) =>
            FileTypeInfo.get(fileType).masterExtension
        }
        .map { extension =>
          s"missing $extension file"
        }
        .toList

    def makeGeneric(
      files: NonEmptyList[FileUpload]
    ): NonEmptyList[FileUpload] = {
      files.map(
        file =>
          file.copy(
            fileType = FileType.Data,
            info = FileTypeInfo.get(FileType.Data)
          )
      )
    }

    NonEmptyList
      .fromList(fileUploads)
      .toRight(
        Error(
          "package preview cannot be constructed from an empty list of files"
        )
      )
      .map { files =>
        val size = fileUploads.map(_.size).flatten.sum
        val warnings = getWarnings(files)

        // If a necessary master file is missing, then the files and package should be made Generic and should not be processed
        val transformedFiles =
          if (warnings.isEmpty) files else makeGeneric(files)

        val s3Files =
          (fileUploads ++ fileUploads.flatMap(_.annotations)).map(S3File.apply)

        val fileType: FileType = getFileType(transformedFiles)

        val packageInfo = FileTypeInfo.get(fileType)

        val masterFile =
          transformedFiles.find(_.isMasterFile).getOrElse(transformedFiles.head)

        val packageName = masterFile.baseName + masterFile.extension

        val hasWorkflow = transformedFiles.exists(_.info.hasWorkflow)

        PackagePreview(
          packageName,
          packageInfo.packageType,
          packageInfo.packageSubtype,
          fileType,
          s3Files,
          warnings,
          size,
          hasWorkflow,
          importId,
          packageInfo.icon.toString,
          escapedPackageName = Some(escapeName(packageName))
        )
      }
  }

  // Create a PackagePreview with an auto-generated importId
  def fromFiles(
    fileUploads: List[FileUpload]
  ): Either[CoreError, PackagePreview] =
    fromFiles(fileUploads, java.util.UUID.randomUUID.toString)

  implicit val decodePackagePreview: Decoder[PackagePreview] =
    deriveDecoder[PackagePreview]
  implicit val encodePackagePreview: Encoder[PackagePreview] =
    deriveEncoder[PackagePreview]
}
