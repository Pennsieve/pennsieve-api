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
