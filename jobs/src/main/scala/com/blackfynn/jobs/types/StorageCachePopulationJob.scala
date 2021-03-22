package com.blackfynn.jobs.types

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Sink, Source }
import cats.data._
import cats.implicits._
import com.blackfynn.core.utilities._
import com.blackfynn.db._
import com.blackfynn.jobs.contexts.StorageCacheContext
import com.blackfynn.jobs.{ ExceptionError, InvalidOrganization, JobException }
import com.blackfynn.models.{ Dataset, Organization }
import com.blackfynn.service.utilities.{ ContextLogger, Tier }
import com.blackfynn.traits.PostgresProfile.api._
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
    mat: ActorMaterializer,
    ec: ExecutionContext
  ): Future[Unit] = {

    val datasetsMapper = new DatasetsMapper(organization)
    val datasetStorageMapper = new DatasetStorageMapper(organization)

    log.tierContext.info(
      s"Caching storage for organization ${organization.name} with id ${organization.id}"
    )(StorageCacheContext(organization.id))

    for {
      _ <- Source
        .fromFuture(db.run(datasetsMapper.result))
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
    mat: ActorMaterializer,
    ec: ExecutionContext
  ): Future[Unit] = {

    val datasetsMapper = new DatasetsMapper(organization)
    val packagesMapper = new PackagesMapper(organization)
    val packageStorageMapper = new PackageStorageMapper(organization)
    val datasetStorageMapper = new DatasetStorageMapper(organization)

    log.tierContext.info(
      s"Caching storage for dataset ${dataset.name} with id ${dataset.id}"
    )(StorageCacheContext(organization.id))

    db.run(for {
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
    } yield ())
  }

  def run(
    organizationId: Option[Int] = None
  )(implicit
    mat: ActorMaterializer,
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
