// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.etl.`data-cli`

import com.blackfynn.db.{ DimensionsMapper, OrganizationsMapper }
import com.blackfynn.etl.`data-cli`.container._
import com.blackfynn.etl.`data-cli`.exceptions._
import com.blackfynn.models.{ Dimension, DimensionAssignment, Organization }
import com.blackfynn.traits.PostgresProfile.api._
import io.circe.parser.decode
import io.circe.syntax._
import io.circe.java8.time._
import cats.implicits._
import java.io.{ FileWriter, File => JavaFile }

import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.{ Decoder, Encoder }
import net.ceedubs.ficus.Ficus._

import scala.io.Source
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try
import scopt.OptionParser

object SetDimension {

  case class DimensionInfo(
    name: String,
    length: Long,
    resolution: Option[Double],
    unit: Option[String],
    assignment: DimensionAssignment,
    id: Option[Int] = None
  )
  object DimensionInfo {
    implicit val encoder: Encoder[DimensionInfo] = deriveEncoder[DimensionInfo]
    implicit val decoder: Decoder[DimensionInfo] = deriveDecoder[DimensionInfo]
  }

  def create(
    packageId: Int,
    organizationId: Int,
    data: DimensionInfo
  )(implicit
    container: Container
  ): DBIO[Dimension] = {
    // these default values are specific and set on purpose
    val dimension = new Dimension(
      packageId,
      data.name.trim,
      data.length,
      data.resolution,
      data.unit,
      data.assignment
    )

    for {
      organization <- OrganizationsMapper.getOrganization(organizationId)
      dimensions = new DimensionsMapper(organization)
      result <- dimensions returning dimensions += dimension
    } yield result
  }

  def update(
    packageId: Int,
    organizationId: Int,
    dimensionId: Int,
    data: DimensionInfo
  )(implicit
    container: Container
  ): DBIO[Dimension] = {

    for {
      organization <- OrganizationsMapper.getOrganization(organizationId)
      dimensions = new DimensionsMapper(organization)
      original <- dimensions.getDimension(dimensionId)

      updated = original.copy(
        name = data.name.trim,
        length = data.length,
        resolution =
          if (data.resolution.isDefined) data.resolution
          else original.resolution,
        unit = if (data.unit.isDefined) data.unit else original.unit,
        assignment = data.assignment
      )

      _ <- dimensions.get(original.id).update(updated)
    } yield updated
  }

  def set(
    packageId: Int,
    organizationId: Int,
    data: DimensionInfo,
    output: JavaFile
  )(implicit
    container: Container
  ): Future[Dimension] = {
    val query: DBIO[Dimension] = data.id match {
      case Some(dimensionId) =>
        update(packageId, organizationId, dimensionId, data)(container)
      case None => create(packageId, organizationId, data)(container)
    }

    for {
      dimension <- container.db.run(query.transactionally)
      _ <- Future { FileOutputWriter.writeJson(dimension.asJson, output) }
    } yield dimension
  }

  // Note: default values required by scopt
  case class CLIConfig(
    dimension: JavaFile = new JavaFile("dimension.json"),
    output: JavaFile = new JavaFile("dimension.json"),
    packageId: Int = 1,
    organizationId: Int = 1
  ) {
    override def toString: String = s"""
    | package-id = $packageId
    | organization-id = $organizationId
    | dimension-info = ${dimension.toString}
    | output = ${output.toString}
    """
  }

  val parser = new OptionParser[CLIConfig]("") {
    head("set-dimension")

    opt[JavaFile]("dimension-info")
      .required()
      .valueName("<file>")
      .action((value, config) => config.copy(dimension = value))
      .text("dimension-info is a required file property")

    opt[JavaFile]('o', "output-file")
      .required()
      .valueName("<file>")
      .action((value, config) => config.copy(output = value))
      .text("output-file is a required file property")

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

  def decodeDimensionInfo(file: JavaFile): Future[DimensionInfo] = {
    val parsed = for {
      source <- Try { Source.fromFile(file) }.toEither
      decoded <- decode[DimensionInfo](source.getLines.mkString(""))
      _ <- Try { source.close() }.toEither
    } yield decoded

    parsed match {
      case Left(exception) =>
        Future.failed(
          new Exception(
            s"invalid JSON for dimension-info in ${file.toString}",
            exception
          )
        )
      case Right(dimension) => Future.successful(dimension)
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
      dimension <- decodeDimensionInfo(config.dimension)
      container <- getContainer
      _ <- set(
        config.packageId,
        config.organizationId,
        dimension,
        config.output
      )(container)
    } yield ()

}
