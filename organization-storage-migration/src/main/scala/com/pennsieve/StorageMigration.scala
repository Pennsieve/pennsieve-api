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

package com.pennsieve.migrations.storage

import cats.data._
import cats.implicits._
import com.amazonaws.services.s3.model._
import com.pennsieve.aws.s3._
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.db._
import com.pennsieve.models._
import com.pennsieve.core.utilities.DatabaseContainer
import com.pennsieve.utilities.{ AbstractError, Container }
import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }

import net.ceedubs.ficus.Ficus._

import scala.collection.mutable
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

abstract class MigrationContainer(val config: Config)
    extends Container
    with DatabaseContainer
    with S3Container

object Main extends App {
  val config: Config = ConfigFactory.load()
  implicit val container = new MigrationContainer(config) with AWSS3Container

  MigrateOrganizationStorage.migrate
}

object MigrateOrganizationStorage {
  def migrate(implicit c: MigrationContainer): Unit = {
    val runner = new MigrateOrganizationStorage()
    runner.migrate
  }
}

case class MissingFiles(files: List[File]) extends Throwable

/**
  * Migrate source files from the Pennsieve storage bucket to an external,
  * organization-specific bucket. This script *does not* move view files.
  *
  * Run the migration using the $env-organization-storage-migration jobs in Jenkins.
  */
class MigrateOrganizationStorage {

  sealed trait TargetDataset
  case object AllDatasets extends TargetDataset
  case class SingleDataset(id: Int) extends TargetDataset

  def await[A](e: Future[A]): A = Await.result(e, Duration.Inf)

  def run[A](q: DBIO[A])(implicit c: DatabaseContainer): A =
    await(c.db.run(q))

  def migrate(
    implicit
    c: MigrationContainer
  ): Unit = {
    val dryRun = c.config.as[Boolean]("dry_run")
    val environment = c.config.as[String]("environment").toLowerCase()
    val organizationId = c.config.as[Int]("organization_id")
    val datasetId = c.config.as[String]("dataset_id").toLowerCase.trim match {
      case "all" => AllDatasets
      case s => SingleDataset(s.toInt)
    }
    val pennsieveStorageBucket = c.config.as[String]("storage_bucket")
    val partSize = c.config.as[Long]("multipart_part_size")

    if (!Seq("dev", "prod", "local").contains(environment)) {
      throw new Exception(
        s"invalid environment $environment, must be one of (dev, prod, local)"
      )
    }

    if (dryRun) {
      println("*********************************")
      println("* DRY RUN - NOT MOVING ANY DATA *")
      println("*********************************")
    }

    val organization = run(OrganizationsMapper.getOrganization(organizationId))
    println(
      s"Migrating storage for organization ${organization.id} (${organization.name})"
    )

    val destinationBucket = organization.storageBucket match {
      case Some(bucket) => bucket
      case None =>
        throw new Exception(
          "Nothing to migrate: custom storage bucket not set for organization"
        )
    }

    implicit val datasetsMapper = new DatasetsMapper(organization)
    implicit val packagesMapper = new PackagesMapper(organization)
    implicit val filesMapper = new FilesMapper(organization)

    val datasets = datasetId match {
      case AllDatasets =>
        run(datasetsMapper.withoutDeleted.result)
      case SingleDataset(id) =>
        run(
          datasetsMapper.withoutDeleted
            .filter(_.id === id)
            .result
            .headOption
            .flatMap {
              case None =>
                DBIO.failed(new Exception(s"No dataset with id $id exists"))
              case Some(dataset) => DBIO.successful(List(dataset))
            }
        )
    }

    for (dataset <- datasets) {

      println(s"  Migrating dataset ${dataset.id} (${dataset.name})")

      // Nice, fun, mutable data structures
      val missingFiles = new mutable.ArrayBuffer[File]()

      for ((source, pkg) <- run(
          filesMapper
            .filter(_.objectType === (FileObjectType.Source: FileObjectType))
            .filter(_.s3bucket === pennsieveStorageBucket)
            .join(packagesMapper)
            .on(_.packageId === _.id)
            .filter(_._2.datasetId === dataset.id)
            .filter(_._2.state =!= (PackageState.DELETING: PackageState))
            .filter(_._2.state =!= (PackageState.DELETED: PackageState))
            .result
        )) {

        if (dryRun) {
          println(
            s"    Dry run: would move file ${source.id} (${source.name}) from s3://${source.s3Bucket}/${source.s3Key} to s3://${destinationBucket}/${source.s3Key}"
          )
        } else {
          println(
            s"    Moving file ${source.id} (${source.name}) from s3://${source.s3Bucket}/${source.s3Key} to s3://${destinationBucket}/${source.s3Key}"
          )

          c.s3.multipartCopy(
            sourceBucket = source.s3Bucket,
            sourceKey = source.s3Key,
            destinationBucket = destinationBucket,
            destinationKey = source.s3Key,
            acl = Some(CannedAccessControlList.BucketOwnerFullControl),
            multipartChunkSize = partSize
          ) match {

            case Right(_) =>
              updateBucket(source, destinationBucket)
              deleteOriginal(source)

            case Left(e) if alreadyMoved(source, destinationBucket) =>
              // File is referenced by another package, and that package has
              // been migrated
              println("Already moved")
              updateBucket(source, destinationBucket)

            case Left(e) if pendingDeletion(pkg) =>
              // File belongs to a package which is a child of a deleting
              // collection. Do nothing.
              println("Pending deletion")

            case Left(e) =>
              // This is bad: the file does not exist in S3. Aggregate all
              // missing files and throw a single exception after migrating the
              // rest of this dataset.
              println(e.getMessage)
              missingFiles += source
          }
        }
      }

      if (missingFiles.length > 0) {
        throw new MissingFiles(missingFiles.toList)
      }
    }
  }

  /**
    * Check if the source file has already been moved to the new bucket (because
    * multiple files reference the same S3 location).
    */
  def alreadyMoved(
    source: File,
    destinationBucket: String
  )(implicit
    filesMapper: FilesMapper,
    c: MigrationContainer
  ): Boolean = {
    run(
      filesMapper
        .filter(_.s3bucket === destinationBucket)
        .filter(_.s3key === source.s3Key)
        .filter(_.id =!= source.id)
        .exists
        .result
    ) && c.s3.getObjectMetadata(destinationBucket, source.s3Key).isRight
  }

  def updateBucket(
    source: File,
    destinationBucket: String
  )(implicit
    filesMapper: FilesMapper,
    c: MigrationContainer
  ) =
    run(
      filesMapper
        .filter(_.packageId === source.packageId)
        .filter(_.id === source.id)
        .map(_.s3bucket)
        .update(destinationBucket)
    )

  def deleteOriginal(source: File)(implicit c: MigrationContainer) =
    c.s3
      .deleteObject(source.s3Bucket, source.s3Key)
      .leftMap(throw _)

  /**
    * Check if any ancestor collection of the package is DELETING.  This means
    * that the child package is also ready for deletion, even if not in DELETING
    * state.
    */
  def pendingDeletion(
    pkg: Package
  )(implicit
    filesMapper: FilesMapper,
    packagesMapper: PackagesMapper,
    c: MigrationContainer
  ): Boolean = await(packagesMapper.ancestors(pkg, c.db)) match {
    case Right(ancestors)
        if ancestors.exists(p => p.state == PackageState.DELETING) =>
      printAncestorTree(ancestors)
      true
    case Right(_) => false
    case Left(e) => throw e
  }

  def printAncestorTree(ancestors: List[Package]) = {
    var indent: Int = 6

    def spaces(i: Int) =
      List.fill(i)(' ').mkString

    println(s"${spaces(indent)}Ancestor is DELETING: ")

    println(s"${spaces(indent)}ROOT")
    indent += 1

    for (ancestor <- ancestors) {
      println(
        s"${spaces(indent)}└── (${ancestor.id}, ${ancestor.name}, ${ancestor.state})"
      )
      indent += 1
    }
    println(s"${spaces(indent)}└── PKG")
  }
}
