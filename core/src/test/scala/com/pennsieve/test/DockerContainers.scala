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

/**
  * Shared singleton Docker containers.
  *
  * Containers are cached on this object so that the same container can be used
  * across multiple test suites by the PersistantDockerContainers trait.
  */
object DockerContainers {
  val postgresContainer: PostgresContainerImpl =
    new PostgresDockerContainerImpl

  val postgresSeedContainer: PostgresContainerImpl =
    new PostgresSeedDockerContainerImpl

  val sqsContainer: SQSDockerContainerImpl = new SQSDockerContainerImpl

  val s3Container: S3DockerContainerImpl = new S3DockerContainerImpl

  val clamdContainer = new ClamdDockerContainerImpl
}
