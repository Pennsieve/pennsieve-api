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

package com.pennsieve.jobs.types

import akka.actor.ActorSystem
import akka.stream.scaladsl.{ Sink, Source }
import cats.data._
import cats.implicits._
import com.pennsieve.core.utilities._
import com.pennsieve.db._
import com.pennsieve.jobs.contexts.StorageCacheContext
import com.pennsieve.jobs.{ ExceptionError, InvalidOrganization, JobException }
import com.pennsieve.models.{ Dataset, Organization }
import com.pennsieve.service.utilities.{ ContextLogger, Tier }
import com.pennsieve.traits.PostgresProfile.api._
import com.typesafe.config.Config

import scala.concurrent.{ ExecutionContext, Future }

class StorageCachePopulationJob(
  insecureContainer: DatabaseContainer,
  dryRun: Boolean = true
)(implicit
  log: ContextLogger
) {

  val db: Database = insecureContainer.db

  implicit val tier: Tier[StorageCachePopulationJob] =
    Tier[StorageCachePopulationJob]

  private def cacheOrganizationStorage(
    organization: Organization
  )(implicit
    system: ActorSystem,
    ec: ExecutionContext
  ): Future[Unit] = {

    val datasetsMapper = new DatasetsMapper(organization)
    val datasetStorageMapper = new DatasetStorageMapper(organization)

    log.tierContext.info(
      s"Caching storage for organization ${organization.name} with id ${organization.id}"
    )(StorageCacheContext(organization.id))

    for {
      _ <- Source
        .future(db.run(datasetsMapper.result))
        .mapConcat(_.toList)
        .map(organization -> _)
        .mapAsync(parallelism = 1)(cacheDatasetStorage _ tupled)
        .runWith(Sink.ignore)

      // Set total organization size to be the sum of all datasets in the organization
      organizationSize <- db.run(
        datasetsMapper.withoutDeleted
          .join(datasetStorageMapper)
          .on(_.id === _.datasetId)
          .map(_._2.size)
          .sum
          .result
          .map(_.getOrElse(0L))
      )
      _ <- db.run(
        OrganizationStorageMapper
          .setOrganization(organization.id, organizationSize)
      )

    } yield
      log.tierContext.info(
        s"Finished caching storage for organization ${organization.name} with id ${organization.id}"
      )(StorageCacheContext(organization.id))
  }

  private def cacheDatasetStorage(
    organization: Organization,
    dataset: Dataset
  )(implicit
    system: ActorSystem,
    ec: ExecutionContext
  ): Future[Unit] = {

    val datasetsMapper = new DatasetsMapper(organization)
    val packagesMapper = new PackagesMapper(organization)
    val packageStorageMapper = new PackageStorageMapper(organization)
    val datasetStorageMapper = new DatasetStorageMapper(organization)

    log.tierContext.info(
      s"Caching storage for dataset ${dataset.name} with id ${dataset.id}"
    )(StorageCacheContext(organization.id))

    val dbAction = for {
      _ <- packageStorageMapper.refreshPackageStorage(dataset)

      // Set the storage of the dataset to be the sum of sizes of packages at
      // the root of the dataset.
      datasetSize <- packagesMapper
        .children(parent = None, dataset)
        .join(packageStorageMapper)
        .on(_.id === _.packageId)
        .map(_._2.size)
        .sum
        .result
        .map(_.getOrElse(0L))

      _ <- datasetStorageMapper.setDataset(dataset.id, datasetSize)
    } yield ()

    db.run(dbAction)
  }

  def run(
    organizationId: Option[Int] = None
  )(implicit
    system: ActorSystem,
    ec: ExecutionContext
  ): EitherT[Future, JobException, Unit] =
    EitherT {
      organizationId match {
        case None =>
          for {
            organizations <- db.run(OrganizationsMapper.result)
            _ <- Source(organizations.toList)
              .mapAsync(parallelism = 1)(cacheOrganizationStorage)
              .runWith(Sink.ignore)
          } yield ().asRight[JobException]

        case Some(orgId) =>
          for {
            organization <- db.run(OrganizationsMapper.get(orgId))
            _ <- organization match {
              case Some(organization) =>
                cacheOrganizationStorage(organization)
                  .map(_.asRight[JobException])
                  .recover {
                    case e: Exception => ExceptionError(e).asLeft[Unit]
                  }
              case None =>
                Future.successful(InvalidOrganization(orgId).asLeft[Unit])
            }
          } yield ().asRight[JobException]
      }
    }
}

object StorageCachePopulationJob {

  def apply(
    config: Config
  )(implicit
    ec: ExecutionContext,
    system: ActorSystem,
    log: ContextLogger
  ): StorageCachePopulationJob = {

    val insecureContainer =
      new InsecureContainer(config) with DatabaseContainer

    new StorageCachePopulationJob(insecureContainer, false)
  }
}
