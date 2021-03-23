package com.pennsieve.test

import com.dimafeng.testcontainers.GenericContainer
import com.typesafe.config.{ Config, ConfigValueFactory }

object RedisDockerContainer {
  val port: Int = 6379
}

final class RedisDockerContainerImpl
    extends DockerContainer(
      dockerImage = "redis:3",
      exposedPorts = Seq(RedisDockerContainer.port)
    ) {
  def mappedPort(): Int = mappedPort(RedisDockerContainer.port)

  def apply(): GenericContainer = this

  override def config: Config =
    super.config
      .withValue(
        "redis.host",
        ConfigValueFactory.fromAnyRef(containerIpAddress)
      )
      .withValue("redis.port", ConfigValueFactory.fromAnyRef(mappedPort))
      .withValue("redis.use_ssl", ConfigValueFactory.fromAnyRef(false))
      .withValue("redis.auth_token", ConfigValueFactory.fromAnyRef(""))
      .withValue("redis.max_connections", ConfigValueFactory.fromAnyRef(128))
}

trait RedisDockerContainer extends StackedDockerContainer {
  val redisContainer = DockerContainers.redisContainer

  override def stackedContainers = redisContainer :: super.stackedContainers
}
