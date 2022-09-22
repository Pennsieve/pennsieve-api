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

import com.pennsieve.db.{ ChannelsMapper, OrganizationsMapper, PackagesMapper }
import com.pennsieve.etl.`data-cli`.container._
import com.pennsieve.etl.`data-cli`.exceptions._
import com.pennsieve.models.Organization
import com.pennsieve.traits.PostgresProfile.api._
import io.circe.parser.decode
import io.circe.syntax._
import cats.implicits._
import java.io.{ FileWriter, File => JavaFile }

import com.pennsieve.models.Channel
import net.ceedubs.ficus.Ficus._

import scala.io.Source
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try
import scopt.OptionParser

object GetChannels {

  def query(
    packageId: Int,
    organizationId: Int
  )(implicit
    container: Container
  ): DBIO[Seq[Channel]] = {
    for {
      organization <- OrganizationsMapper.getOrganization(organizationId)
      channels = new ChannelsMapper(organization)
      result <- channels.getByPackageId(packageId).result
    } yield result
  }

  def get(
    packageId: Int,
    organizationId: Int,
    output: JavaFile
  )(implicit
    container: Container
  ): Future[List[Channel]] =
    for {
      channels <- container.db.run(
        query(packageId, organizationId)(container).transactionally
      )
      _ <- Future { FileOutputWriter.writeJson(channels.asJson, output) }
    } yield channels.toList

  // Note: default values required by scopt
  case class CLIConfig(
    output: JavaFile = new JavaFile("channel.json"),
    packageId: Int = 1,
    organizationId: Int = 1
  ) {
    override def toString: String = s"""
    | package-id = $packageId
    | organization-id = $organizationId
    | output = ${output.toString}
    """
  }

  val parser = new OptionParser[CLIConfig]("") {
    head("get-channels")

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

  def parse(args: Array[String]): Future[CLIConfig] =
    parser.parse(args, new CLIConfig()) match {
      case None => Future.failed(new ScoptParsingFailure)
      case Some(config) => Future.successful(config)
    }

  def run(args: Array[String], getContainer: Future[Container]): Future[Unit] =
    for {
      config <- parse(args)
      container <- getContainer
      _ <- get(config.packageId, config.organizationId, config.output)(
        container
      )
    } yield ()

}
