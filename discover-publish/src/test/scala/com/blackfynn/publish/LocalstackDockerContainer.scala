package com.blackfynn.publish

import java.time.Duration

import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.{ DefaultAWSCredentialsProviderChain }
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.s3.{ AmazonS3, AmazonS3ClientBuilder }
import com.blackfynn.test._
import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy

object LocalstackDockerContainer {
  val s3ContainerPort: Int = 4572
  val accessKey: String = "access-key"
  val secretKey: String = "secret-key"
  val region: String = "us-east-1"
}

final class LocalstackDockerContainerImpl
    extends DockerContainer(
      dockerImage = "localstack/localstack:0.8.7",
      exposedPorts = Seq(LocalstackDockerContainer.s3ContainerPort),
      env = Map(
        "AWS_ACCESS_KEY_ID" -> "test",
        "AWS_SECRET_ACCESS_KEY" -> "test",
        "SERVICES" -> "s3",
        "DEFAULT_REGION" -> LocalstackDockerContainer.region
      ),
      waitStrategy = Some(
        new HttpWaitStrategy()
          .forPort(LocalstackDockerContainer.s3ContainerPort)
          .forPath("/")
          .forStatusCode(200)
          .withStartupTimeout(Duration.ofMinutes(1))
      )
    ) {

  val region: String = LocalstackDockerContainer.region

  def mappedPort(): Int = throw new Exception("container has many ports")
  val accessKey: String = S3DockerContainer.accessKey
  val secretKey: String = S3DockerContainer.secretKey
  def endpointUrl: String =
    s"http://${containerIpAddress}:${mappedPort(LocalstackDockerContainer.s3ContainerPort)}"

  override def config: Config = {
    ConfigFactory
      .empty()
      .withValue(
        "s3.region",
        ConfigValueFactory.fromAnyRef(LocalstackDockerContainer.region)
      )
      .withValue("s3.host", ConfigValueFactory.fromAnyRef(endpointUrl))
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
  }

  def s3Client: AmazonS3 = {
    val endpoint = new EndpointConfiguration(endpointUrl, "us-east-1")
    val clientConfig =
      new ClientConfiguration().withSignerOverride("AWSS3V4SignerType")
    AmazonS3ClientBuilder
      .standard()
      .withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
      .withEndpointConfiguration(endpoint)
      .withPathStyleAccessEnabled(true)
      .withClientConfiguration(clientConfig)
      .build()
  }

}

trait LocalstackDockerContainer extends StackedDockerContainer {
  val localstackContainer = new LocalstackDockerContainerImpl

  override def stackedContainers =
    localstackContainer :: super.stackedContainers
}
