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
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.GenericContainer.DockerImage
import com.typesafe.config.{ Config, ConfigFactory }
import org.testcontainers.containers.wait.strategy.WaitStrategy

abstract class DockerContainer(
  dockerImage: DockerImage,
  exposedPorts: Seq[Int] = Seq(),
  env: Map[String, String] = Map(),
  waitStrategy: Option[WaitStrategy] = None,
  command: Seq[String] = Seq(),
  tmpFsMapping: Map[String, String] = Map.empty
) extends GenericContainer(
      dockerImage = dockerImage,
      exposedPorts = exposedPorts,
      env = env,
      waitStrategy = waitStrategy,
      command = command,
      tmpFsMapping = tmpFsMapping
    ) {
  def mappedPort(): Int
  def config: Config = ConfigFactory.empty
}
