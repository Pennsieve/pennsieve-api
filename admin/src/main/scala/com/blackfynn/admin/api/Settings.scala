// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.admin.api

import net.ceedubs.ficus.Ficus._
import com.typesafe.config.{ Config, ConfigFactory }

object Settings {
  val config: Config = ConfigFactory.load()

  val session_timeout: Int = config.as[Int]("session_timeout")

  val environment: String = config.as[String]("environment")

  val isLocal: Boolean = environment.toLowerCase == "local"

  val newUserTokenTTL: Int = config.as[Int]("new_user_token_ttl")

  val s3Bucket: String = config.as[String]("s3.storage_bucket")
  val s3Region = config.as[String]("s3.region")
  val s3Host: String = config.as[String]("s3.host")

  val jwtKey = config.as[String]("pennsieve.jwt.key")

  val discoverHost = config.as[String]("pennsieve.discover_service.host")
}
