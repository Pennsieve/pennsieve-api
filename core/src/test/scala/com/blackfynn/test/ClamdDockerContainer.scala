package com.blackfynn.test
import java.time.Duration

import com.dimafeng.testcontainers.GenericContainer
import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }
import org.testcontainers.containers.wait.strategy.DockerHealthcheckWaitStrategy

object ClamdDockerContainer {
  val port: Int = 3310
  val waitStrategy = new DockerHealthcheckWaitStrategy()
    .withStartupTimeout(Duration.ofMinutes(5))
}

final class ClamdDockerContainerImpl
    extends DockerContainer(
      dockerImage = "pennsieve/clamd:latest",
      exposedPorts = Seq(ClamdDockerContainer.port),
      waitStrategy = Some(ClamdDockerContainer.waitStrategy)
    ) {

  def mappedPort(): Int = mappedPort(ClamdDockerContainer.port)

  def apply(): GenericContainer = this

  override def config: Config =
    super.config
      .withValue(
        "clamd.host",
        ConfigValueFactory.fromAnyRef(containerIpAddress)
      )
      .withValue("clamd.port", ConfigValueFactory.fromAnyRef(mappedPort))
}

trait ClamdDockerContainer extends StackedDockerContainer {
  val clamdContainer = DockerContainers.clamdContainer

  override def stackedContainers = clamdContainer :: super.stackedContainers
}
