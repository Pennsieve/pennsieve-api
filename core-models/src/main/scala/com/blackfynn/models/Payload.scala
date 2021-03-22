package com.blackfynn.models

import cats.syntax.functor._
import io.circe._
import io.circe.generic.extras._
import io.circe.generic.extras.semiauto._

sealed trait Payload {
  val packageId: Int
  val datasetId: Int
  val userId: Int
  val encryptionKey: String
  val `type`: PayloadType
}

object Payload {
  val discriminator = "__type__"

  implicit val configuration: Configuration =
    Configuration.default withDiscriminator discriminator

  implicit def encoder: Encoder[Payload] =
    deriveEncoder[Payload]
      .mapJson(_ mapObject (_ remove discriminator))

  implicit def decoder: Decoder[Payload] =
    List[Decoder[Payload]](Decoder[Upload].widen, Decoder[Workflow].widen)
      .reduceLeft(_ or _)
}

case class Upload(
  packageId: Int,
  datasetId: Int,
  userId: Int,
  encryptionKey: String,
  files: List[String], // S3 urls: s3://${bucket}/${path}
  size: Long
) extends Payload {
  override val `type`: PayloadType = PayloadType.Upload
}

object Upload {

  implicit val configuration: Configuration =
    Configuration.default

  implicit val encoder: Encoder[Upload] = deriveEncoder[Upload]
  implicit val decoder: Decoder[Upload] = deriveDecoder[Upload]
}

sealed trait Workflow extends Payload

object Workflow {

  implicit val configuration: Configuration =
    Configuration.default

  implicit val encoder: Encoder[Workflow] = deriveEncoder[Workflow]
  implicit val decoder: Decoder[Workflow] = List[Decoder[Workflow]](
    Decoder[ETLAppendWorkflow].widen,
    Decoder[ETLExportWorkflow].widen,
    Decoder[ETLWorkflow].widen
  ).reduceLeft(_ or _)
}

// Import ETL workflow
final case class ETLWorkflow(
  packageId: Int,
  datasetId: Int,
  userId: Int,
  encryptionKey: String,
  files: List[String],
  assetDirectory: String,
  fileType: FileType,
  packageType: PackageType
) extends Workflow {
  override val `type`: PayloadType = PayloadType.Workflow
}

object ETLWorkflow {

  implicit val configuration: Configuration =
    Configuration.default

  implicit val encoder: Encoder[ETLWorkflow] = deriveEncoder[ETLWorkflow]
  implicit val decoder: Decoder[ETLWorkflow] = deriveDecoder[ETLWorkflow]
}

final case class ETLAppendWorkflow(
  packageId: Int,
  datasetId: Int,
  userId: Int,
  encryptionKey: String,
  files: List[String],
  assetDirectory: String,
  fileType: FileType,
  packageType: PackageType,
  channels: List[Channel]
) extends Workflow {
  override val `type`: PayloadType = PayloadType.Append
}

object ETLAppendWorkflow {

  implicit val configuration: Configuration =
    Configuration.default

  implicit val encoder: Encoder[ETLAppendWorkflow] =
    deriveEncoder[ETLAppendWorkflow]
  implicit val decoder: Decoder[ETLAppendWorkflow] =
    deriveDecoder[ETLAppendWorkflow]
}

/**
  * JSS `JobMonitor` and `JobWatchdog` both expect the `packageId` on the payload to be the `packageId` for the
  * currently being processed package, which is why it is treated as the "target" of export, rather than the source.
  *
  * Please see `updatePackageFlow` in JSS for details.
  */
final case class ETLExportWorkflow(
  packageId: Int, // target package
  datasetId: Int,
  userId: Int,
  encryptionKey: String,
  fileType: FileType, // target package
  packageType: PackageType, // target package
  sourcePackageId: Int, // source package
  sourcePackageType: PackageType // source package
) extends Workflow {
  override val `type`: PayloadType = PayloadType.Export
}

object ETLExportWorkflow {

  implicit val configuration: Configuration =
    Configuration.default

  implicit val encoder: Encoder[ETLExportWorkflow] =
    deriveEncoder[ETLExportWorkflow]
  implicit val decoder: Decoder[ETLExportWorkflow] =
    deriveDecoder[ETLExportWorkflow]
}
