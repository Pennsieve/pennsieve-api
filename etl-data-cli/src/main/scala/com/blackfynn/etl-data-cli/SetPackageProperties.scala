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

package com.pennsieve.etl.`data-cli`

import com.pennsieve.db.{ OrganizationsMapper, PackagesMapper }
import com.pennsieve.etl.`data-cli`.container._
import com.pennsieve.etl.`data-cli`.exceptions._
import com.pennsieve.models.{ ModelProperty, Organization, Package }
import com.pennsieve.traits.PostgresProfile.api._
import io.circe.parser.decode
import io.circe.syntax._
import io.circe.java8.time._
import cats.implicits._
import java.io.{ FileWriter, File => JavaFile }

import net.ceedubs.ficus.Ficus._

import scala.io.Source
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try
import scopt.OptionParser

object SetPackageProperties {

  def update(
    packageId: Int,
    organizationId: Int,
    properties: List[ModelProperty]
  )(implicit
    container: Container
  ): DBIO[Package] = {
    for {
      organization <- OrganizationsMapper.getOrganization(organizationId)
      packages = new PackagesMapper(organization)
      original <- packages.getPackage(packageId)

      updated = original.copy(
        attributes = ModelProperty.merge(original.attributes, properties)
      )

      _ <- packages.get(original.id).update(updated)
    } yield updated
  }

  def set(
    packageId: Int,
    organizationId: Int,
    properties: List[ModelProperty]
  )(implicit
    container: Container
  ): Future[Package] = {
    val query: DBIO[Package] =
      update(packageId, organizationId, properties)(container)

    container.db.run(query.transactionally)
  }

  case class CLIConfig(
    properties: JavaFile = new JavaFile("metadata.json"),
    packageId: Int = 1,
    organizationId: Int = 1
  ) {
    override def toString: String = s"""
    | package-id = $packageId
    | organization-id = $organizationId
    | property-info = ${properties.toString}
    """
  }

  val parser = new OptionParser[CLIConfig]("") {
    head("set-package-properties")

    opt[JavaFile]("property-info")
      .required()
      .valueName("<file>")
      .action((value, config) => config.copy(properties = value))
      .text("property-info is required")

    opt[Int]("package-id")
      .required()
      .valueName("<id>")
      .action((value, config) => config.copy(packageId = value))
      .text("package-id is required")

    opt[Int]("organization-id")
      .required()
      .valueName("<id>")
      .action((value, config) => config.copy(organizationId = value))
      .text("organization-id is required")
  }

  def decodeProperties(file: JavaFile): Future[List[ModelProperty]] = {
    val parsed = for {
      source <- Try { Source.fromFile(file) }.toEither
      decoded <- decode[List[ModelProperty]](source.getLines.mkString(""))
      _ <- Try { source.close() }.toEither
    } yield decoded

    parsed match {
      case Left(exception) =>
        Future.failed(
          new Exception(
            s"invalid JSON for set-package-properties in ${file.toString}",
            exception
          )
        )
      case Right(properties) => Future.successful(properties)
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
      properties <- decodeProperties(config.properties)
      container <- getContainer
      _ <- set(config.packageId, config.organizationId, properties)(container)
    } yield ()
}
