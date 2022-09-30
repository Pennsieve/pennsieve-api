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

package com.pennsieve.publish

import com.dimafeng.testcontainers.GenericContainer
import com.pennsieve.test.{ DockerContainer, StackedDockerContainer }
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy
import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }
import org.mockserver.client.MockServerClient

//TODO Factor out the S3/Alpakka specific stuff
// and move this (and the relevant dependencies)
// to the appropriate place in core/test
// to make this available to mock any service.
object MockServerDockerContainer {
  val port: Int = 1080
  val accessKey: String = "access-key"
  val secretKey: String = "secret-key"
  val healthCheckPath = "/mockServer/healthCheck"
}

/**
  * The config override means this is useful for mocking S3 for
  * Akka's Alpkka S3 module. In that library all S3 access is down through static
  * methods, so we cannot swap in a mocked S3 client.
  */
final class MockServerDockerContainerImpl
    extends DockerContainer(
      dockerImage = s"mockserver/mockserver:5.14.0",
      exposedPorts = Seq(MockServerDockerContainer.port),
      env = Map(
        "MOCKSERVER_LIVENESS_HTTP_GET_PATH" -> MockServerDockerContainer.healthCheckPath
      ),
      waitStrategy = Some(
        new HttpWaitStrategy()
          .forPath(MockServerDockerContainer.healthCheckPath)
      )
    ) {

  def mappedPort(): Int = super.mappedPort(MockServerDockerContainer.port)
  val accessKey: String = MockServerDockerContainer.accessKey
  val secretKey: String = MockServerDockerContainer.secretKey
  def endpointUrl: String = s"http://${containerIpAddress}:${mappedPort()}"

  def apply(): GenericContainer = this

  override def config: Config =
    ConfigFactory
      .empty()
      .withValue(
        "alpakka.s3.endpoint-url",
        ConfigValueFactory.fromAnyRef(endpointUrl)
      )
      .withValue(
        "alpakka.s3.path-style-access",
        ConfigValueFactory.fromAnyRef(true)
      )
      .withValue(
        "alpakka.s3.aws.credentials.provider",
        ConfigValueFactory.fromAnyRef("static")
      )
      .withValue(
        "alpakka.s3.aws.credentials.access-key-id",
        ConfigValueFactory.fromAnyRef(accessKey)
      )
      .withValue(
        "alpakka.s3.aws.credentials.secret-access-key",
        ConfigValueFactory.fromAnyRef(secretKey)
      )
      .withValue(
        "alpakka.s3.aws.region.provider",
        ConfigValueFactory.fromAnyRef("static")
      )
      .withValue(
        "alpakka.s3.aws.region.default-region",
        ConfigValueFactory.fromAnyRef("us-east-1")
      )

  def mockServerClient: MockServerClient = {
    new MockServerClient(containerIpAddress, mappedPort())
  }

}

trait MockServerDockerContainer extends StackedDockerContainer {
  val mockServerContainer = new MockServerDockerContainerImpl

  override def stackedContainers =
    mockServerContainer :: super.stackedContainers
}
