// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.etl.`data-cli`

import com.blackfynn.db.{ FilesMapper, OrganizationsMapper, PackagesMapper }
import com.blackfynn.etl.`data-cli`.container._
import com.blackfynn.etl.`data-cli`.exceptions._
import com.blackfynn.models.{
  File,
  FileExtensions,
  FileObjectType,
  FileProcessingState,
  Organization
}
import com.blackfynn.traits.PostgresProfile.api._
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import io.circe.parser.decode
import io.circe.syntax._
import java.io.{ File => JavaFile }

import com.blackfynn.core.utilities
import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

import scala.io.Source
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try
import scopt.OptionParser

object CreateAsset extends LazyLogging {

  case class AssetInfo(
    bucket: String,
    key: String,
    size: Long,
    `type`: FileObjectType
  )
  object AssetInfo {
    implicit val encoder: Encoder[AssetInfo] = deriveEncoder[AssetInfo]
    implicit val decoder: Decoder[AssetInfo] = deriveDecoder[AssetInfo]
  }

  def write(
    packageId: Int,
    organizationId: Int,
    asset: AssetInfo
  )(implicit
    container: Container
  ): DBIO[(Organization, File)] =
    for {
      organization <- OrganizationsMapper.getOrganization(organizationId)

      files = new FilesMapper(organization)

      (name, extension) = utilities.splitFileName(asset.key)
      processingState = asset.`type` match {
        case FileObjectType.Source => FileProcessingState.Processed
        case FileObjectType.File => FileProcessingState.NotProcessable
        case FileObjectType.View => FileProcessingState.NotProcessable
      }
      fileType = utilities.getFileType(extension)
      result <- (files returning files) += File(
        packageId,
        name,
        fileType,
        asset.bucket,
        asset.key,
        asset.`type`,
        processingState,
        asset.size
      )
    } yield (organization, result)

  def create(
    packageId: Int,
    organizationId: Int,
    asset: AssetInfo
  )(implicit
    container: Container
  ): Future[File] =
    for {
      result <- container.db.run(
        write(packageId, organizationId, asset)(container).transactionally
      )
      (organization, file) = result
    } yield file

  // Note: default values required by scopt
  case class CLIConfig(
    asset: JavaFile = new JavaFile("asset.json"),
    packageId: Int = 1,
    organizationId: Int = 1
  ) {
    override def toString: String = s"""
    | package-id = $packageId
    | organization-id = $organizationId
    | asset-info = ${asset.toString}
    """
  }

  val parser = new OptionParser[CLIConfig]("") {
    head("create-asset")

    opt[JavaFile]("asset-info")
      .required()
      .valueName("<file>")
      .action((value, config) => config.copy(asset = value))
      .text("asset-info is a required file property")

    opt[Int]("package-id")
      .required()
      .valueName("<id>")
      .action((value, config) => config.copy(packageId = value))
      .text("package-id is a required file property")

    opt[Int]("organization-id")
      .required()
      .valueName("<id>")
      .action((value, config) => config.copy(organizationId = value))
      .text("organization-id is a required file property")
  }

  def decodeAssetInfo(file: JavaFile): Future[AssetInfo] = {
    val parsed = for {
      source <- Try { Source.fromFile(file) }.toEither
      decoded <- decode[AssetInfo](source.getLines.mkString(""))
      _ <- Try { source.close() }.toEither
    } yield decoded

    parsed match {
      case Left(exception) =>
        Future.failed(
          new Exception(
            s"invalid JSON for asset-info in ${file.toString}",
            exception
          )
        )
      case Right(asset) => Future.successful(asset)
    }
  }

  def parse(args: Array[String]): Future[CLIConfig] =
    parser.parse(args, new CLIConfig()) match {
      case None => Future.failed(new ScoptParsingFailure)
      case Some(config) => Future.successful(config)
    }

  def run(args: Array[String], getContainer: Future[Container]): Future[Unit] =
    for {
      config <- parse(args)
      asset <- decodeAssetInfo(config.asset)
      container <- getContainer
      _ <- create(config.packageId, config.organizationId, asset)(container)
    } yield ()

}
