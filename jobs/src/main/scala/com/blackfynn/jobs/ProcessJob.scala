package com.pennsieve.jobs

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import cats.data._
import cats.implicits._
import com.pennsieve.jobs.container._
import com.pennsieve.jobs.types._
import com.pennsieve.messages._
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.{ ExecutionContext, Future }

object ProcessJob {

  def apply(
    deleteRunner: CreditDeleteJob,
    cacheRunner: StorageCachePopulationJob,
    logChangeEventRunner: DatasetChangelogEvent
  )(implicit
    system: ActorSystem,
    mat: ActorMaterializer,
    ec: ExecutionContext
  ): ProcessJob = {
    new ProcessJob(deleteRunner, cacheRunner, logChangeEventRunner)
  }

}

class ProcessJob(
  deleteRunner: CreditDeleteJob,
  cacheRunner: StorageCachePopulationJob,
  logChangeEventRunner: DatasetChangelogEvent
) extends LazyLogging {

  def processJob(
    job: BackgroundJob
  )(implicit
    mat: ActorMaterializer,
    executionContext: ExecutionContext,
    container: Container
  ): EitherT[Future, JobException, Unit] = {

    job match {
      case dpj: DeletePackageJob => deleteRunner.creditDeleteJob(dpj)
      case ddj: DeleteDatasetJob => deleteRunner.deleteDatasetJob(ddj)
      case cpj: CachePopulationJob => cacheRunner.run(cpj.organizationId)
      case dlcej: DatasetChangelogEventJob => logChangeEventRunner.run(dlcej)
      case _ =>
        (Left(InvalidJob(job.getClass.getName)): Either[JobException, Unit])
          .toEitherT[Future]
    }
  }
}
