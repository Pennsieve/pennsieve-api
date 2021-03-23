package com.pennsieve.test

/**
  * Shared singleton Docker containers.
  *
  * Containers are cached on this object so that the same container can be used
  * across multiple test suites by the PersistantDockerContainers trait.
  */
object DockerContainers {
  val redisContainer: RedisDockerContainerImpl = new RedisDockerContainerImpl

  val postgresContainer: PostgresContainerImpl =
    new PostgresDockerContainerImpl

  val postgresSeedContainer: PostgresContainerImpl =
    new PostgresSeedDockerContainerImpl

  val sqsContainer: SQSDockerContainerImpl = new SQSDockerContainerImpl

  val s3Container: S3DockerContainerImpl = new S3DockerContainerImpl

  val clamdContainer = new ClamdDockerContainerImpl
}
