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

import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.{ AWSStaticCredentialsProvider, BasicAWSCredentials }
import com.amazonaws.services.s3.{ AmazonS3, AmazonS3ClientBuilder }
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.dimafeng.testcontainers.GenericContainer
import org.scalatest.TestSuite
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy
import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }

object S3DockerContainer {
  val port: Int = 9000
  val accessKey: String = "access-key"
  val secretKey: String = "secret-key"
}

final class S3DockerContainerImpl
    extends DockerContainer(
      dockerImage = s"minio/minio:RELEASE.2019-04-23T23-50-36Z",
      exposedPorts = Seq(S3DockerContainer.port),
      env = Map(
        "MINIO_ACCESS_KEY" -> S3DockerContainer.accessKey,
        "MINIO_SECRET_KEY" -> S3DockerContainer.secretKey
      ),
      waitStrategy = Some(new HttpWaitStrategy().forPath("/minio/health/live")),
      command = Seq("server", "/tmp")
    ) {

  def mappedPort(): Int = super.mappedPort(S3DockerContainer.port)
  val accessKey: String = S3DockerContainer.accessKey
  val secretKey: String = S3DockerContainer.secretKey
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

  def s3Client: AmazonS3 = {
    val creds = new BasicAWSCredentials(accessKey, secretKey)
    val credsProvider = new AWSStaticCredentialsProvider(creds)
    val endpoint = new EndpointConfiguration(endpointUrl, "us-east-1")
    val clientConfig =
      new ClientConfiguration().withSignerOverride("AWSS3V4SignerType")
    AmazonS3ClientBuilder
      .standard()
      .withCredentials(credsProvider)
      .withEndpointConfiguration(endpoint)
      .withPathStyleAccessEnabled(true)
      .withClientConfiguration(clientConfig)
      .build()
  }
}

trait S3DockerContainer extends StackedDockerContainer {
  val s3Container = DockerContainers.s3Container

  override def stackedContainers = s3Container :: super.stackedContainers
}
