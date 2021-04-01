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

package com.pennsieve.admin.api

import net.ceedubs.ficus.Ficus._
import com.typesafe.config.{ Config, ConfigFactory }

object Settings {
  val config: Config = ConfigFactory.load()

  val environment: String = config.as[String]("environment")

  val isLocal: Boolean = environment.toLowerCase == "local"

  val newUserTokenTTL: Int = config.as[Int]("new_user_token_ttl")

  val s3Bucket: String = config.as[String]("s3.storage_bucket")
  val s3Region = config.as[String]("s3.region")
  val s3Host: String = config.as[String]("s3.host")

  val jwtKey = config.as[String]("pennsieve.jwt.key")

  val discoverHost = config.as[String]("pennsieve.discover_service.host")
}
