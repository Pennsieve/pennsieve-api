// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.uploads

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.blackfynn.akka.consumer.AlertConfig
import com.blackfynn.aws.queue.{ SQSContainer, SQSDeduplicationContainer }
import com.blackfynn.aws.s3.S3Container
import com.blackfynn.aws.sns.SNSContainer
import com.blackfynn.core.utilities.{
  ContextLoggingContainer,
  DatabaseContainer,
  RedisContainer
}
import com.blackfynn.clients.{
  JobSchedulingServiceContainer,
  UploadServiceContainer
}
import com.blackfynn.uploads.consumer.antivirus.ClamAVContainer
import com.blackfynn.utilities.{ Container => ConfigContainer }
import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._

import scala.concurrent.ExecutionContext

package object consumer {

  class ConsumerContainer(
    val config: Config
  )(implicit
    val ec: ExecutionContext,
    val system: ActorSystem,
    _materializer: ActorMaterializer
  ) extends ConfigContainer
      with AlertConfig {

    // Here to make tests work
    lazy val materializer: ActorMaterializer = _materializer

    val environment: String = config.as[String]("environment")
    val parallelism: Int = config.as[Int]("parallelism")

    val queue: String = config.as[String]("sqs.queue")
    val notificationsQueue: String = config.as[String]("notifications.queue")

    val etlBucket: String = config.as[String]("s3.buckets.etl")
    val storageBucket: String = config.as[String]("s3.buckets.storage")
    val uploadsBucket: String = config.as[String]("s3.buckets.uploads")

    val jwtKey: String = config.as[String]("jwt.key")
    lazy val jobSchedulingServiceHost: String =
      config.as[String]("job_scheduling_service.host")
    lazy val jobSchedulingServiceQueueSize: Int =
      config.as[Int]("job_scheduling_service.queue_size")
    lazy val jobSchedulingServiceRateLimit: Int =
      config.as[Int]("job_scheduling_service.rate_limit")

    lazy val uploadServiceHost: String =
      config.as[String]("upload_service.host")
  }

  type Container = ConsumerContainer
    with DatabaseContainer
    with RedisContainer
    with SQSContainer
    with S3Container
    with SQSDeduplicationContainer
    with ClamAVContainer
    with SNSContainer
    with JobSchedulingServiceContainer
    with UploadServiceContainer
}
