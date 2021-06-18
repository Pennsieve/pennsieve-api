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

import akka.actor.ActorSystem
import com.pennsieve.aws.queue.{ AWSSQSContainer, LocalSQSContainer }
import com.pennsieve.jobs.container._
import com.pennsieve.jobs.types._
import com.pennsieve.service.utilities.ContextLogger
import com.typesafe.config.{ Config, ConfigFactory }
import net.ceedubs.ficus.Ficus._
import scalikejdbc.config.DBsWithEnv

import scala.concurrent.ExecutionContext

object Main extends App {

  implicit val actorSystem = ActorSystem("jobs")
  implicit val ec: ExecutionContext = actorSystem.dispatcher
  implicit val log: ContextLogger = new ContextLogger()

  val config: Config = ConfigFactory.load()

  val environment = config.as[String]("environment")
  val isLocal = environment.toLowerCase == "local"

  implicit val container: Container =
    if (isLocal) {
      new JobContainer(config) with LocalSQSContainer
    } else {
      new JobContainer(config) with AWSSQSContainer
    }

  val deletePackageJob: DeleteJob = DeleteJob()
  val cacheJob = StorageCachePopulationJob(config)
  val logChangeEventRunner = DatasetChangelogEvent(config)

  val processor: ProcessJob =
    ProcessJob(
      deleteRunner = deletePackageJob,
      cacheRunner = cacheJob,
      logChangeEventRunner = logChangeEventRunner
    )

  DBsWithEnv("postgres").setupAll()

  val sqsProcessor =
    Processor.consume(processor, container.queue, container.parallelism).run()
  log.noContext.info("listening for jobs from SQS")

  // allow stream to finish processing after SIGTERM is received
  sys.addShutdownHook {
    log.noContext.info("Shutting down stream")
    sqsProcessor.shutdown()
  }
}
