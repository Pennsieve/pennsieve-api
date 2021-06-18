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

package com.pennsieve.uploads.consumer

import akka.actor.ActorSystem
import akka.stream.alpakka.sqs.SqsSourceSettings
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.regions.Region
import com.pennsieve.akka.consumer.{
  ConsumerUtilities,
  DeadLetterQueueProcessor,
  TriggerUtilities
}
import com.pennsieve.akka.http.{
  HealthCheck,
  HealthCheckService,
  RouteService,
  WebServer
}
import com.pennsieve.aws.queue.{ AWSSQSContainer, LocalSQSContainer }
import com.pennsieve.aws.s3.{ AWSS3Container, LocalS3Container }
import com.pennsieve.aws.sns.{ AWSSNSContainer, LocalSNSContainer }
import com.pennsieve.clients.{
  JobSchedulingServiceContainerImpl,
  LocalJobSchedulingServiceContainer,
  LocalUploadServiceContainer,
  UploadServiceContainerImpl
}
import com.pennsieve.core.utilities.DatabaseContainer
import com.pennsieve.service.utilities.ContextLogger
import com.pennsieve.traits.PostgresProfile.api.Database
import com.pennsieve.uploads.consumer.antivirus.ClamAVContainer
import net.ceedubs.ficus.Ficus._
import scala.concurrent.duration._

object Main extends App with WebServer {

  implicit val logger: ContextLogger = new ContextLogger()

  logger.noContext.info("launching uploads-consumer...")

  override val actorSystemName: String = "uploads-consumer"

  val environment: String = config.as[String]("environment")
  val isLocal: Boolean = environment.toLowerCase == "local"

  implicit val container: Container =
    if (isLocal) {
      new ConsumerContainer(config) with DatabaseContainer
      with LocalSQSContainer with LocalS3Container with ClamAVContainer
      with LocalSNSContainer with LocalJobSchedulingServiceContainer
      with LocalUploadServiceContainer
    } else {
      new ConsumerContainer(config) with DatabaseContainer with AWSSQSContainer
      with AWSS3Container with ClamAVContainer with AWSSNSContainer
      with JobSchedulingServiceContainerImpl with UploadServiceContainerImpl
    }

  val deadLetterQueueSqsSettings = SqsSourceSettings()
    .withWaitTime(10.seconds)
    .withMaxBufferSize(100)
    .withMaxBatchSize(10)

  implicit val db: Database = container.db

  implicit val sqsClient: SqsAsyncClient = container.sqs.client

  implicit val snsClient: SnsAsyncClient = container.snsClient

  val (deadLetterQueueKillSwitch, deadLetterQueueFuture) =
    DeadLetterQueueProcessor
      .deadLetterQueueConsume(
        container.alertQueue,
        container.alertTopic,
        deadLetterQueueSqsSettings,
        TriggerUtilities.failManifest
      )
      .run()

  // Launch Consumer
  logger.noContext.info(s"polling SQS queue ${container.queue}")
  val (consumerKillSwitch, completionFuture) =
    Processor.consume(container.queue, container.parallelism).run()

  ConsumerUtilities.handle(completionFuture)

  // when a termination signal (sigkill) is sent to the application process
  // this allows the consumer to gracefully shutdown the akka stream
  ConsumerUtilities.addShutdownHook(consumerKillSwitch)

  // serve health check status
  override val routeService: RouteService = new HealthCheckService(
    Map(
      "postgres" -> HealthCheck.postgresHealthCheck(container.db),
      "s3" -> HealthCheck.s3HealthCheck(
        container.s3,
        List(
          container.etlBucket,
          container.storageBucket,
          container.uploadsBucket
        )
      )
    )
  )
  startServer()
}
