// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.jobs

import akka.actor.ActorSystem
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings, Supervision }
import com.pennsieve.aws.queue.{
  AWSSQSContainer,
  LocalSQSContainer,
  SQSDeduplicationContainer
}
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
  implicit val materializer: ActorMaterializer = ActorMaterializer(
    ActorMaterializerSettings(actorSystem)
      .withSupervisionStrategy { exception: Throwable =>
        log.noContext.error("Unhandled exception thrown", exception)
        Supervision.resume
      }
  )

  val config: Config = ConfigFactory.load()

  val environment = config.as[String]("environment")
  val isLocal = environment.toLowerCase == "local"

  implicit val container: Container =
    if (isLocal) {
      new JobContainer(config) with LocalSQSContainer
      with SQSDeduplicationContainer
    } else {
      new JobContainer(config) with AWSSQSContainer
      with SQSDeduplicationContainer
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
