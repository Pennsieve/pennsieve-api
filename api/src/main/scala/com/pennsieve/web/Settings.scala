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

package com.pennsieve.web
import com.amazonaws.regions.Regions
import software.amazon.awssdk.regions.Region
import com.pennsieve.aws.email.Email
import com.typesafe.config.{ Config, ConfigFactory }
import net.ceedubs.ficus.Ficus._

import scala.jdk.CollectionConverters._

object Settings {

  val config: Config = ConfigFactory.load()

  val environment: String = config.as[String]("environment")

  val isProduction: Boolean = environment == "PRODUCTION"

  val isLocal: Boolean = environment == "LOCAL"

  val isDevelopment: Boolean = environment == "DEVELOPMENT"

  val respondWithStackTrace: Boolean = (isDevelopment || isLocal)

  val s3_upload_bucketName: String =
    config.as[String]("pennsieve.s3.upload_bucket_name")

  val s3_host: String = config.as[String]("pennsieve.s3.host")

  val uploader_role: String = config.as[String]("pennsieve.s3.uploader_role")

  val url_time_limit: Int = config.as[Int]("pennsieve.url_time_limit")
  val bitly_url_time_limit: Int =
    config.as[Int]("bitly.url_time_limit")

  val support_email: Email = Email(config.as[String]("email.support_email"))

  val region = Regions.US_EAST_1
  val regionV2 = Region.US_EAST_1

  val newUserTokenTTL: Int = config.as[Int]("new_user_token_ttl")

  val notificationHost = config.as[String]("pennsieve.notifications.host")

  val notificationPort = config.as[Int]("pennsieve.notifications.port")

  val modelServiceHost = config.as[String]("pennsieve.model_service.host")

  val modelServicePort = config.as[Int]("pennsieve.model_service.port")

  val analyticsHost = config.as[String]("pennsieve.analytics.host")
  val analyticsQueueSize = config.as[Int]("pennsieve.analytics.queue_size")
  val analyticsRateLimit = config.as[Int]("pennsieve.analytics.rate_limit")

  val appHost = config.as[String]("pennsieve.app_host")

  val colors: Map[String, String] = config
    .getObject("pennsieve.colors")
    .asScala
    .map { case (k, v) => (k.toString, v.unwrapped().asInstanceOf[String]) }
    .toMap

  val userColors: Set[String] =
    config.as[String]("pennsieve.userColors").split(",").toSet

  val jwtKey = config.as[String]("pennsieve.jwt.key")
}
