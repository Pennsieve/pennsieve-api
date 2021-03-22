package com.blackfynn.test
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
