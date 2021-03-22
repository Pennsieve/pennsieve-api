// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.jobs

import com.typesafe.config.ConfigFactory
import net.ceedubs.ficus.Ficus._

object Settings {
  val config = ConfigFactory.load()

  val modelServiceHost: String = config.as[String]("model_service.host")

  val modelServicePort: Int = config.as[Int]("model_service.port")

  val postgresHost: String = config.as[String]("postgres.host")

  val postgresUser: String = config.as[String]("postgres.user")

  val postgresPort: String = config.as[String]("postgres.port")

  val postgresPassword: String = config.as[String]("postgres.password")

  val postgresDb: String = config.as[String]("postgres.database")

  val postgresUrl: String =
    s"jdbc:postgresql://${postgresHost}:${postgresPort}/${postgresDb}?ssl=true&sslmode=verify-ca"
}
