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

import com.pennsieve.etl.`data-cli`.container._
import com.pennsieve.etl.`data-cli`.exceptions._
import com.pennsieve.core.utilities.DatabaseContainer
import cats.data._
import cats.implicits._
import com.typesafe.config.{ Config, ConfigFactory }
import com.typesafe.scalalogging.LazyLogging
import net.ceedubs.ficus.Ficus._
import org.slf4j.MDC

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

object Main extends App with LazyLogging {

  def baseConfig: Config = ConfigFactory.load()

  def getContainer: Future[Container] =
    ConfigBuilder.build(baseConfig).map { config =>
      {
        new DataCLIContainer(config) with DatabaseContainer
      }
    }

  def usage(error: String) = s"""usage: etl-data <command> [parameters]
  |
  |commands:
  |  create-asset
  |  get-channel
  |  set-channel
  |  update-package-type
  |  set-package-properties
  |
  |etl-data: error: $error
  """.stripMargin

  try {
    sys.env.get("IMPORT_ID") match {
      case Some(importId) => MDC.put("import-id", importId)
      case None =>
        logger.warn("missing IMPORT_ID environment variable; used by logger")
    }

    args.toList match {
      case Nil => {
        println(usage("the following arguments are required: command"))

        System.exit(2)
      }

      case "create-asset" :: parameters => {
        logger.info(s"creating asset with inputs: ${parameters.mkString(" ")}")

        Await.result(
          CreateAsset.run(parameters.toArray, getContainer),
          Duration(baseConfig.as[Int]("timeouts.CreateAsset"), MINUTES)
        )

        System.exit(0)
      }

      case "set-channel" :: parameters => {
        logger.info(
          s"creating channel with inputs: ${parameters.mkString(" ")}"
        )

        Await.result(
          SetChannel.run(parameters.toArray, getContainer),
          Duration(baseConfig.as[Int]("timeouts.SetChannel"), MINUTES)
        )

        System.exit(0)
      }

      case "get-channels" :: parameters => {
        logger.info(
          s"retrieving channels with inputs: ${parameters.mkString(" ")}"
        )

        Await.result(
          GetChannels.run(parameters.toArray, getContainer),
          Duration(baseConfig.as[Int]("timeouts.GetChannels"), MINUTES)
        )

        System.exit(0)
      }

      case "update-package-type" :: parameters => {
        logger.info(
          s"updating package type with inputs: ${parameters.mkString(" ")}"
        )

        Await.result(
          UpdatePackageType.run(parameters.toArray, getContainer),
          Duration(baseConfig.as[Int]("timeouts.UpdatePackageType"), MINUTES)
        )

        System.exit(0)
      }

      case "set-package-properties" :: parameters => {
        logger.info(
          s"setting package properties with inputs: ${parameters.mkString(" ")}"
        )

        Await.result(
          SetPackageProperties.run(parameters.toArray, getContainer),
          Duration(baseConfig.as[Int]("timeouts.SetPackageProperties"), MINUTES)
        )

        System.exit(0)
      }

      case command => {
        println(usage(s"invalid command $command"))

        System.exit(2)
      }
    }
  } catch {
    case _: ScoptParsingFailure => System.exit(2)
    case exception: Exception => {
      logger.error("Operation failed", exception)
      System.exit(1)
    }
  }

}
