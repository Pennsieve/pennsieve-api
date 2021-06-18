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
    system: ActorSystem,
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
