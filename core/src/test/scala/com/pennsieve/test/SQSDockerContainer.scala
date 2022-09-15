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

package com.pennsieve.test

import java.time.Duration

import com.dimafeng.testcontainers.GenericContainer
import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }
import org.testcontainers.containers.wait.strategy.{
  HttpWaitStrategy,
  WaitStrategy
}

object SQSDockerContainer {
  val port: Int = 4576
  val region: String = "us-east-1"
  val waitStrategy: WaitStrategy =
    new HttpWaitStrategy()
      .forPort(port)
      .forPath("/")
      .forStatusCode(404)
      .withStartupTimeout(Duration.ofMinutes(5))
}

final class SQSDockerContainerImpl
    extends DockerContainer(
      dockerImage = "localstack/localstack:0.8.7",
      exposedPorts = Seq(SQSDockerContainer.port),
      env = Map(
        "AWS_ACCESS_KEY_ID" -> "test",
        "AWS_SECRET_ACCESS_KEY" -> "test",
        "SERVICES" -> "sqs",
        "DEFAULT_REGION" -> "us_east_1"
      ),
      waitStrategy = Some(SQSDockerContainer.waitStrategy)
    ) {
  val region: String = SQSDockerContainer.region
  def containerHost: String = s"${containerIpAddress}:${mappedPort()}"
  def mappedPort(): Int = mappedPort(SQSDockerContainer.port)
  def httpHost(): String = s"http://${containerHost}"
  def testQueueUrl(): String = s"http://${containerHost}/queue/test"
  def uploadsQueueUrl(): String = s"http://${containerHost}/queue/uploads"
  def notificationsQueueUrl(): String =
    s"http://${containerHost}/queue/notifications"

  def apply(): GenericContainer = this

  override def config: Config =
    super.config
      .withValue("sqs.host", ConfigValueFactory.fromAnyRef(httpHost()))
      .withValue("sqs.queue", ConfigValueFactory.fromAnyRef(testQueueUrl()))
      .withValue("sqs.region", ConfigValueFactory.fromAnyRef(region))
      .withValue(
        "sqs.notifications_queue",
        ConfigValueFactory.fromAnyRef(notificationsQueueUrl())
      )
      .withValue(
        "pennsieve.uploads.queue",
        ConfigValueFactory.fromAnyRef(uploadsQueueUrl())
      )
}

trait SQSDockerContainer extends StackedDockerContainer {
  val sqsContainer = DockerContainers.sqsContainer

  override def stackedContainers = sqsContainer :: super.stackedContainers
}
