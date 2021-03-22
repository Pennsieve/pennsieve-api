// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.web
import com.amazonaws.regions.Regions
import software.amazon.awssdk.regions.Region
import com.blackfynn.aws.email.Email
import com.typesafe.config.{ Config, ConfigFactory }
import net.ceedubs.ficus.Ficus._

import scala.collection.JavaConverters._

object Settings {

  val config: Config = ConfigFactory.load()

  val environment: String = config.as[String]("environment")

  val isProduction: Boolean = environment == "PRODUCTION"

  val isLocal: Boolean = environment == "LOCAL"

  val isDevelopment: Boolean = environment == "DEVELOPMENT"

  val respondWithStackTrace: Boolean = (isDevelopment || isLocal)

  val sessionTimeout: Int = config.as[Int]("pennsieve.session_timeout")

  val s3_upload_bucketName: String =
    config.as[String]("pennsieve.s3.upload_bucket_name")

  val s3_host: String = config.as[String]("pennsieve.s3.host")

  // val s3_access_key: String = config.as[String]("pennsieve.s3.access_key")

  // val s3_secret_access_key: String =
  //   config.as[String]("pennsieve.s3.secret_access_key")

  val uploader_role: String = config.as[String]("pennsieve.s3.uploader_role")

  val url_time_limit: Int = config.as[Int]("pennsieve.url_time_limit")
  val bitly_url_time_limit: Int =
    config.as[Int]("bitly.url_time_limit")

  val password_reset_time_limit: Int =
    config.as[Int]("pennsieve.password.reset_time_limit")

  val support_email: Email = Email(config.as[String]("email.support_email"))

  val region = Regions.US_EAST_1
  val regionV2 = Region.US_EAST_1

  val authyKey: String = config.as[String]("pennsieve.authy.authyKey")

  val authyApiUrl: String = config.as[String]("pennsieve.authy.apiUrl")

  val password_validation_error_message: String =
    config.as[String]("pennsieve.password.validation_error_message")

  val newUserTokenTTL: Int = config.as[Int]("new_user_token_ttl")

  val notificationHost = config.as[String]("pennsieve.notifications.host")

  val notificationPort = config.as[Int]("pennsieve.notifications.port")

  val modelServiceHost = config.as[String]("pennsieve.model_service.host")

  val modelServicePort = config.as[Int]("pennsieve.model_service.port")

  val analyticsHost = config.as[String]("pennsieve.analytics.host")
  val analyticsQueueSize = config.as[Int]("pennsieve.analytics.queue_size")
  val analyticsRateLimit = config.as[Int]("pennsieve.analytics.rate_limit")

  val colors: Map[String, String] = config
    .getObject("pennsieve.colors")
    .asScala
    .map { case (k, v) => (k.toString, v.unwrapped().asInstanceOf[String]) }
    .toMap

  val userColors: Set[String] =
    config.as[String]("pennsieve.userColors").split(",").toSet

  val jwtKey = config.as[String]("pennsieve.jwt.key")
}
