package com.blackfynn.jobs

import com.blackfynn.aws.queue.{ SQSContainer, SQSDeduplicationContainer }
import com.blackfynn.core.utilities.RedisContainer
import com.blackfynn.utilities.{ Container => ConfigContainer }
import com.blackfynn.auth.middleware.Jwt
import com.blackfynn.messages._

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

  type Container = JobContainer
    with RedisContainer
    with SQSContainer
    with SQSDeduplicationContainer

  type MessageExceptionPair = (SQSMessage, JobException)
  type MessageJobPair = (SQSMessage, BackgroundJob)

  type JobResult = Either[JobException, Unit]
  type StreamContext = (SQSMessage, BackgroundJob, JobResult)

}
