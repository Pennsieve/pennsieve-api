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

package com.pennsieve.managers

import java.sql.Timestamp
import java.time.{ ZoneOffset, ZonedDateTime }
import java.util.UUID

import cats.data.EitherT
import cats.implicits._
import com.pennsieve.audit.middleware.TraceId
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.core.utilities.{ checkOrErrorT, FutureEitherHelpers }
import com.pennsieve.models.Utilities.isNameValid
import com.pennsieve.db._
import com.pennsieve.domain._
import com.pennsieve.domain.StorageAggregation.{
  spackages => PackageStorageAggregationKey
}
import com.pennsieve.messages.{ BackgroundJob, DeletePackageJob }
import com.pennsieve.models.FileObjectType.Source
import com.pennsieve.models.PackageState.READY
import com.pennsieve.models.PackageType.Collection
import com.pennsieve.models.{
  CollectionUpload,
  Dataset,
  ExternalId,
  File,
  FileChecksum,
  FileObjectType,
  FileProcessingState,
  FileState,
  FileType,
  ModelProperty,
  NodeCodes,
  Organization,
  Package,
  PackageState,
  PackageType,
  PublicationStatus,
  User
}
import com.pennsieve.traits.PostgresProfile.api._
import io.circe.Json
import org.postgresql.util.PSQLException
import slick.jdbc.{ GetResult, SQLActionBuilder, TransactionIsolation }
import io.circe.parser.decode

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

class PackageManager(datasetManager: DatasetManager) {

  val organization: Organization = datasetManager.organization

  val actor: User = datasetManager.actor
  val db: Database = datasetManager.db

  val packagesMapper: PackagesMapper = new PackagesMapper(organization)
  val externalFilesMapper = new ExternalFilesMapper(organization)
  val datasetsMapper: DatasetsMapper = datasetManager.datasetsMapper
  val filesMapper = new FilesMapper(organization)

  val externalFileManager =
    new ExternalFileManager(externalFilesMapper, this)

  /**
    * Create a new package. If the package name is shared by another package in
    * the same directory, recommend a unique name.
    */
  def create(
    name: String,
    `type`: PackageType,
    state: PackageState,
    dataset: Dataset,
    ownerId: Option[Int],
    parent: Option[Package],
    importId: Option[UUID] = None, // explicitly set by upload request
    attributes: List[ModelProperty] = List(),
    description: Option[String] = None,
    externalLocation: Option[String] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Package] = {
    val nodeId = `type` match {
      case PackageType.Collection =>
        NodeCodes.generateId(NodeCodes.collectionCode)
      case _ => NodeCodes.generateId(NodeCodes.packageCode)
    }

    for {
      _ <- parent match {
        case None => FutureEitherHelpers.unit
        case Some(p) =>
          FutureEitherHelpers.assert(p.datasetId == dataset.id)(
            PredicateError(
              "a package must belong to the same dataset as its parent"
            )
          )
      }

      _ <- FutureEitherHelpers.assert(name.trim.nonEmpty)(
        PredicateError("package name must not be empty")
      )

      _ <- checkOrErrorT(isNameValid(name))(
        PredicateError(
          s"Invalid package name, please follow the naming conventions"
        )
      )

      createPackage = for {
        // If the package's name is taken use a generated recommendation
        // otherwise use what was given
        checkedName <- packagesMapper
          .checkName(name, parent, dataset, `type`)
          .map {
            case Left(NameCheckError(recommendation, _)) => recommendation
            case Right(name) => name
          }

        // Sanity check the generated name
        // TODO: Should be a unique constraint in the DB that we handle
        _ <- packagesMapper
          .assertNameIsUnique(checkedName, parent, dataset, `type`)

        packageId <- (packagesMapper returning packagesMapper.map(_.id)) +=
          Package(
            nodeId,
            checkedName,
            `type`,
            dataset.id,
            ownerId,
            state,
            parent.map(_.id),
            importId,
            attributes = attributes
          )

        pkg <- packagesMapper.get(packageId).result.headOption

      } yield pkg

      // Use REPEATABLE READ isolation to avoid phantom reads after the checkName query
      p <- runAndRetrySerializationErrors(db, createPackage, retries = 2)
        .toEitherT[CoreError] {
          // packages_import_dataset_id_key unique constraint violation
          case e: PSQLException if e.getSQLState == "23505" =>
            IntegrityError(e.getMessage)
          case e: Throwable =>
            ExceptionError(e)
        }
        .subflatMap {
          case Some(p) => Right(p)
          case None => Left(NotFound(s"Package"): CoreError)
        }

      // create an entry for an external file if this is an external package
      _ <- FutureEitherHelpers.assert(
        if (`type` == PackageType.ExternalFile) externalLocation.isDefined
        else true
      )(PredicateError("Externally Linked files must have a location"))
      _ <- if (`type` == PackageType.ExternalFile)
        externalFileManager.create(p, externalLocation.get, description)
      else Future.unit.toEitherT
    } yield p
  }

  /**
    * Run query under REPEATABLE READ transaction isolation and retry any
    * serialization errors.
    */
  def runAndRetrySerializationErrors[T](
    db: Database,
    query: DBIO[T],
    retries: Int
  )(implicit
    ec: ExecutionContext
  ): Future[T] =
    db.run(
        query.transactionally
          .withTransactionIsolation(TransactionIsolation.RepeatableRead)
      )
      .recoverWith {
        case e: PSQLException
            if retries > 0 && (e.getSQLState == "40001" ||
              e.getSQLState == "40P01") =>
          runAndRetrySerializationErrors(db, query, retries - 1)
      }

  def createSingleCollection(
    collectionUpload: CollectionUpload,
    dataset: Dataset,
    ownerId: Option[Int]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Package] = {

    val query = for {
      newOrExistingCollection <- for {
        parent <- collectionUpload.parentId match {
          case Some(pid) =>
            packagesMapper.getByNodeId(pid.value).result.headOption
          case None => DBIO.successful(None)
        }

        collection <- packagesMapper.createCollection(
          collectionUpload,
          parent,
          dataset,
          ownerId
        )
      } yield collection
    } yield newOrExistingCollection

    db.run(query.transactionally.asTry)
      .flatMap {
        case Failure(_: PSQLException) => {
          db.run(query.transactionally)
        }
        case Success(pkg) => {
          Future.successful(pkg)
        }
        case Failure(e: Throwable) => Future.failed(e)
      }
      .toEitherT
  }

  def createCollections(
    destinationPackage: Option[Package],
    dataset: Dataset,
    collectionsToCreate: List[CollectionUpload],
    ownerId: Option[Int]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[Package]] = {
    destinationPackage match {
      case Some(rootPackage) =>
        createNestedCollections(
          rootPackage,
          dataset,
          collectionsToCreate,
          ownerId
        )
      case None => {
        // No destination package was given, so we must create the root package before creating the collections nested within it
        val head = collectionsToCreate.head
        val tail = collectionsToCreate.tail
        createRootAndNestedCollections(head, dataset, tail, ownerId)
      }
    }
  }

  def createRootAndNestedCollections(
    rootCollection: CollectionUpload,
    dataset: Dataset,
    collectionsToCreate: List[CollectionUpload],
    ownerId: Option[Int]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[Package]] = {

    val query = for {
      newOrExistingRoot <- for {
        parent <- rootCollection.parentId match {
          case Some(pid) =>
            packagesMapper.getByNodeId(pid.value).result.headOption
          case None => DBIO.successful(None)
        }
        newOrExistingCollection <- packagesMapper.createCollection(
          rootCollection,
          parent,
          dataset,
          ownerId
        )
      } yield newOrExistingCollection

      nestedCollections <- packagesMapper
        .createNestedCollections(
          newOrExistingRoot,
          dataset,
          collectionsToCreate,
          ownerId
        )

    } yield nestedCollections

    runWithRetry(query)

  }

  def createNestedCollections(
    rootPackage: Package,
    dataset: Dataset,
    collectionsToCreate: List[CollectionUpload],
    ownerId: Option[Int]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[Package]] = {

    runWithRetry {
      packagesMapper
        .createNestedCollections(
          rootPackage,
          dataset,
          collectionsToCreate,
          ownerId
        )
    }

  }

  def runWithRetry(
    query: DBIO[List[Package]]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[Package]] = {
    db.run(query.transactionally.asTry)
      .flatMap {
        case Failure(_: PSQLException) => {
          db.run(query.transactionally)
        }
        case Success(packages) => {
          Future.successful(packages)
        }
        case Failure(e: Throwable) => Future.failed(e)
      }
      .toEitherT
  }

  def basePackageDatasetQuery
    : Query[(PackagesTable, DatasetsTable), (Package, Dataset), Seq] =
    packagesMapper
      .filter(_.state =!= (PackageState.DELETING: PackageState))
      .join(datasetsMapper)
      .on(_.datasetId === _.id)

  def getPackageAndDatasetByNodeId(
    nodeId: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Package, Dataset)] = {
    val query = basePackageDatasetQuery
      .filter {
        case (pkg, _) => pkg.nodeId === nodeId
      }
      .result
      .headOption
    db.run(query).whenNone(NotFound(s"Package ($nodeId)"))
  }

  def getByImportId(
    dataset: Dataset,
    importId: UUID
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Package] = {
    val query = basePackageDatasetQuery
      .map {
        case (pkg, _) => pkg
      }
      .filter(pkg => pkg.datasetId === dataset.id && pkg.importId === importId)
      .result
      .headOption
    db.run(query).whenNone(NotFound(s"Import ID $importId"))
  }

  def getPackagesAndDatasetsByNodeIds(
    nodeIds: List[String]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[(Package, Dataset)]] = {
    val query = basePackageDatasetQuery.filter {
      case (pkg, _) => pkg.nodeId inSet nodeIds
    }.result
    db.run(query).map(_.toList).toEitherT
  }

  def getPackageAndDatasetById(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Package, Dataset)] = {
    val query = basePackageDatasetQuery
      .filter {
        case (pkg, _) => pkg.id === id
      }
      .result
      .headOption
    db.run(query).whenNone(NotFound(s"Package ($id)"))
  }

  def getByNodeId(
    nodeId: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Package] =
    getPackageAndDatasetByNodeId(nodeId).map(_._1)

  def get(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Package] =
    getPackageAndDatasetById(id).map(_._1)

  def getParent(
    p: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Option[Package]] =
    p.parentId.traverse(get(_))

  def getByNodeIds(
    nodeIds: List[String]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[Package]] =
    db.run(
        packagesMapper
          .filter(_.nodeId inSet nodeIds)
          .filter(_.state =!= (PackageState.DELETING: PackageState))
          .result
      )
      .map(_.toList)
      .toEitherT

  def getByExternalIdsForDataset(
    dataset: Dataset,
    ids: List[ExternalId]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[Package]] = {
    val (intIds, nodeIds) = ids.map(_.value).separate

    db.run(
        packagesMapper
          .filter(_.datasetId === dataset.id)
          .filter(pkg => (pkg.id inSet intIds) || (pkg.nodeId inSet nodeIds))
          .filter(_.state =!= (PackageState.DELETING: PackageState))
          .result
      )
      .map(_.toList)
      .toEitherT
  }

  def maybeGetPackage(
    dataset: Dataset,
    id: ExternalId
  ): Future[Option[Package]] = {
    val (intId, nodeId) = List(id).map(_.value).separate

    db.run(
      packagesMapper
        .filter(_.datasetId === dataset.id)
        .filter(pkg => (pkg.id inSet intId) || (pkg.nodeId inSet nodeId))
        .filter(_.state =!= (PackageState.DELETING: PackageState))
        .result
        .headOption
    )
  }

  def children(
    parent: Option[Package],
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[Package]] =
    db.run(
        packagesMapper
          .children(parent, dataset)
          .result
      )
      .map(_.toList)
      .toEitherT

  def checkName(
    name: String,
    parent: Option[Package],
    dataset: Dataset,
    packageType: PackageType,
    duplicateThreshold: Int = 100
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, String] =
    EitherT {
      db.run(
        packagesMapper
          .checkName(name, parent, dataset, packageType, duplicateThreshold)
      )
    }

  def update(
    `package`: Package,
    description: Option[String] = None,
    externalLocation: Option[String] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Package] = {
    val packageToUpdate = `package`.copy(name = `package`.name.trim)

    for {
      old <- get(packageToUpdate.id)

      _ <- FutureEitherHelpers.assert(packageToUpdate.name.nonEmpty)(
        PredicateError("package name must not be empty")
      )

      parent <- packageToUpdate.parentId.traverse(this.get(_))

      _ <- FutureEitherHelpers.assert(
        parent match {
          case Some(p) => p.datasetId == packageToUpdate.datasetId
          case None => true
        }
      )(ServiceError("a package must belong to the same dataset as its parent"))

      _ <- checkOrErrorT(
        isNameValid(packageToUpdate.name) || old.name == packageToUpdate.name
      )(
        PredicateError(
          s"Invalid package name, please follow the naming conventions"
        )
      )

      nameCheckQuery = if (old.name == packageToUpdate.name) {
        DBIO.successful(())
      } else {
        for {
          dataset <- datasetsMapper.getDataset(old.datasetId)

          _ <- packagesMapper
            .assertNameIsUnique(
              packageToUpdate.name,
              parent,
              dataset,
              packageToUpdate.`type`
            )
        } yield ()
      }

      updateQuery = packagesMapper
        .get(packageToUpdate.id)
        .update(packageToUpdate)

      updateExternalFile = if (packageToUpdate.`type` == PackageType.ExternalFile && externalLocation.isDefined)
        externalFileManager
          .selectForUpdate(packageToUpdate, externalLocation.get, description)
          .flatMap(_ => DBIO.successful(()))
      else DBIO.successful(())

      currentUpdatedAtQuery = packagesMapper
        .get(packageToUpdate.id)
        .map(_.updatedAt)
        .result
        .headOption

      queries = for {
        _ <- nameCheckQuery
        _ <- updateQuery
        _ <- updateExternalFile
        updateAt <- currentUpdatedAtQuery
      } yield updateAt

      currentUpdatedAt <- db.run(queries.transactionally).toEitherT
    } yield
      packageToUpdate.copy(
        updatedAt = currentUpdatedAt.getOrElse(packageToUpdate.updatedAt)
      )
  }

  /**
    * Soft delete a package. Returns a DeleteJob to be scheduled that will
    * hard-delete the package and all assets.
    *
    * @param traceId The trace ID from the endpoint that initiated the deletion
    * @param p The Package to delete
    * @param storageManager An StorageManager instance used to immediately
    *   decrement storage counts for the package.  This is passed directly
    *   (instead of living on the class) so that the the PackageManager can, in
    *   general, be used without a dependency on the Redis storage instance (eg
    *   for discover-publish).
    */
  def delete(
    traceId: TraceId,
    pkg: Package
  )(
    storageManager: StorageServiceClientTrait
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, BackgroundJob] = {

    for {
      // Deletion is not allowed while a dataset is locked
      // to prevent race conditions where a publish job starts, then a package
      // is deleted, and the delete job removes one of the source assets before
      // it is copied to the publish bucket.
      _ <- datasetManager.assertNotLocked(pkg.datasetId)
      _ <- db
        .run(
          packagesMapper
            .get(pkg.id)
            .map(_.state)
            .update(PackageState.DELETING)
        )
        .toEitherT

      amount <- storageManager
        .getStorage(PackageStorageAggregationKey, List(pkg.id))
        .map(_.get(pkg.id).flatten)

      _ <- amount.traverse { a =>
        // TODO: this state check is wrong. Should be allowed for all states after UPLOADED?
        if (pkg.state == READY) {
          storageManager.incrementStorage(
            PackageStorageAggregationKey,
            -a,
            pkg.id
          )
        } else {
          Either
            .right[CoreError, Map[String, Long]](Map.empty)
            .toEitherT[Future]
        }
      }
    } yield
      DeletePackageJob(pkg.id, organization.id, actor.nodeId, traceId = traceId): BackgroundJob

  }

  def descendants(p: Package)(implicit ec: ExecutionContext): Future[Set[Int]] =
    packagesMapper.descendants(p, db)

  def ancestors(
    p: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[Package]] =
    EitherT(packagesMapper.ancestors(p, db))

  def switchOwner(
    user: User,
    owner: Option[User]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] = {
    db.run(packagesMapper.switchOwner(user.id, owner.map(_.id))).toEitherT
  }

  def packageTypes(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Map[PackageType, Int]] =
    db.run {
      packagesMapper
        .filter(p => p.datasetId === dataset.id)
        .filter(_.state =!= (PackageState.DELETING: PackageState))
        .groupBy(p => p.`type`)
        .map { case (group, result) => (group, result.length) }
        .result
        .map(counts => counts.toMap)
    }.toEitherT

  def toZonedDatetime(t: Timestamp): ZonedDateTime =
    ZonedDateTime.ofInstant(t.toInstant, ZoneOffset.UTC)

  def getPackageQuery(
    dataset: Dataset,
    startAtId: Option[Int],
    pageSize: Int,
    filterType: Option[Set[PackageType]],
    filename: Option[String],
    withSourceFiles: Boolean
  ): SQLActionBuilder = {

    val selectFiles =
      if (withSourceFiles)
        ", array_to_json(array_agg(row_to_json(f.*)))"
      else
        ", '[]'::json"

    val stringFilterTypes: Option[List[String]] =
      filterType.map(_.toList.map(_.entryName))

    // If we are filtering by filename we need to use an inner join to exclude
    // other packages.
    val filterFiles: Option[SQLActionBuilder] =
      filename.map(f => sql"""
          JOIN (
            SELECT *
            FROM "#${organization.schemaId}".files
            WHERE name = $f
            AND object_type = ${Source.entryName}
          ) f
          ON f.package_id = p.id
          """)

    // We only need to join on source files if we are returning nested source files.
    // If we are also filtering by source filename, only returning *matching* source files.
    val joinFiles: Option[SQLActionBuilder] =
      if (withSourceFiles)
        Some(sql"""
          LEFT JOIN (
            SELECT * FROM (
              SELECT *, row_number()
              OVER (PARTITION BY package_id ORDER BY id) AS row_num
              FROM "#${organization.schemaId}".files
          """.opt(filename)(f => sql"WHERE name = $f") ++ sql"""
            ) fs
            WHERE row_num <= 101 AND object_type = ${Source.entryName}
          ) f
          ON f.package_id = p.id
          """)
      else None

    sql"""
       SELECT p.*#${selectFiles}
       FROM "#${organization.schemaId}".packages p
       INNER JOIN (
         SELECT p.id FROM "#${organization.schemaId}".packages p
       """.opt(filterFiles)(q => q) ++ sql"""
         WHERE p.id >= ${startAtId.getOrElse(0)}
         AND p.dataset_id = ${dataset.id}
       """.opt(stringFilterTypes)(t => sql"AND p.type = any($t)") ++ sql"""
         ORDER BY p.id
         LIMIT ${pageSize + 1}
       ) page ON p.id = page.id
       """.opt(joinFiles)(q => q) ++ sql"""
       GROUP BY p.id
       """
  }

  implicit val packageWithFiles: GetResult[(Package, Seq[File])] =
    GetResult { p =>
      val id = p.<<[Int]
      val name = p.<<[String]
      val `type` = p.<<[String]
      val state = p.<<[String]
      val datasetId = p.<<[Int]
      val parentId = p.<<?[Int]
      val updatedAt = p.<<[Timestamp]
      val createdAt = p.<<[Timestamp]
      val attributes = p.<<[Json]
      val nodeId = p.<<[String]
      val _ = p.<<?[Int]
      val ownerId = p.<<?[Int]
      val importId = p.<<?[String]

      val packageA = Package(
        nodeId,
        name,
        PackageType.withName(`type`),
        datasetId,
        ownerId,
        PackageState.withName(state),
        parentId,
        importId.map(UUID.fromString),
        toZonedDatetime(createdAt),
        toZonedDatetime(updatedAt),
        id,
        attributes.as[List[ModelProperty]].right.get
      )

      decode[Seq[Option[File]]](p.nextString()) match {
        case Right(files) => (packageA, files.flatten)
        case Left(err) => throw err
      }
    }

  type PackagePageTruncated = (Package, Seq[File], Boolean)

  def getPackagesPageWithFiles(
    dataset: Dataset,
    startAtId: Option[Int],
    pageSize: Int,
    filterType: Option[Set[PackageType]],
    filename: Option[String]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[PackagePageTruncated]] =
    db.run {
        getPackageQuery(
          dataset,
          startAtId,
          pageSize,
          filterType,
          filename,
          withSourceFiles = true
        ).as[(Package, Seq[File])]
      }
      .map { page =>
        page
          .map {
            case (p, files) if files.length > 100 =>
              (p, files.dropRight(1), true)
            case (p, files) => (p, files, false)
          }
          .to[Seq]
      }
      .toEitherT

  def markFilesInPendingStateAsUploaded(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] = {
    db.run(
        filesMapper
          .filter(
            _.id in (packagesMapper
              .filter(_.datasetId === dataset.id)
              .join(filesMapper)
              .on(_.id === _.packageId)
              .filter(_._2.uploadedState === (FileState.PENDING: FileState))
              .map {
                case (_, files) => files.id
              })
          )
          .map(f => f.uploadedState)
          .update((Some(FileState.UPLOADED): Option[FileState]))
      )
      .toEitherT
  }

  def getPackagesPage(
    dataset: Dataset,
    startAtId: Option[Int],
    pageSize: Int,
    filterType: Option[Set[PackageType]],
    filename: Option[String]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Vector[Package]] =
    db.run {
        getPackageQuery(
          dataset,
          startAtId,
          pageSize,
          filterType,
          filename,
          withSourceFiles = false
        ).as[(Package, Seq[File])]
      }
      .map(_.map {
        case (pkg, _) => pkg
      })
      .toEitherT

  case class PackageHierarchy(
    datasetId: Int,
    nodeIdPath: Seq[String],
    packageId: Int,
    nodeId: String,
    packageType: PackageType,
    packageState: PackageState,
    packageNamePath: Seq[String],
    packageName: String,
    packageFileCount: Int,
    fileId: Int,
    fileName: String,
    size: Long,
    fileType: FileType,
    s3Bucket: String,
    s3Key: String
  )

  implicit val packageHierarchy: GetResult[PackageHierarchy] =
    GetResult { p =>
      val datasetId = p.<<[Int]
      val nodeIdPath = p.<<[Seq[String]]
      val packageId = p.<<[Int]
      val nodeId = p.<<[String]
      val packageType = p.<<[String]
      val packageState = p.<<[String]
      val packageNamePath = p.<<[Seq[String]]
      val packageName = p.<<[String]
      val packageFileCount = p.<<[Int]
      val fileId = p.<<[Int]
      val fileName = p.<<[String]
      val size = p.<<[Long]
      val fileType = p.<<[String]
      val s3Bucket = p.<<[String]
      val s3Key = p.<<[String]

      PackageHierarchy(
        datasetId,
        nodeIdPath,
        packageId,
        nodeId,
        PackageType.withName(packageType),
        PackageState.withName(packageState),
        packageNamePath,
        packageName,
        packageFileCount,
        fileId,
        fileName,
        size,
        FileType.withName(fileType),
        s3Bucket,
        s3Key
      )
    }

  def getPackageHierarchy(
    nodeIds: List[String],
    fileIds: Option[List[Int]] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[PackageHierarchy]] =
    db.run {

        sql"""
              WITH RECURSIVE parents AS (
                SELECT
                  id,
                  parent_id,
                  dataset_id,
                  name,
                  type,
                  node_id,
                  size,
                  state,
                  ARRAY[]::VARCHAR[] AS node_id_path,
                  ARRAY[]::VARCHAR[] AS name_path
                FROM
                  "#${organization.schemaId}".packages
                WHERE
                  node_id = any($nodeIds)
                UNION
                SELECT
                  children.id,
                  children.parent_id,
                  children.dataset_id,
                  children.name,
                  children.type,
                  children.node_id,
                  children.size,
                  children.state,
                  (parents.node_id_path || parents.node_id)::VARCHAR[] AS node_id_path,
                  (parents.name_path || parents.name)::VARCHAR[] AS name_path
                FROM
                  "#${organization.schemaId}".packages children
                INNER JOIN parents ON
                  parents.id = children.parent_id
              )

              SELECT
                parents.dataset_id,
                parents.node_id_path AS node_id_path,
                parents.id AS package_id,
                parents.node_id,
                parents.type AS package_type,
                parents.state AS package_state,
                parents.name_path AS package_name_path,
                parents.name AS package_name,
                f_count.package_file_count,
                f.id AS file_id,
                f.name AS file_name,
                f.size,
                f.file_type,
                f.s3_bucket,
                f.s3_key
              FROM
                parents
              JOIN "#${organization.schemaId}".files f ON
                f.package_id = parents.id
              JOIN (
                SELECT
                  package_id,
                  count(*) AS package_file_count
                FROM
                  "#${organization.schemaId}".files
                  GROUP BY package_id
              ) AS f_count ON f_count.package_id = parents.id
              WHERE
                type != ${Collection.entryName}
              AND
                parents.state != ${PackageState.DELETING.entryName}
              AND
                f.object_type = ${FileObjectType.Source.entryName}
        """
          .opt(fileIds)(_ => sql"AND f.id = any($fileIds)")
          .as[PackageHierarchy]
      }
      .map(_.to[Seq])
      .toEitherT
}
