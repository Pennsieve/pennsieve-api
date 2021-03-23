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

package com.pennsieve.uploads

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.pennsieve.akka.consumer.AlertConfig
import com.pennsieve.aws.queue.{ SQSContainer, SQSDeduplicationContainer }
import com.pennsieve.aws.s3.S3Container
import com.pennsieve.aws.sns.SNSContainer
import com.pennsieve.core.utilities.{
  ContextLoggingContainer,
  DatabaseContainer,
  RedisContainer
}
import com.pennsieve.clients.{
  JobSchedulingServiceContainer,
  UploadServiceContainer
}
import com.pennsieve.uploads.consumer.antivirus.ClamAVContainer
import com.pennsieve.utilities.{ Container => ConfigContainer }
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
