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

package com.pennsieve.jobs

import com.pennsieve.aws.queue.SQSContainer
import com.pennsieve.utilities.{ Container => ConfigContainer }
import com.pennsieve.auth.middleware.Jwt
import com.pennsieve.messages._

import software.amazon.awssdk.services.sqs.model.{ Message => SQSMessage }

import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._

package object container {

  class JobContainer(val config: Config) extends ConfigContainer {
    val environment: String = config.as[String]("environment")
    val parallelism: Int = config.as[Int]("parallelism")

    val queue: String = config.as[String]("sqs.queue")

    val postgresHost: String = config.as[String]("postgres.host")
    val postgresUser: String = config.as[String]("postgres.user")
    val postgresPort: String = config.as[String]("postgres.port")
    val postgresPassword: String = config.as[String]("postgres.password")
    val postgresDb: String = config.as[String]("postgres.database")
    val postgresUrl: String =
      s"jdbc:postgresql://${postgresHost}:${postgresPort}/${postgresDb}?ssl=true&sslmode=verify-ca"
  }

  type Container = JobContainer with SQSContainer

  type MessageExceptionPair = (SQSMessage, JobException)
  type MessageJobPair = (SQSMessage, BackgroundJob)

  type JobResult = Either[JobException, Unit]
  type StreamContext = (SQSMessage, BackgroundJob, JobResult)

}
