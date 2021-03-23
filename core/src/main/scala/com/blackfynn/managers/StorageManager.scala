package com.pennsieve.managers

import com.pennsieve.core.utilities._
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.db._
import com.pennsieve.domain.{
  CoreError,
  Error,
  ExceptionError,
  NotFound,
  StorageAggregation
}
import com.pennsieve.domain.StorageAggregation.spackages
import com.pennsieve.messages.{ BackgroundJob, CachePopulationJob }
import com.pennsieve.models.{
  Dataset,
  File,
  FileObjectType,
  Organization,
  Package,
  User
}
import com.pennsieve.traits.PostgresProfile.api._
import cats.data.EitherT
import cats.implicits._
import com.rms.miu.slickcats.DBIOInstances._
import com.typesafe.scalalogging.LazyLogging

import scala.annotation.tailrec
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration._
import scala.util.Try

trait StorageServiceClientTrait {

  def getStorage(
    storageType: StorageAggregation,
    itemIds: List[Int]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Map[Int, Option[Long]]]

  def incrementStorage(
    storageType: StorageAggregation,
    size: Long,
    itemId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Map[String, Long]]

  def setPackageStorage(
    `package`: Package
  )(implicit
    executionContext: ExecutionContext
  ): EitherT[Future, CoreError, Long]
}

object StorageManager {

  /**
    * Create a new storage container.
    */
  def create(
    container: DatabaseContainer,
    organization: Organization
  ): StorageServiceClientTrait =
    new StorageManager(container.db, organization)

  def updateCache(
    organization: Organization
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, BackgroundJob] = {
    Future
      .successful(
        CachePopulationJob(dryRun = false, Some(organization.id)): BackgroundJob
      )
      .toEitherT
  }
}

/**
  * Storage manager that stores data sizes directly in Postgres.
  *
  * We track the size of the following entities in the platform:
  *
  *   - files
  *   - packages (and collections)
  *   - datasets
  *   - organizations
  *   - users
  *
  * These entities form a natural hierarchy whereby the size of a package is
  * equal to the sum of the sizes of the sources files in the packages, the size
  * of a collection is equal to the sum of the sizes of child packages in the
  * collection, etc. To efficiently return the size of any entity, we cache the
  * size of the entity in the database.
  *
  * Operations in the platform increment and decrement sizes. Those changes must
  * be propagated up the hierarchy to also increment and decrement storage sizes
  * for parent entities.
  */
class StorageManager(val db: Database, val organization: Organization)
    extends LazyLogging
    with StorageServiceClientTrait {

  val datasetsMapper = new DatasetsMapper(organization)
  val packagesMapper = new PackagesMapper(organization)

  val datasetStorageMapper = new DatasetStorageMapper(organization)
  val packageStorageMapper = new PackageStorageMapper(organization)
  val organizationStorageMapper = OrganizationStorageMapper

  /**
    * Get the current storage size for multiple entities of the same type.
    *
    * TODO: this is a backwards-compatible method to match Redis storage. Remove
    * it and get storage directly from the database rows once Redis storage is
    * removed.
    */
  override def getStorage(
    storageType: StorageAggregation,
    itemIds: List[Int]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Map[Int, Option[Long]]] =
    db.run(storageType match {
        case StorageAggregation.sorganizations =>
          organizationStorageMapper
            .filter(_.organizationId inSet itemIds)
            .map(storage => storage.organizationId -> storage.size)
            .result

        case StorageAggregation.spackages =>
          packageStorageMapper
            .filter(_.packageId inSet itemIds)
            .map(storage => storage.packageId -> storage.size)
            .result

        case StorageAggregation.sdatasets =>
          datasetStorageMapper
            .filter(_.datasetId inSet itemIds)
            .map(storage => storage.datasetId -> storage.size)
            .result

        // TODO implement this
        case StorageAggregation.susers =>
          DBIO.successful(itemIds.map(_ -> None))
      })
      .map(_.toMap)
      // Add null values for itemIds that are not found
      .map(m => m ++ itemIds.filterNot(m contains _).map(_ -> None))
      .toEitherT

  /**
    * Increment the storage count for any supported entity.
    */
  override def incrementStorage(
    storageType: StorageAggregation,
    size: Long,
    itemId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Map[String, Long]] = {
    val query = storageType match {
      case StorageAggregation.spackages =>
        incrementPackage(itemId, size)
      case StorageAggregation.sdatasets =>
        datasetStorageMapper.incrementDataset(itemId, size)
      case StorageAggregation.sorganizations =>
        organizationStorageMapper.incrementOrganization(itemId, size)
      case StorageAggregation.susers => incrementUser(itemId, size)
    }
    db.run(query.transactionally).map(_ => Map.empty[String, Long]).toEitherT
  }

  /**
    * Recompute the storage of a package based on the current sizes of its
    * source files.
    */
  override def setPackageStorage(
    pkg: Package
  )(implicit
    executionContext: ExecutionContext
  ): EitherT[Future, CoreError, Long] = {
    val query = for {
      packageSourceSize <- packagesMapper.sourceSize(pkg).result

      currentSize <- packageStorageMapper
        .filter(_.packageId === pkg.id)
        .map(_.size)
        .take(1)
        .result
        .headOption
        .map(_.flatten.getOrElse(0L))

      difference = packageSourceSize.getOrElse(0L) - currentSize

      // increment/decrement package size to match the current value
      _ <- incrementPackage(pkg, difference)
    } yield packageSourceSize.getOrElse(0L)

    db.run(query.transactionally).toEitherT
  }

  private def incrementPackage(
    packageId: Int,
    size: Long
  )(implicit
    ec: ExecutionContext
  ): DBIO[Map[String, Long]] =
    for {
      pkg <- getPackage(packageId)
      keys <- incrementPackage(pkg, size)
    } yield keys

  /**
    * Increment storage for a package, propagating changes to all containing
    * entities.
    */
  private def incrementPackage(
    pkg: Package,
    size: Long
  )(implicit
    ec: ExecutionContext
  ): DBIO[Map[String, Long]] =
    for {
      _ <- packageStorageMapper.incrementPackage(pkg.id, size)
      _ <- packageStorageMapper.incrementPackageAncestors(pkg, size)
      _ <- datasetStorageMapper.incrementDataset(pkg.datasetId, size)
      _ <- organizationStorageMapper.incrementOrganization(
        organization.id,
        size
      )
      _ <- pkg.ownerId.traverse(incrementUser(_, size))
    } yield Map.empty[String, Long]

  /**
    * TODO: implement this. The size should be stored on the `organization_user` table.
    */
  private def incrementUser(
    userId: Int,
    size: Long
  )(implicit
    ec: ExecutionContext
  ): DBIO[Int] = DBIO.successful(0)

  private def getPackage(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): DBIO[Package] =
    packagesMapper
      .get(id)
      .result
      .headOption
      .flatMap {
        case Some(pkg) => DBIO.successful(pkg)
        case None => DBIO.failed(NotFound(s"Package ($id)"))
      }
}
