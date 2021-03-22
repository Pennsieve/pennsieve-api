// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.etl.`data-cli`

import com.blackfynn.aws.ssm.{
  AWSSimpleSystemsManagementContainer,
  LocalSimpleSystemsManagerContainer,
  SimpleSystemsManagementContainer
}
import com.blackfynn.utilities.{ Container => ConfigContainer }

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

  def build(
    baseConfig: Config
  )(implicit
    executionContext: ExecutionContext
  ): Future[Config] = {

    val environment: String = baseConfig.as[String]("environment")
    val isLocal: Boolean = environment.toLowerCase == "local"

    val ssmContainer: SimpleSystemsManagementContainer =
      if (isLocal) {
        new { val config = baseConfig } with ConfigContainer
        with LocalSimpleSystemsManagerContainer
      } else {
        new { val config = baseConfig } with ConfigContainer
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
