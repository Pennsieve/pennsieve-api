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
import com.pennsieve.core.utilities._
import com.pennsieve.db._
import com.pennsieve.managers.BaseManagerSpec
import com.pennsieve.test._
import com.pennsieve.test.helpers._
import org.scalatest.EitherValues._
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.models._
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import org.scalatest._
import matchers._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random
import scala.jdk.CollectionConverters._
import org.apache.commons.io.IOUtils
import org.scalatest.matchers.should.Matchers

class TestStorageMigration
    extends BaseManagerSpec
    with S3DockerContainer
    with Matchers {

  // Only available after Docker containers start
  implicit def config: Config =
    ConfigFactory
      .empty()
      .withFallback(postgresContainer.config)
      .withFallback(s3Container.config)
      .withValue(
        "s3.host",
        ConfigValueFactory.fromAnyRef(s3Container.endpointUrl)
      )
      .withValue("dry_run", ConfigValueFactory.fromAnyRef(false))
      .withValue("environment", ConfigValueFactory.fromAnyRef("local"))
      .withValue(
        "organization_id",
        ConfigValueFactory.fromAnyRef(testOrganization.id)
      )
      .withValue("dataset_id", ConfigValueFactory.fromAnyRef(testDataset.id))
      .withValue("storage_bucket", ConfigValueFactory.fromAnyRef(storageBucket))
      .withValue(
        "multipart_part_size",
        ConfigValueFactory.fromAnyRef(5368709120L)
      )

  def createContainer(
    implicit
    config: Config
  ): MigrationContainer with LocalS3Container =
    new MigrationContainer(config) with LocalS3Container

  /**
    * Scalatest fixture to create and cleanup a container https://www.scalatest.org/user_guide/sharing_fixtures
    */
  def withContainer(config: Config)(testCode: MigrationContainer => Any) = {
    val container = createContainer(config)
    try {
      container.db
        .run(
          OrganizationsMapper
            .filter(_.id === testOrganization.id)
            .map(_.storageBucket)
            .update(Some(sparcStorageBucket))
        )
        .await

      testCode(container) // "loan" the fixture to the test
    } finally container.db.close
  }

  val uploadBucket = "local-uploads-use1"
  val storageBucket = "local-pennsieve-storage-use1"
  val sparcStorageBucket = "local-sparc-storage-use1"

  override def afterStart(): Unit = {
    super.afterStart()

    val s3 = new S3(s3Container.s3Client)
    s3.createBucket(uploadBucket) shouldBe 'right
    s3.createBucket(storageBucket) shouldBe 'right
    s3.createBucket(sparcStorageBucket) shouldBe 'right
  }

  def createS3File(
    file: File,
    content: String = randomString(),
    metadata: Option[ObjectMetadata] = None
  )(implicit
    c: MigrationContainer
  ): String = {

    metadata match {
      case None =>
        c.s3.putObject(file.s3Bucket, file.s3Key, content).leftMap(throw _)
      case Some(metadata) =>
        c.s3
          .putObject(
            file.s3Bucket,
            file.s3Key,
            IOUtils.toInputStream(content, "UTF-8"),
            metadata
          )
          .leftMap(throw _)
    }
    content
  }

  def randomString(length: Int = 128): String =
    Random.alphanumeric take length mkString

  def getS3File(
    bucket: String,
    key: String
  )(implicit
    c: MigrationContainer
  ): Option[S3Object] =
    c.s3
      .getObject(bucket, key)
      .toOption

  "migration" should "move source files to SPARC bucket" in withContainer(
    config
  ) { implicit container =>
    val pkg = createPackage()

    val source =
      createFile(
        pkg,
        objectType = FileObjectType.Source,
        s3Bucket = storageBucket
      )
    createS3File(source)

    val view =
      createFile(
        pkg,
        objectType = FileObjectType.View,
        processingState = FileProcessingState.NotProcessable,
        s3Bucket = storageBucket
      )
    createS3File(view)

    val file =
      createFile(
        pkg,
        objectType = FileObjectType.File,
        processingState = FileProcessingState.NotProcessable,
        s3Bucket = storageBucket
      )
    createS3File(file)

    MigrateOrganizationStorage.migrate

    source should beInBucket(sparcStorageBucket)

    // Should not move View and File objects
    view should beInBucket(storageBucket)
    file should beInBucket(storageBucket)
  }

  "migration" should "not move files from the upload bucket" in withContainer(
    config
  ) { implicit container =>
    val pkg = createPackage()

    val source =
      createFile(
        pkg,
        objectType = FileObjectType.Source,
        s3Bucket = uploadBucket
      )
    createS3File(source)

    MigrateOrganizationStorage.migrate

    source should beInBucket(uploadBucket)
  }

  "migration" should "ignore DELETING files" in withContainer(config) {
    implicit container =>
      val pkg = createPackage(state = PackageState.DELETING)

      val source =
        createFile(
          pkg,
          objectType = FileObjectType.Source,
          s3Bucket = storageBucket
        )
      createS3File(source)

      MigrateOrganizationStorage.migrate

      source should beInBucket(storageBucket)
  }

  "migration" should "not move files in a different dataset" in withContainer(
    config
  ) { implicit container =>
    val otherDataset = createDataset()
    val pkg = createPackage(dataset = otherDataset)

    val source =
      createFile(
        pkg,
        objectType = FileObjectType.Source,
        s3Bucket = storageBucket
      )
    createS3File(source)

    MigrateOrganizationStorage.migrate

    source should beInBucket(storageBucket)
  }

  "migration" should "move files in all datasets" in withContainer(
    config.withValue("dataset_id", ConfigValueFactory.fromAnyRef("ALL"))
  ) { implicit container =>
    val pkg1 = createPackage(dataset = testDataset)
    val source1 =
      createFile(
        pkg1,
        objectType = FileObjectType.Source,
        s3Bucket = storageBucket
      )
    createS3File(source1)

    val otherDataset = createDataset()
    val pkg2 = createPackage(dataset = otherDataset)
    val source2 =
      createFile(
        pkg2,
        objectType = FileObjectType.Source,
        s3Bucket = storageBucket
      )
    createS3File(source2)

    MigrateOrganizationStorage.migrate

    source1 should beInBucket(sparcStorageBucket)
    source2 should beInBucket(sparcStorageBucket)
  }

  "migration" should "preserve object metadata" in withContainer(config) {
    implicit container =>
      val pkg = createPackage()

      val source =
        createFile(
          pkg,
          objectType = FileObjectType.Source,
          s3Bucket = storageBucket
        )

      createS3File(source, metadata = Some({
        val metadata = new ObjectMetadata()
        metadata.addUserMetadata("chunk-size", "41943040")
        metadata
      }))

      MigrateOrganizationStorage.migrate

      source should beInBucket(sparcStorageBucket)

      getS3File(sparcStorageBucket, source.s3Key).get
        .getObjectMetadata()
        .getUserMetaDataOf("chunk-size") shouldBe "41943040"
  }

  "dry run " should "not move source files to SPARC bucket" in withContainer(
    config.withValue("dry_run", ConfigValueFactory.fromAnyRef(true))
  ) { implicit container =>
    val pkg = createPackage()

    val source =
      createFile(
        pkg,
        objectType = FileObjectType.Source,
        s3Bucket = storageBucket
      )
    createS3File(source)

    MigrateOrganizationStorage.migrate

    source should beInBucket(storageBucket)
  }

  "migration" should "use multipart copy for large objects and preserve metadata" in withContainer(
    // 5 MB limit
    config.withValue(
      "multipart_part_size",
      ConfigValueFactory.fromAnyRef(5 * 1024 * 1024)
    )
  ) { implicit container =>
    val pkg = createPackage()

    val source =
      createFile(
        pkg,
        objectType = FileObjectType.Source,
        s3Bucket = storageBucket
      )

    // 21 MB data
    val content =
      createS3File(source, randomString(21 * 1024 * 1024))

    MigrateOrganizationStorage.migrate

    source should beInBucket(sparcStorageBucket)

    getS3File(sparcStorageBucket, source.s3Key)
      .map(_.getObjectContent)
      .map(scala.io.Source.fromInputStream(_).mkString)
      .get shouldBe content
  }

  "migration" should "handle multiple packages that point to same source file" in withContainer(
    config
  ) { implicit container =>
    val pkg = createPackage()
    val duplicatePkg = createPackage()

    val source =
      createFile(
        pkg,
        objectType = FileObjectType.Source,
        s3Bucket = storageBucket
      )
    createS3File(source)

    val duplicateSource =
      createFile(
        duplicatePkg,
        objectType = FileObjectType.Source,
        s3Bucket = storageBucket,
        s3Key = source.s3Key
      )

    MigrateOrganizationStorage.migrate

    source should beInBucket(sparcStorageBucket)
    duplicateSource should beInBucket(sparcStorageBucket)
  }

  "migration" should "aggregate missing S3 files and fail" in withContainer(
    config
  ) { implicit container =>
    val pkg = createPackage()

    val source =
      createFile(
        pkg,
        objectType = FileObjectType.Source,
        s3Bucket = storageBucket
      )

    val caught = intercept[MissingFiles] {
      MigrateOrganizationStorage.migrate
    }

    caught.files shouldBe List(source)
  }

  "migration" should "ignore children of deleted packages that are missing S3 files" in withContainer(
    config
  ) { implicit container =>
    /**
      * Pathological case here. I think this situation could be introduced by a
      * failure in the delete job: a package is a descendant of a DELETING
      * collection, the S3 assets for a package do not exist, but the package
      * still exists in Postgres. Should be safe to ignore the file and leave it
      * to be cleaned up by https://app.clickup.com/t/2r78w6
      */
    val greatGrandParent = createPackage(`type` = PackageType.Collection)

    val grandParent = createPackage(
      `type` = PackageType.Collection,
      parent = Some(greatGrandParent),
      state = PackageState.DELETING
    )

    val pkgParent =
      createPackage(`type` = PackageType.Collection, parent = Some(grandParent))

    val pkg = createPackage(parent = Some(pkgParent))
    val source =
      createFile(
        pkg,
        objectType = FileObjectType.Source,
        s3Bucket = storageBucket
      )

    // Not deleting
    val otherChild = createPackage(parent = Some(greatGrandParent))
    val otherSource =
      createFile(
        pkg,
        objectType = FileObjectType.Source,
        s3Bucket = storageBucket
      )
    createS3File(otherSource)

    // No object in S3

    MigrateOrganizationStorage.migrate

    // Don't update the database for the deleting file

    fileManager(testOrganization, superAdmin)
      .get(source.id, pkg)
      .await
      .right
      .get
      .s3Bucket shouldBe storageBucket

    // Other file should migrate normally

    otherSource should beInBucket(sparcStorageBucket)
  }

  /**
    * Custom ScalaTest matcher to check the location of files on S3 and in Postgres
    */
  class FileStorageBucketMatcher(
    expectedBucket: String
  )(implicit
    c: MigrationContainer
  ) extends Matcher[File] {

    def apply(f: File) = {

      // Refresh file to get updated bucket location, if any
      val fm = fileManager(testOrganization, superAdmin)
      val pm = packageManager(testOrganization, superAdmin)
      val pkg = pm.db.run(pm.packagesMapper.getPackage(f.packageId)).await
      val updatedFile = fm.get(f.id, pkg).await.right.get

      if (updatedFile.s3Bucket != expectedBucket)
        MatchResult(
          false,
          s"""File ${f.name} was not in "$expectedBucket" in Postgres""",
          s"""File ${f.name} was in "$expectedBucket" in Postgres"""
        )
      else if (getS3File(updatedFile.s3Bucket, updatedFile.s3Key).isEmpty)
        MatchResult(
          false,
          s"""File ${f.name} does not exist in "$expectedBucket" on S3""",
          s"""File ${f.name} exists in "$expectedBucket" on S3"""
        )
      else if (getS3File(
          oppositeBucket(updatedFile.s3Bucket),
          updatedFile.s3Key
        ).isDefined)
        MatchResult(
          false,
          s"""File ${f.name} should not exist in "${oppositeBucket(
            expectedBucket
          )} but it does"""",
          s"""File ${f.name} does not exist in "${oppositeBucket(expectedBucket)} as expected""""
        )
      else
        MatchResult(
          true,
          s"""File ${f.name} was not moved to "$expectedBucket"""",
          s"""File ${f.name} was successfully moved to "$expectedBucket""""
        )
    }

    def oppositeBucket(bucket: String): String =
      if (bucket == storageBucket) sparcStorageBucket else storageBucket
  }

  def beInBucket(expectedBucket: String)(implicit c: MigrationContainer) =
    new FileStorageBucketMatcher(expectedBucket)

}
