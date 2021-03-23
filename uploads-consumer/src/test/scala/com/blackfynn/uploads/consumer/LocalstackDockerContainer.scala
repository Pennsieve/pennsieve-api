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

import java.security.cert
import java.time.Duration

import com.pennsieve.test._
import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }
import javax.net.ssl._
import javax.security.cert.X509Certificate
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy

object TrustAll extends X509TrustManager {
  val getAcceptedIssuers: Null = null

  def checkClientTrusted(
    x509Certificates: Array[X509Certificate],
    s: String
  ): Unit = {}

  def checkServerTrusted(
    x509Certificates: Array[X509Certificate],
    s: String
  ): Unit = {}

  override def checkClientTrusted(
    x509Certificates: Array[cert.X509Certificate],
    s: String
  ): Unit = {}

  override def checkServerTrusted(
    x509Certificates: Array[cert.X509Certificate],
    s: String
  ): Unit = {}
}

object VerifiesAllHostNames extends HostnameVerifier {
  def verify(s: String, sslSession: SSLSession) = true
}

object LocalstackDockerContainer {
  val s3ContainerPort: Int = 4572
  val sqsContainerPort: Int = 4576
  val snsContainerPort: Int = 4575

  val region: String = "us-east-1"
}

final class LocalstackDockerContainerImpl
    extends DockerContainer(
      dockerImage = "localstack/localstack:0.8.7",
      exposedPorts = Seq(
        LocalstackDockerContainer.s3ContainerPort,
        LocalstackDockerContainer.sqsContainerPort,
        LocalstackDockerContainer.snsContainerPort
      ),
      env = Map(
        "AWS_ACCESS_KEY_ID" -> "test",
        "AWS_SECRET_ACCESS_KEY" -> "test",
        "SERVICES" -> "s3,sqs,sns",
        "DEFAULT_REGION" -> LocalstackDockerContainer.region,
        "USE_SSL" -> "true"
      ),
      waitStrategy = Some(
        new HttpWaitStrategy()
          .forPort(LocalstackDockerContainer.sqsContainerPort)
          .forPath("/")
          .usingTls()
          .forStatusCode(404)
          .withStartupTimeout(Duration.ofMinutes(5))
      )
    ) {

  // disable ssl checking for javax connections so we can healthcheck the localstack container
  val sslContext: SSLContext = SSLContext.getInstance("SSL")
  sslContext.init(null, Array(TrustAll), new java.security.SecureRandom())
  HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory)
  HttpsURLConnection.setDefaultHostnameVerifier(VerifiesAllHostNames)

  val deadLetterQueueName: String = "test-etl-dead-letter-queue"
  val alertTopicName: String = "test-alert"

  val region: String = LocalstackDockerContainer.region

  def mappedPort(): Int = throw new Exception("container has many ports")

  override def config: Config = {
    val localstackHost: String = "https://" + containerIpAddress

    val s3Port: Int = mappedPort(LocalstackDockerContainer.s3ContainerPort)
    val sqsPort: Int = mappedPort(LocalstackDockerContainer.sqsContainerPort)
    val snsPort: Int = mappedPort(LocalstackDockerContainer.snsContainerPort)

    ConfigFactory
      .empty()
      .withValue(
        "s3.region",
        ConfigValueFactory.fromAnyRef(LocalstackDockerContainer.region)
      )
      .withValue(
        "sqs.region",
        ConfigValueFactory.fromAnyRef(LocalstackDockerContainer.region)
      )
      .withValue(
        "s3.host",
        ConfigValueFactory.fromAnyRef(s"$localstackHost:$s3Port")
      )
      .withValue(
        "sqs.host",
        ConfigValueFactory.fromAnyRef(s"$localstackHost:$sqsPort")
      )
      .withValue(
        "sns.host",
        ConfigValueFactory.fromAnyRef(s"http://$localstackHost:$snsPort")
      )
      .withValue(
        "alert.sqsQueue",
        ConfigValueFactory.fromAnyRef(s"queue/$deadLetterQueueName")
      )
      .withValue(
        "alert.snsTopic",
        ConfigValueFactory.fromAnyRef(s"$alertTopicName")
      )
  }
}

trait LocalstackDockerContainer extends StackedDockerContainer {
  val localstackContainer = new LocalstackDockerContainerImpl

  override def stackedContainers =
    localstackContainer :: super.stackedContainers
}
