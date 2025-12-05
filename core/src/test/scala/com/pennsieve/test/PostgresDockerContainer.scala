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

import com.pennsieve.core.utilities.PostgresDatabase
import com.dimafeng.testcontainers.GenericContainer
import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy
import java.time.Duration

final class PostgresDockerContainerImpl
    extends PostgresContainerImpl(
      dockerImage = "pennsieve/pennsievedb:V20251123185618"
    )

trait PostgresDockerContainer extends StackedDockerContainer {
  val postgresContainer = DockerContainers.postgresContainer

  override def stackedContainers = postgresContainer :: super.stackedContainers
}

final class PostgresSeedDockerContainerImpl
    extends PostgresContainerImpl(
      dockerImage = "pennsieve/pennsievedb:V20251123185618-seed"
    )

trait PostgresSeedDockerContainer extends StackedDockerContainer {
  val postgresContainer = DockerContainers.postgresSeedContainer

  override def stackedContainers = postgresContainer :: super.stackedContainers
}

object PostgresDockerContainer {
  val port: Int = 5432
  val user: String = "postgres"
  val password: String = "password"
  val dbName: String = "postgres"
}

class PostgresContainerImpl(dockerImage: String)
    extends DockerContainer(
      dockerImage = dockerImage,
      exposedPorts = Seq(PostgresDockerContainer.port),
      env = Map("POSTGRES_PASSWORD" -> PostgresDockerContainer.password),
      // Disable data durability to speed up tests. See
      // https://kubuszok.com/2018/speed-up-things-in-scalac-and-sbt/
      command = Seq(
        "-c",
        "fsync=off",
        "-c",
        "synchronous_commit=off",
        "-c",
        "full_page_writes=off"
      ),
      waitStrategy = Some(
        new LogMessageWaitStrategy()
          .withRegEx(".*database system is ready to accept connections.*\\s")
          .withStartupTimeout(Duration.ofMinutes(1))
      )
    ) {

  def database: PostgresDatabase = PostgresDatabase(
    host = containerIpAddress,
    port = mappedPort(),
    database = PostgresDockerContainer.dbName,
    user = PostgresDockerContainer.user,
    password = PostgresDockerContainer.password,
    useSSL = false
  )

  def mappedPort(): Int = super.mappedPort(PostgresDockerContainer.port)
  val user: String = PostgresDockerContainer.user
  val password: String = PostgresDockerContainer.password
  val dbName: String = PostgresDockerContainer.dbName
  def jdbcUrl(): String = database.jdbcURL

  def apply(): GenericContainer = this

  override def config: Config =
    ConfigFactory
      .empty()
      .withValue(
        "postgres.host",
        ConfigValueFactory.fromAnyRef(containerIpAddress)
      )
      .withValue("postgres.port", ConfigValueFactory.fromAnyRef(mappedPort()))
      .withValue("postgres.database", ConfigValueFactory.fromAnyRef(dbName))
      .withValue("postgres.user", ConfigValueFactory.fromAnyRef(user))
      .withValue("postgres.password", ConfigValueFactory.fromAnyRef(password))
      .withValue(
        "data.postgres.host",
        ConfigValueFactory.fromAnyRef(containerIpAddress)
      )
      .withValue(
        "data.postgres.port",
        ConfigValueFactory.fromAnyRef(mappedPort())
      )
      .withValue(
        "data.postgres.database",
        ConfigValueFactory.fromAnyRef(dbName)
      )
      .withValue("data.postgres.user", ConfigValueFactory.fromAnyRef(user))
      .withValue(
        "data.postgres.password",
        ConfigValueFactory.fromAnyRef(password)
      )
}
