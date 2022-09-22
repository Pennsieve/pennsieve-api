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

import com.pennsieve.aws.ssm.{
  AWSSimpleSystemsManagementContainer,
  LocalSimpleSystemsManagerContainer,
  SimpleSystemsManagementContainer
}
import com.pennsieve.utilities.{ Container => ConfigContainer }

import cats.data._
import cats.implicits._
import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._

import scala.concurrent.{ ExecutionContext, Future }

trait ConfigBuilderTrait {
  def build(
    baseConfig: Config
  )(implicit
    executionContext: ExecutionContext
  ): Future[Config]
}

object ConfigBuilder extends ConfigBuilderTrait {

  // Map from SimpleSystemsManagement parameters to our Typesafe config keys
  def getSSMConfig(environment: String): Map[String, String] = Map(
    s"${environment}-pennsieve-postgres-db" -> "postgres.database",
    s"${environment}-pennsieve-postgres-host" -> "postgres.host",
    s"${environment}-pennsieve-postgres-password" -> "postgres.password",
    s"${environment}-pennsieve-postgres-port" -> "postgres.port",
    s"${environment}-pennsieve-postgres-user" -> "postgres.user"
  )

  // Early initializers deprecated in Scala 2.13 and removed in Scala 3. This class
  // can be replaced by giving com.pennsieve.utilities.Container a trait parameter once
  // we move to Scala 3. https://docs.scala-lang.org/scala3/guides/migration/incompat-dropped-features.html#early-initializer
  abstract class AbstractConfigContainer(val config: Config)
      extends ConfigContainer

  def build(
    baseConfig: Config
  )(implicit
    executionContext: ExecutionContext
  ): Future[Config] = {

    val environment: String = baseConfig.as[String]("environment")
    val isLocal: Boolean = environment.toLowerCase == "local"

    val ssmContainer: SimpleSystemsManagementContainer =
      if (isLocal) {
        new AbstractConfigContainer(baseConfig)
        with LocalSimpleSystemsManagerContainer
      } else {
        new AbstractConfigContainer(baseConfig)
        with AWSSimpleSystemsManagementContainer
      }

    val ssmConfig = getSSMConfig(environment)

    ssmContainer.ssm
      .getParametersAsConfig(ssmConfig, withDecryption = true)
      .map { config =>
        config.withFallback(baseConfig)
      }
  }

}
