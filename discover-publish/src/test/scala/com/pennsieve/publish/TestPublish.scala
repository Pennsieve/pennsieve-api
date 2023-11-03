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

package com.pennsieve.publish

import akka.stream.scaladsl.Keep
import akka.stream.testkit.scaladsl.TestSink
import akka.actor.ActorSystem
import akka.stream.scaladsl.{ Sink, Source }
import cats.data.EitherT
import cats.implicits._
import com.amazonaws.services.s3.model.{ Bucket, S3ObjectSummary }
import com.pennsieve.clients.{ DatasetAssetClient, S3DatasetAssetClient }
import com.pennsieve.aws.s3.S3
import com.pennsieve.core.utilities._
import com.pennsieve.domain.{ CoreError, ServiceError }
import com.pennsieve.models._
import com.pennsieve.publish.models.{ CopyAction, DeleteAction, KeepAction }
import com.pennsieve.test._
import com.pennsieve.test.helpers._
import org.scalatest.EitherValues._
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.utilities.Container
import com.typesafe.config.{ Config, ConfigFactory }
import io.circe.parser.decode
import io.circe.syntax._

import java.time.LocalDate
import org.apache.commons.io.IOUtils
import org.scalatest.{ Assertion, BeforeAndAfterAll, BeforeAndAfterEach, Suite }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.jdk.CollectionConverters._
import scala.collection.mutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try
import java.io.InputStream

case class InsecureDatabaseContainer(config: Config, organization: Organization)
    extends Container
    with DatabaseContainer
    with UserManagerContainer
    with OrganizationContainer
    with DatasetMapperContainer
    with DatasetAssetsContainer
    with PackagesMapperContainer {
  override val postgresUseSSL = false
}

class TestPublish
    extends AnyWordSpec
    with Matchers
    with PersistantTestContainers
    with S3DockerContainer
    with PostgresDockerContainer
    with TestDatabase
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with ValueHelper {
  self: Suite =>

  implicit var system: ActorSystem = _
  implicit var executionContext: ExecutionContext = _

  implicit var s3: S3 = _
  var bucket: Bucket = _

  val testOrganization: Organization = sampleOrganization

  var testDataset: Dataset = _
  var testUser: User = _

  val owner: PublishedContributor =
    PublishedContributor(
      first_name = "Shigeru",
      middle_initial = None,
      last_name = "Miyamoto",
      degree = None,
      orcid = Some("0000-0001-0221-1986")
    )

  var config: Config = _
  var databaseContainer: InsecureDatabaseContainer = _
  implicit var publishContainer: PublishContainer = _
  var embargoContainer: PublishContainer = _
  var datasetAssetClient: DatasetAssetClient = _

  /**
    * Run prior to publishAssets to clean `${container.s3Bucket}/${container.s3Key}` of existing objects before
    * starting the publishing process. This is used to simulate discover-s3clean behavior prior to the discover-publish
    * step function workflow being invoked.
    *
    * @param container
    * @param executionContext
    * @param system
    * @return
    */
  def s3PrePublishCleanObjects(
    container: PublishContainer
  )(implicit
    executionContext: ExecutionContext,
    system: ActorSystem
  ): EitherT[Future, CoreError, Unit] = {
    container.s3
      .deleteObjectsByPrefix(container.s3Bucket, container.s3Key)
      .toEitherT[Future]
      .leftMap[CoreError](e => ServiceError(e.getMessage))
  }

  override def afterStart(): Unit = {
    super.afterStart()

    // alpakka-s3 v1.0 can only be configured via Typesafe config passed to the
    // actor system, or as S3Settings that are attached to every graph
    config = ConfigFactory
      .empty()
      .withFallback(postgresContainer.config)
      .withFallback(s3Container.config)
      .withFallback(ConfigFactory.load())

    system = ActorSystem("discover-publish", config)
    executionContext = system.dispatcher

    /*
     * Since PublishContainer is scoped to an organization, and requires a
     * user-actor, use a simple database container to set up initial conditions.
     */
    databaseContainer = InsecureDatabaseContainer(config, testOrganization)
    databaseContainer.db.run(createSchema(testOrganization.id.toString)).await
    migrateOrganizationSchema(
      testOrganization.id,
      databaseContainer.postgresDatabase
    )

    s3 = new S3(s3Container.s3Client)
  }

  override def beforeEach(): Unit = {
    super.beforeEach()

    datasetAssetClient = new S3DatasetAssetClient(s3, assetBucket)

    s3.createBucket(publishBucket).isRight shouldBe true
    s3.createBucket(embargoBucket).isRight shouldBe true
    s3.createBucket(assetBucket).isRight shouldBe true
    s3.createBucket(sourceBucket).isRight shouldBe true

    testUser = createUser(databaseContainer)
    testDataset = createDatasetWithAssets(
      databaseContainer = databaseContainer,
      s3 = Some(s3)
    )

    publishContainer = {
      PublishContainer(
        config = config,
        s3 = s3,
        s3Bucket = publishBucket,
        s3AssetBucket = assetBucket,
        s3Key = testKey,
        s3AssetKeyPrefix = assetKeyPrefix,
        s3CopyChunkSize = copyChunkSize,
        s3CopyChunkParallelism = copyParallelism,
        s3CopyFileParallelism = copyParallelism,
        doi = testDoi,
        dataset = testDataset,
        publishedDatasetId = 100,
        version = 10,
        organization = testOrganization,
        user = ownerUser,
        userOrcid = "0000-0001-0221-1986",
        datasetRole = Some(Role.Owner),
        contributors = List(contributor),
        collections = List(collection),
        externalPublications = List(externalPublication),
        datasetAssetClient = datasetAssetClient,
        workflowId = PublishingWorkflows.Version4
      )
    }

    embargoContainer = {
      PublishContainer(
        config = config,
        s3 = s3,
        s3Bucket = embargoBucket,
        s3AssetBucket = assetBucket,
        s3Key = testKey,
        s3AssetKeyPrefix = assetKeyPrefix,
        s3CopyChunkSize = copyChunkSize,
        s3CopyChunkParallelism = copyParallelism,
        s3CopyFileParallelism = copyParallelism,
        doi = testDoi,
        dataset = testDataset,
        publishedDatasetId = 100,
        version = 10,
        organization = testOrganization,
        user = ownerUser,
        userOrcid = "0000-0001-0221-1986",
        datasetRole = Some(Role.Owner),
        contributors = List(contributor),
        collections = List(collection),
        externalPublications = List(externalPublication),
        datasetAssetClient = datasetAssetClient,
        workflowId = PublishingWorkflows.Version4
      )
    }

    // Simulate discover-s3clean:
    s3PrePublishCleanObjects(publishContainer)
    s3PrePublishCleanObjects(embargoContainer)
  }

  override def afterEach(): Unit = {
    super.afterEach()
    deleteBucket(publishBucket)
    deleteBucket(embargoBucket)
    deleteBucket(assetBucket)
    deleteBucket(sourceBucket)
    publishContainer.db.close()
  }

  override def afterAll(): Unit = {
    databaseContainer.db.close()
    system.terminate()
    super.afterAll()
  }

  def splitMultiLinesString(s: String): Seq[String] =
    s.split("\n")
      .toSeq
      .map(_.trim)
      .filter(_ != "")

  "publish job" should {

    "decode all versions of datasetMetadata" in {

      val sampleMetadataV1 =
        """{
        "pennsieveDatasetId": 1,
        "version": 1,
        "name" : "Test Dataset",
        "description" : "Lorem ipsum",
        "creator" : "Blaise Pascal",
        "contributors" : [
           "Isaac Newton",
           "Albert Einstein"
        ],
        "sourceOrganization" : "1",
        "keywords" : [
        "neuro",
        "neuron"
        ],
        "datePublished": "2019-06-05",
        "license": "MIT",
        "@id": "10.21397/jlt1-xdqn",
        "publisher" : "The University of Pennsylvania",
        "@context" : "http://purl.org/dc/terms",
        "@type":"Dataset",
        "schemaVersion": "http://schema.org/version/3.7/",
        "files" : [
          {
            "path" : "packages/brain.dcm",
            "size" : 15010,
            "fileType" : "DICOM",
            "sourcePackageId" : "N:package:1"
          }
        ],
        "pennsieveSchemaVersion" : 1
        }"""

      val mdV1 = DatasetMetadataV1_0(
        pennsieveDatasetId = 1,
        version = 1,
        name = "Test Dataset",
        description = "Lorem ipsum",
        creator = "Blaise Pascal",
        contributors = List("Isaac Newton", "Albert Einstein"),
        sourceOrganization = "1",
        keywords = List("neuro", "neuron"),
        datePublished = LocalDate.of(2019, 6, 5),
        license = Some(License.MIT),
        `@id` = "10.21397/jlt1-xdqn",
        `@context` = "http://purl.org/dc/terms",
        files = List(
          FileManifest(
            "packages/brain.dcm",
            15010,
            FileType.DICOM,
            Some("N:package:1")
          )
        ),
        pennsieveSchemaVersion = 1
      )

      decode[DatasetMetadata](sampleMetadataV1) shouldBe Right(mdV1)

      val sampleMetadataV2 =
        """{
        "pennsieveDatasetId": 1,
        "version": 1,
        "name" : "Test Dataset",
        "description" : "Lorem ipsum",
        "creator" : "Blaise Pascal",
        "contributors" : [  { "first_name": "Isaac", "last_name": "Newton"}, { "first_name": "Albert", "last_name": "Einstein"}],
        "sourceOrganization" : "1",
        "keywords" : [
        "neuro",
        "neuron"
        ],
        "datePublished": "2019-06-05",
        "license": "MIT",
        "@id": "10.21397/jlt1-xdqn",
        "publisher" : "The University of Pennsylvania",
        "@context" : "http://purl.org/dc/terms",
        "@type":"Dataset",
        "schemaVersion": "http://schema.org/version/3.7/",
        "files" : [
          {
            "path" : "packages/brain.dcm",
            "size" : 15010,
            "fileType" : "DICOM",
            "sourcePackageId" : "N:package:1"
          }
        ],
        "pennsieveSchemaVersion" : 2
        }"""

      val mdV2 = DatasetMetadataV2_0(
        pennsieveDatasetId = 1,
        version = 1,
        name = "Test Dataset",
        description = "Lorem ipsum",
        creator = "Blaise Pascal",
        contributors = List(
          PublishedContributor("Isaac", "Newton", None),
          PublishedContributor("Albert", "Einstein", None)
        ),
        sourceOrganization = "1",
        keywords = List("neuro", "neuron"),
        datePublished = LocalDate.of(2019, 6, 5),
        license = Some(License.MIT),
        `@id` = "10.21397/jlt1-xdqn",
        `@context` = "http://purl.org/dc/terms",
        files = List(
          FileManifest(
            "packages/brain.dcm",
            15010,
            FileType.DICOM,
            Some("N:package:1")
          )
        ),
        pennsieveSchemaVersion = 2
      )

      decode[DatasetMetadata](sampleMetadataV2) shouldBe Right(mdV2)

      val sampleMetadataV3 =
        """{
        "pennsieveDatasetId": 1,
        "version": 1,
        "name" : "Test Dataset",
        "description" : "Lorem ipsum",
        "creator" : { "first_name": "Blaise", "last_name": "Pascal", "orcid": "0000-0009-1234-5678"},
        "contributors" : [  { "first_name": "Isaac", "last_name": "Newton"}, { "first_name": "Albert", "last_name": "Einstein"}],
        "sourceOrganization" : "1",
        "keywords" : [
        "neuro",
        "neuron"
        ],
        "datePublished": "2019-06-05",
        "license": "MIT",
        "@id": "10.21397/jlt1-xdqn",
        "publisher" : "The University of Pennsylvania",
        "@context" : "http://purl.org/dc/terms",
        "@type":"Dataset",
        "schemaVersion": "http://schema.org/version/3.7/",
        "files" : [
          {
            "path" : "packages/brain.dcm",
            "size" : 15010,
            "fileType" : "DICOM",
            "sourcePackageId" : "N:package:1"
          }
        ],
        "pennsieveSchemaVersion" : "3.0"
        }"""

      val mdV3 = DatasetMetadataV3_0(
        pennsieveDatasetId = 1,
        version = 1,
        name = "Test Dataset",
        description = "Lorem ipsum",
        creator =
          PublishedContributor("Blaise", "Pascal", Some("0000-0009-1234-5678")),
        contributors = List(
          PublishedContributor("Isaac", "Newton", None),
          PublishedContributor("Albert", "Einstein", None)
        ),
        sourceOrganization = "1",
        keywords = List("neuro", "neuron"),
        datePublished = LocalDate.of(2019, 6, 5),
        license = Some(License.MIT),
        `@id` = "10.21397/jlt1-xdqn",
        `@context` = "http://purl.org/dc/terms",
        files = List(
          FileManifest(
            "packages/brain.dcm",
            15010,
            FileType.DICOM,
            Some("N:package:1")
          )
        ),
        pennsieveSchemaVersion = "3.0"
      )

      decode[DatasetMetadata](sampleMetadataV3) shouldBe Right(mdV3)

      val sampleMetadataV4 =
        """{
        "pennsieveDatasetId": 1,
        "version": 1,
        "revision": 1,
        "name" : "Test Dataset",
        "description" : "Lorem ipsum",
        "creator" : { "first_name": "Blaise", "last_name": "Pascal", "orcid": "0000-0009-1234-5678"},
        "contributors" : [  { "first_name": "Isaac", "last_name": "Newton"}, { "first_name": "Albert", "last_name": "Einstein"}],
        "sourceOrganization" : "1",
        "keywords" : [
        "neuro",
        "neuron"
        ],
        "datePublished": "2019-06-05",
        "license": "MIT",
        "@id": "10.21397/jlt1-xdqn",
        "publisher" : "The University of Pennsylvania",
        "@context" : "http://purl.org/dc/terms",
        "@type":"Dataset",
        "schemaVersion": "http://schema.org/version/3.7/",
        "collections" : [
          {
            "name" : "My great collection"
          }
        ],
        "relatedPublications" : [
          {
            "doi" : "10.26275/t6j6-77pu",
            "relationshipType" : "IsDescribedBy"
          }
        ],
        "files" : [
          {
            "path" : "packages/brain.dcm",
            "size" : 15010,
            "fileType" : "DICOM",
            "sourcePackageId" : "N:package:1"
          }
        ],
        "pennsieveSchemaVersion" : "4.0"
        }"""

      val mdV4 = DatasetMetadataV4_0(
        pennsieveDatasetId = 1,
        version = 1,
        revision = Some(1),
        name = "Test Dataset",
        description = "Lorem ipsum",
        creator =
          PublishedContributor("Blaise", "Pascal", Some("0000-0009-1234-5678")),
        contributors = List(
          PublishedContributor("Isaac", "Newton", None),
          PublishedContributor("Albert", "Einstein", None)
        ),
        sourceOrganization = "1",
        keywords = List("neuro", "neuron"),
        datePublished = LocalDate.of(2019, 6, 5),
        license = Some(License.MIT),
        `@id` = "10.21397/jlt1-xdqn",
        `@context` = "http://purl.org/dc/terms",
        files = List(
          FileManifest(
            "packages/brain.dcm",
            15010,
            FileType.DICOM,
            Some("N:package:1")
          )
        ),
        collections = Some(List(PublishedCollection("My great collection"))),
        relatedPublications = Some(
          List(
            PublishedExternalPublication(
              Doi("10.26275/t6j6-77pu"),
              Some(RelationshipType.IsDescribedBy)
            )
          )
        )
      )

      decode[DatasetMetadata](sampleMetadataV4) shouldBe Right(mdV4)
    }

    "encode contributors as objects" in {
      val contributors =
        s"""[{"id":1,"first_name":"Sally","last_name":"Mae","orcid":null}]"""
      decode[List[PublishedContributor]](contributors) shouldBe Right(
        List(PublishedContributor("Sally", "Mae", None))
      )
    }

    "ensure that prefix deletion works as expected" in {

      createS3File(s3, embargoBucket, s"2/11/foo")
      createS3File(s3, embargoBucket, s"2/11/bar")
      createS3File(s3, embargoBucket, s"2/11/sub/baz")
      createS3File(s3, embargoBucket, s"2/11/another-key/quux")

      // Make sure deletion doesn't happen for partial prefix keys:
      s3.deleteObjectsByPrefix(embargoBucket, "2/1")
      // No files should exist under key prefix "2/1":
      assert(!s3FilesExistUnderKey(embargoBucket, "2/1"))

      // Checking deleting files from a sub key:
      assert(s3FilesExistUnderKey(embargoBucket, "2/11/sub"))
      s3.deleteObjectsByPrefix(embargoBucket, "2/11/sub")
      assert(!s3FilesExistUnderKey(embargoBucket, "2/11/sub"))

      // Check deleting a specific file:
      assert(s3FilesExistUnderKey(embargoBucket, "2/11/foo", matchExact = true))
      s3.deleteObject(embargoBucket, "2/11/foo")
      assert(
        !s3FilesExistUnderKey(embargoBucket, "2/11/foo", matchExact = true)
      )

      // Finally, make sure we can delete everything under "2/11":
      assert(s3FilesExistUnderKey(embargoBucket, "2/11"))
      s3.deleteObjectsByPrefix(embargoBucket, "2/11")
      assert(!s3FilesExistUnderKey(embargoBucket, "2/11"))
    }

    "succeed with an empty dataset" in {
      Publish.publishAssets(publishContainer).await.isRight shouldBe true
      assert(
        Try(
          downloadFile(publishBucket, testKey + Publish.PUBLISH_ASSETS_FILENAME)
        ).toEither.isRight
      )

      runModelPublish(publishBucket, testKey)

      Publish.finalizeDataset(publishContainer).await shouldBe Right(())
//      // Ensure all temporary files should be deleted by now:
//      for (tempFile <- Publish.temporaryFiles) {
//        assert(
//          Try(downloadFile(publishBucket, testKey + tempFile)).toEither.isLeft
//        )
//      }
    }

    "create metadata, package objects and public assets in S3 (publish bucket)" in {

      // everything under `testKey` should be gone:
      assert(!s3FilesExistUnderKey(publishBucket, testKey))
      assert(!s3FilesExistUnderKey(embargoBucket, testKey))

      // seed the publish bucket:
      createS3File(
        s3,
        publishBucket,
        s"${testKey}/delete-prefix-key/delete-file.txt"
      )
      createS3File(
        s3,
        publishBucket,
        s"${testKey}/some-other-prefix/sub-key/another-file.txt"
      )

      // Package with a single file
      val pkg1 = createPackage(testUser, name = "pkg1")
      val file1 = createFile(
        pkg1,
        name = "file1",
        s3Key = "key/file.txt",
        content = "data data",
        size = 1234
      )

      // Package with multiple file sources
      val pkg2 = createPackage(testUser, name = "pkg2")
      val file2 = createFile(
        pkg2,
        name = "file2",
        s3Key = "key/file2.dcm",
        content = "atad atad",
        size = 2222,
        fileType = FileType.DICOM
      )
      val file3 = createFile(
        pkg2,
        name = "file3",
        s3Key = "key/file3.dcm",
        content = "double data",
        size = 3333,
        fileType = FileType.DICOM
      )

      Publish.publishAssets(publishContainer).await.isRight shouldBe true
      val assetsJson = Try(
        downloadFile(publishBucket, testKey + Publish.PUBLISH_ASSETS_FILENAME)
      ).toEither
      assert(assetsJson.isRight)

      runModelPublish(publishBucket, testKey)

      // Finalizing the jobs should write an `output.json` and delete all other
      // temporary files
      Publish.finalizeDataset(publishContainer).await shouldBe Right(())
      assert(
        Try(downloadFile(publishBucket, testKey + "outputs.json")).toEither.isRight
      )
//      assert(
//        Try(downloadFile(publishBucket, testKey + "publish.json")).toEither.isLeft
//      )
//      assert(
//        Try(downloadFile(publishBucket, testKey + "graph.json")).toEither.isLeft
//      )

      // should write a temp results file to publish bucket
      val tempResults = decode[Publish.TempPublishResults](
        downloadFile(publishBucket, testKey + "outputs.json")
      ).value
      tempResults.readmeKey shouldBe testKey + Publish.README_FILENAME
      tempResults.bannerKey shouldBe testKey + Publish.BANNER_FILENAME
      tempResults.changelogKey shouldBe testKey + Publish.CHANGELOG_FILENAME
      tempResults.totalSize > 0 shouldBe true

      // should export package and graph files to publish bucket
      downloadFile(publishBucket, testKey + "files/pkg1.txt") shouldBe "data data"
      downloadFile(publishBucket, testKey + "files/pkg2/file2.dcm") shouldBe "atad atad"
      downloadFile(publishBucket, testKey + "files/pkg2/file3.dcm") shouldBe "double data"

      val schemaJson =
        downloadFile(publishBucket, testKey + "metadata/schema.json")

      val bannerJpg =
        downloadFile(publishBucket, testKey + s"/${Publish.BANNER_FILENAME}")
      bannerJpg shouldBe "banner-data"
      val readmeMarkdown =
        downloadFile(publishBucket, testKey + s"/${Publish.README_FILENAME}")
      readmeMarkdown shouldBe "readme-data"
      val changelogMarkdown =
        downloadFile(publishBucket, testKey + s"/${Publish.CHANGELOG_FILENAME}")
      changelogMarkdown shouldBe "changelog-data"

      // should write assets to public discover bucket
      downloadFile(
        assetBucket,
        "dataset-assets/" + testKey + Publish.BANNER_FILENAME
      ) shouldBe "banner-data"
      downloadFile(
        assetBucket,
        "dataset-assets/" + testKey + Publish.README_FILENAME
      ) shouldBe "readme-data"

      val metadata =
        downloadFile(publishBucket, testKey + Publish.METADATA_FILENAME)

      decode[DatasetMetadata](metadata) shouldBe Right(
        DatasetMetadataV4_0(
          pennsieveDatasetId = 100,
          version = 10,
          revision = None,
          name = testDataset.name,
          description = "description",
          creator = owner,
          contributors = List(contributor),
          sourceOrganization = testOrganization.name,
          keywords = testDataset.tags,
          datePublished = LocalDate.now(),
          license = testDataset.license,
          `@id` = s"https://doi.org/$testDoi",
          collections = Some(List(collection)),
          relatedPublications = Some(List(externalPublication)),
          files = List(
            FileManifest(
              Publish.CHANGELOG_FILENAME,
              Publish.CHANGELOG_FILENAME,
              changelogMarkdown.length,
              FileType.Markdown
            ),
            FileManifest(
              Publish.BANNER_FILENAME,
              Publish.BANNER_FILENAME,
              bannerJpg.length,
              FileType.JPEG
            ),
            FileManifest(
              Publish.METADATA_FILENAME,
              Publish.METADATA_FILENAME,
              metadata.getBytes("utf-8").length,
              FileType.Json
            ),
            FileManifest(
              "file2",
              "files/pkg2/file2.dcm",
              2222,
              FileType.DICOM,
              Some(pkg2.nodeId)
            ),
            FileManifest(
              "file3",
              "files/pkg2/file3.dcm",
              3333,
              FileType.DICOM,
              Some(pkg2.nodeId)
            ),
            FileManifest(
              "file1",
              "files/pkg1.txt",
              1234,
              FileType.Text,
              Some(pkg1.nodeId)
            ),
            FileManifest(
              Publish.README_FILENAME,
              Publish.README_FILENAME,
              readmeMarkdown.length,
              FileType.Markdown
            ),
            FileManifest(
              "schema.json",
              "metadata/schema.json",
              schemaJson.length,
              FileType.Json
            )
          ).sorted,
          pennsieveSchemaVersion = "4.0"
        )
      )
    }

    "create metadata, package objects and public assets in S3 (embargo bucket)" in {
      // everything under `testKey` should be gone:
      assert(!s3FilesExistUnderKey(publishBucket, testKey))
      assert(!s3FilesExistUnderKey(embargoBucket, testKey))

      // seed the embargo bucket:
      createS3File(
        s3,
        embargoBucket,
        s"${testKey}/delete-prefix-key/delete-file.txt"
      )
      createS3File(
        s3,
        embargoBucket,
        s"${testKey}/some-other-prefix/sub-key/another-file.txt"
      )

      val pkg1 = createPackage(testUser, name = "pkg1")
      val file1 = createFile(
        pkg1,
        name = "file1",
        s3Key = "key/file.txt",
        content = "data data",
        size = 1234
      )
      val pkg2 = createPackage(testUser, name = "pkg2")
      val file2 = createFile(
        pkg2,
        name = "file2",
        s3Key = "key/file2.dcm",
        content = "atad atad",
        size = 2222,
        fileType = FileType.DICOM
      )
      val file3 = createFile(
        pkg2,
        name = "file3",
        s3Key = "key/file3.dcm",
        content = "double data",
        size = 3333,
        fileType = FileType.DICOM
      )

      Publish.publishAssets(embargoContainer).await.isRight shouldBe true
      val assetsJson = Try(
        downloadFile(embargoBucket, testKey + Publish.PUBLISH_ASSETS_FILENAME)
      ).toEither
      assert(assetsJson.isRight)

      runModelPublish(embargoBucket, testKey)

      // Finalizing the jobs should write an `output.json` and delete all other
      // temporary files
      Publish.finalizeDataset(embargoContainer).await shouldBe Right(())
      assert(
        Try(downloadFile(embargoBucket, testKey + "outputs.json")).toEither.isRight
      )
//      assert(
//        Try(downloadFile(embargoBucket, testKey + "publish.json")).toEither.isLeft
//      )
//      assert(
//        Try(downloadFile(embargoBucket, testKey + "graph.json")).toEither.isLeft
//      )

      // should write a temp results file to publish bucket
      val tempResults = decode[Publish.TempPublishResults](
        downloadFile(embargoBucket, testKey + "outputs.json")
      ).value
      tempResults.readmeKey shouldBe testKey + Publish.README_FILENAME
      tempResults.bannerKey shouldBe testKey + Publish.BANNER_FILENAME
      tempResults.changelogKey shouldBe testKey + Publish.CHANGELOG_FILENAME
      tempResults.totalSize > 0 shouldBe true

      // should export package and graph files to publish bucket
      downloadFile(embargoBucket, testKey + "files/pkg1.txt") shouldBe "data data"
      downloadFile(embargoBucket, testKey + "files/pkg2/file2.dcm") shouldBe "atad atad"
      downloadFile(embargoBucket, testKey + "files/pkg2/file3.dcm") shouldBe "double data"

      val schemaJson =
        downloadFile(embargoBucket, testKey + "metadata/schema.json")

      val bannerJpg =
        downloadFile(embargoBucket, testKey + s"/${Publish.BANNER_FILENAME}")
      bannerJpg shouldBe "banner-data"
      val readmeMarkdown =
        downloadFile(embargoBucket, testKey + s"/${Publish.README_FILENAME}")
      readmeMarkdown shouldBe "readme-data"
      val changelogMarkdown =
        downloadFile(embargoBucket, testKey + s"/${Publish.CHANGELOG_FILENAME}")
      changelogMarkdown shouldBe "changelog-data"

      // should write assets to public discover bucket
      downloadFile(
        assetBucket,
        "dataset-assets/" + testKey + Publish.BANNER_FILENAME
      ) shouldBe "banner-data"
      downloadFile(
        assetBucket,
        "dataset-assets/" + testKey + Publish.README_FILENAME
      ) shouldBe "readme-data"

      val metadata =
        downloadFile(embargoBucket, testKey + Publish.METADATA_FILENAME)

      decode[DatasetMetadata](metadata) shouldBe Right(
        DatasetMetadataV4_0(
          pennsieveDatasetId = 100,
          version = 10,
          revision = None,
          name = testDataset.name,
          description = "description",
          creator = owner,
          contributors = List(contributor),
          sourceOrganization = testOrganization.name,
          keywords = testDataset.tags,
          datePublished = LocalDate.now(),
          license = testDataset.license,
          `@id` = s"https://doi.org/$testDoi",
          collections = Some(List(collection)),
          relatedPublications = Some(List(externalPublication)),
          files = List(
            FileManifest(
              Publish.CHANGELOG_FILENAME,
              Publish.CHANGELOG_FILENAME,
              changelogMarkdown.length,
              FileType.Markdown
            ),
            FileManifest(
              Publish.BANNER_FILENAME,
              Publish.BANNER_FILENAME,
              bannerJpg.length,
              FileType.JPEG
            ),
            FileManifest(
              Publish.METADATA_FILENAME,
              Publish.METADATA_FILENAME,
              metadata.getBytes("utf-8").length,
              FileType.Json
            ),
            FileManifest(
              "file2",
              "files/pkg2/file2.dcm",
              2222,
              FileType.DICOM,
              Some(pkg2.nodeId)
            ),
            FileManifest(
              "file3",
              "files/pkg2/file3.dcm",
              3333,
              FileType.DICOM,
              Some(pkg2.nodeId)
            ),
            FileManifest(
              "file1",
              "files/pkg1.txt",
              1234,
              FileType.Text,
              Some(pkg1.nodeId)
            ),
            FileManifest(
              Publish.README_FILENAME,
              Publish.README_FILENAME,
              readmeMarkdown.length,
              FileType.Markdown
            ),
            FileManifest(
              "schema.json",
              "metadata/schema.json",
              schemaJson.length,
              FileType.Json
            )
          ).sorted,
          pennsieveSchemaVersion = "4.0"
        )
      )
    }

    "create metadata, package objects and public assets in S3 with ignored files (publish bucket)" in {

      // Package with a single file
      val pkg1 = createPackage(testUser, name = "pkg1")
      val file1 = createFile(
        pkg1,
        name = "file",
        s3Key = "key/file.txt",
        content = "data data",
        size = 1234
      )

      // Package with multiple file sources
      val pkg2 = createPackage(testUser, name = "pkg2")
      val file2 = createFile(
        pkg2,
        name = "file2",
        s3Key = "key/file2.dcm",
        content = "atad atad",
        size = 2222,
        fileType = FileType.DICOM
      )
      val file3 = createFile(
        pkg2,
        name = "file3",
        s3Key = "key/file3.dcm",
        content = "double data",
        size = 3333,
        fileType = FileType.DICOM
      )
      val pkg3 = createPackage(testUser, name = "pkg3")
      val file4 = createFile(
        pkg3,
        name = "file4",
        s3Key = "key/file4.py",
        content = "lots of code",
        size = 4444,
        fileType = FileType.Python
      )
      val file5 = createFile(
        pkg3,
        name = "file5",
        s3Key = "key/file5.yml",
        content = "triple data",
        size = 5555,
        fileType = FileType.YAML
      )
      val pkg4 = createPackage(testUser, name = "pkg4")
      val file6 = createFile(
        pkg4,
        name = "file6",
        s3Key = "key/file6.pdf",
        content = "research-document",
        size = 6666,
        fileType = FileType.PDF
      )

      val ignoreFiles = setDatasetIgnoreFiles(
        Seq(
          DatasetIgnoreFile(publishContainer.dataset.id, file3.fileName),
          DatasetIgnoreFile(publishContainer.dataset.id, file4.fileName),
          DatasetIgnoreFile(publishContainer.dataset.id, file5.fileName)
        )
      )

      Publish.publishAssets(publishContainer).await.isRight shouldBe true
      runModelPublish(publishBucket, testKey)

      // Finalizing the jobs should write an `output.json` and delete all other
      // temporary files
      Publish.finalizeDataset(publishContainer).await shouldBe Right(())

      // should export package and graph files to publish bucket
      downloadFile(publishBucket, testKey + "files/pkg1.txt") shouldBe "data data"
      downloadFile(publishBucket, testKey + "files/pkg2/file2.dcm") shouldBe "atad atad"
      // should ignore graph file (file3.dcm)
      assert(
        Try(downloadFile(publishBucket, testKey + "files/pkg2/file3.dcm")).toEither.isLeft
      )

      val metadata =
        downloadFile(publishBucket, testKey + Publish.METADATA_FILENAME)

      decode[DatasetMetadata](metadata).map(_.files.map(_.path)) shouldBe Right(
        List(
          Publish.BANNER_FILENAME,
          Publish.CHANGELOG_FILENAME,
          "files/pkg2/file2.dcm",
          Publish.METADATA_FILENAME,
          "files/pkg1.txt",
          "files/pkg4.pdf",
          Publish.README_FILENAME,
          "metadata/schema.json"
        )
      )
    }

    "compute self-aware metadata.json size" in {
      Publish.sizeCountingOwnSize(0) shouldBe 1
      Publish.sizeCountingOwnSize(1) shouldBe 2
      Publish.sizeCountingOwnSize(6) shouldBe 7
      Publish.sizeCountingOwnSize(7) shouldBe 8
      Publish.sizeCountingOwnSize(8) shouldBe 9
      Publish.sizeCountingOwnSize(9) shouldBe 11
      Publish.sizeCountingOwnSize(10) shouldBe 12
      Publish.sizeCountingOwnSize(97) shouldBe 99
      Publish.sizeCountingOwnSize(98) shouldBe 101
      Publish.sizeCountingOwnSize(99) shouldBe 102
      Publish.sizeCountingOwnSize(100) shouldBe 103
      Publish.sizeCountingOwnSize(101) shouldBe 104
      Publish.sizeCountingOwnSize(9995) shouldBe 9999
      Publish.sizeCountingOwnSize(9996) shouldBe 10001
      Publish.sizeCountingOwnSize(9997) shouldBe 10002
    }
  }

  "publish source" should {
    "stream packages in BFS order" in {
      val pkg1 = createPackage(testUser, name = "p1")
      val pkg2 = createPackage(testUser, name = "p2")
      val child1 = createPackage(testUser, parent = Some(pkg1), name = "c1")
      val child2 = createPackage(testUser, parent = Some(pkg1), name = "c2")
      val grandchild1 =
        createPackage(testUser, parent = Some(child1), name = "g1")
      val grandchild2 =
        createPackage(testUser, parent = Some(child2), name = "g2")

      val (_, sink) = PackagesSource()
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(n = 100)
      sink.expectNextUnordered((pkg1, Nil), (pkg2, Nil))
      sink.expectNextUnordered((child1, Seq("p1")), (child2, Seq("p1")))
      sink.expectNextUnordered(
        (grandchild1, Seq("p1", "c1")),
        (grandchild2, Seq("p1", "c2"))
      )
      sink.expectComplete()
    }

    "handle no packages" in {
      val (_, sink) = PackagesSource()
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(n = 100)
      sink.expectComplete()
    }
  }

  "file sources flow" should {
    "emit copy requests" in {
      val pkg = createPackage(testUser, name = "pkg")
      val file1 = createFile(pkg, name = "file1", s3Key = "key/file1.txt")
      val file2 = createFile(pkg, name = "file2", s3Key = "key/file2.txt")

      val (_, sink) = Source
        .single((pkg, Seq("p1", "c1")))
        .via(BuildCopyRequests())
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(n = 100)
      sink.expectNextUnordered(
        CopyAction(
          pkg,
          file1,
          publishBucket,
          testKey,
          s"files/p1/c1/pkg/file1.txt",
          s"files/p1/c1/pkg"
        ),
        CopyAction(
          pkg,
          file2,
          publishBucket,
          testKey,
          s"files/p1/c1/pkg/file2.txt",
          s"files/p1/c1/pkg"
        )
      )
      sink.expectComplete()
    }

    "ignore non-source files" in {
      val pkg = createPackage(testUser)
      val file1 =
        createFile(
          pkg,
          objectType = FileObjectType.View,
          processingState = FileProcessingState.NotProcessable
        )
      val file2 =
        createFile(
          pkg,
          objectType = FileObjectType.File,
          processingState = FileProcessingState.NotProcessable
        )

      val (_, sink) = Source
        .single((pkg, Seq("p1", "c1")))
        .via(BuildCopyRequests())
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(n = 100)
      sink.expectComplete()
    }

    "ignore pending files" in {
      val pkg = createPackage(testUser)
      val file1 =
        createFile(
          pkg,
          name = "file1",
          s3Key = "key/file1.txt",
          uploadedState = Some(FileState.PENDING)
        )

      val (_, sink) = Source
        .single((pkg, Seq("p1", "c1")))
        .via(BuildCopyRequests())
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(n = 100)
      sink.expectComplete()
    }

    "ignore scanning files" in {
      val pkg = createPackage(testUser)
      val file1 =
        createFile(
          pkg,
          name = "file1",
          s3Key = "key/file1.txt",
          uploadedState = Some(FileState.SCANNING)
        )

      val (_, sink) = Source
        .single((pkg, Seq("p1", "c1")))
        .via(BuildCopyRequests())
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(n = 100)
      sink.expectComplete()
    }

    "include uploaded and no state files" in {

      val pkg = createPackage(testUser, name = "pkg")
      val file1 = createFile(
        pkg,
        name = "file1",
        s3Key = "key/file1.txt",
        uploadedState = Some(FileState.UPLOADED)
      )
      val file2 = createFile(
        pkg,
        name = "file2",
        s3Key = "key/file2.txt",
        uploadedState = None
      )

      val (_, sink) = Source
        .single((pkg, Seq("p1", "c1")))
        .via(BuildCopyRequests())
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(n = 100)
      sink.expectNextUnordered(
        CopyAction(
          pkg,
          file1,
          publishBucket,
          testKey,
          s"files/p1/c1/pkg/file1.txt",
          s"files/p1/c1/pkg"
        ),
        CopyAction(
          pkg,
          file2,
          publishBucket,
          testKey,
          s"files/p1/c1/pkg/file2.txt",
          s"files/p1/c1/pkg"
        )
      )
      sink.expectComplete()

    }

    "rename a single-source file with the package name and the file extension" in {
      val pkg = createPackage(testUser, name = "My Notes")

      val file =
        createFile(pkg, name = "notes", s3Key = "key/2524423/notes.txt")

      BuildCopyRequests
        .buildCopyActions(pkg, Seq("grandparent", "parent"), Seq(file)) shouldBe Seq(
        CopyAction(
          pkg,
          file,
          publishBucket,
          testKey,
          s"files/grandparent/parent/My_Notes.txt",
          s"files/grandparent/parent/My_Notes.txt"
        )
      )
    }

    "ensure the package extension is used if one is present" in {
      val pkg = createPackage(testUser, name = "My Notes.text")
      val file =
        createFile(pkg, name = "notes", s3Key = "key/2524423/notes.txt")

      BuildCopyRequests
        .buildCopyActions(pkg, Seq("grandparent", "parent"), Seq(file)) shouldBe Seq(
        CopyAction(
          pkg,
          file,
          publishBucket,
          testKey,
          s"files/grandparent/parent/My_Notes.text",
          s"files/grandparent/parent/My_Notes.text"
        )
      )
    }

    "place multiple sources inside a directory with the package name" in {
      val pkg = createPackage(testUser, name = "Brain stuff")
      val file1 = createFile(
        pkg,
        name = "brain_01",
        s3Key = "key/brain_01.dcm",
        fileType = FileType.DICOM
      )
      val file2 = createFile(
        pkg,
        name = "brain_02",
        s3Key = "key/brain_02.dcm",
        fileType = FileType.DICOM
      )

      BuildCopyRequests
        .buildCopyActions(pkg, Seq("grandparent", "parent"), Seq(file1, file2)) shouldBe Seq(
        CopyAction(
          pkg,
          file1,
          publishBucket,
          testKey,
          s"files/grandparent/parent/Brain_stuff/brain_01.dcm",
          s"files/grandparent/parent/Brain_stuff"
        ),
        CopyAction(
          pkg,
          file2,
          publishBucket,
          testKey,
          s"files/grandparent/parent/Brain_stuff/brain_02.dcm",
          s"files/grandparent/parent/Brain_stuff"
        )
      )
    }

    "generate package ID map" in {
      // Multiple sources - point to package directory
      val pkg1 = createPackage(testUser, name = "Brain stuff")
      val file11 = createFile(pkg1, name = "brain_01.dcm")
      val file12 = createFile(pkg1, name = "brain_02.dcm")

      // Single source - point to source file
      val pkg2 = createPackage(testUser, name = "p2")
      val file21 = createFile(pkg2, name = "Notes.txt")

      val (idMap, _) =
        PackagesExport.exportPackageSources(publishContainer).await

      idMap shouldBe Map(
        ExternalId.nodeId(pkg1.nodeId) -> "files/Brain_stuff",
        ExternalId.intId(pkg1.id) -> "files/Brain_stuff",
        ExternalId.nodeId(pkg2.nodeId) -> "files/p2.txt",
        ExternalId.intId(pkg2.id) -> "files/p2.txt"
      )
    }
  }

  "copy S3 files sink" should {
    "execute copy requests" in {
      val pkg = createPackage(testUser)
      val file = createFile(pkg, content = "some content")

      Source
        .single(
          CopyAction(
            pkg,
            file,
            publishBucket,
            "base",
            testKey + "test.txt",
            testKey + "test.txt"
          )
        )
        .via(CopyS3ObjectsFlow())
        .toMat(Sink.ignore)(Keep.right)
        .run()
        .await

      downloadFile(file.s3Bucket, file.s3Key) shouldBe "some content"
      downloadFile(publishBucket, s"base/$testKey/test.txt") shouldBe "some content"
    }
  }

  "storage calculator" should {
    "compute total storage under the publish dataset key" in {
      Publish.computeTotalSize(publishContainer).await.value shouldBe 0

      createS3File(s3, publishBucket, testKey + "a", "a")
      createS3File(s3, publishBucket, testKey + "b", "bb")
      createS3File(s3, publishBucket, testKey + "c/c", "ccc")

      Publish.computeTotalSize(publishContainer).await.value shouldBe 6

      createS3File(s3, publishBucket, "another-key/nested", "dddddd")

      Publish.computeTotalSize(publishContainer).await.value shouldBe 6
    }
  }

  "build copy requests" should {
    "emit CopyFile when a new file is to be published" in {
      val pkg = createPackage(testUser, name = "pkg")
      val file1 = createFile(pkg, name = "file1", s3Key = "key/file1.txt")
      val file2 = createFile(pkg, name = "file2", s3Key = "key/file2.txt")

      val (_, sink) = Source
        .single((pkg, Seq("p1", "c1")))
        .via(BuildCopyRequests())
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(n = 100)
      sink.expectNextUnordered(
        CopyAction(
          pkg,
          file1,
          publishBucket,
          testKey,
          s"files/p1/c1/pkg/file1.txt",
          s"files/p1/c1/pkg",
          None
        ),
        CopyAction(
          pkg,
          file2,
          publishBucket,
          testKey,
          s"files/p1/c1/pkg/file2.txt",
          s"files/p1/c1/pkg",
          None
        )
      )
      sink.expectComplete()
    }

//    "emit KeepFile when a published file is unchanged" in {
//    }

//    "emit DeleteFile and CopyFile when a file is moved" in {
//    }

//    "emit DeleteFile when a published file is to be removed" in {
//    }

  }

  "compute file actions " should {
    "copy a New File with a CopyAction" in {
      val pkg1 = createPackage(testUser, name = "pkg1")
      val file1 = createFile(
        pkg1,
        name = "file-new",
        s3Key = "key/file-new.txt",
        content = "data data",
        size = 1234
      )

      val previousFiles = List.empty[FileManifest]
      val currentFiles = List(
        FileManifest(
          path = file1.s3Key,
          size = file1.size,
          fileType = file1.fileType,
          sourcePackageId = Some(pkg1.nodeId)
        )
      )
      val currentPackageFileList = List(
        PackageFile(
          `package` = pkg1,
          file = file1,
          packageKey = "",
          fileKey = file1.s3Key
        )
      )

      val fileActions = PackagesExport.computeFileActions(
        previousFiles,
        currentFiles,
        currentPackageFileList
      )

      val copyAction = CopyAction(
        pkg = pkg1,
        file = file1,
        toBucket = publishContainer.s3Bucket,
        baseKey = publishContainer.s3Key,
        fileKey = file1.s3Key,
        packageKey = "",
        s3VersionId = None
      )

      fileActions.length shouldEqual (1)
      fileActions.head shouldEqual (copyAction)
    }

    "delete a Removed File with a DeleteAction" in {
      val pkg1 = createPackage(testUser, name = "pkg1")
      val file1 = createFile(
        pkg1,
        name = "file-delete",
        s3Key = "key/file-delete.txt",
        content = "data data",
        size = 1234
      )

      val fileManifest = FileManifest(
        path = file1.s3Key,
        size = file1.size,
        fileType = file1.fileType,
        sourcePackageId = Some(pkg1.nodeId)
      )

      val previousFiles = List(fileManifest)
      val currentFiles = List.empty[FileManifest]
      val currentPackageFileList = List.empty[PackageFile]

      val fileActions = PackagesExport.computeFileActions(
        previousFiles,
        currentFiles,
        currentPackageFileList
      )

      val deleteAction = DeleteAction(
        fromBucket = publishContainer.s3Bucket,
        baseKey = publishContainer.s3Key,
        fileKey = file1.s3Key,
        s3VersionId = None
      )

      fileActions.length shouldEqual (1)
      fileActions.head shouldEqual (deleteAction)
    }

    "leave an Unchanged File with a KeepAction" in {
      val pkg1 = createPackage(testUser, name = "pkg1")
      val file1 = createFile(
        pkg1,
        name = "file-unchanged",
        s3Key = "key/file-unchanged.txt",
        content = "data data",
        size = 1234
      )

      val fileManifest = FileManifest(
        path = file1.s3Key,
        size = file1.size,
        fileType = file1.fileType,
        sourcePackageId = Some(pkg1.nodeId)
      )

      val previousFiles = List(fileManifest)
      val currentFiles = List(fileManifest)
      val currentPackageFileList = List(
        PackageFile(
          `package` = pkg1,
          file = file1,
          packageKey = "",
          fileKey = file1.s3Key
        )
      )

      val fileActions = PackagesExport.computeFileActions(
        previousFiles,
        currentFiles,
        currentPackageFileList
      )

      val keepAction = KeepAction(
        pkg = pkg1,
        file = file1,
        bucket = publishContainer.s3Bucket,
        baseKey = publishContainer.s3Key,
        fileKey = file1.s3Key,
        packageKey = "",
        s3VersionId = None
      )

      fileActions.length shouldEqual (1)
      fileActions.head shouldEqual (keepAction)
    }

    "modify a Renamed File with a DeleteAction and a CopyAction" in {
      val pkg1 = createPackage(testUser, name = "pkg1")
      val file1 = createFile(
        pkg1,
        name = "file-original",
        s3Key = "key/file-original.txt",
        content = "data data",
        size = 1234
      )
      val file2 = createFile(
        pkg1,
        name = "file-renamed",
        s3Key = "key/file-renamed.txt",
        content = "data data",
        size = 1234
      )

      val previousFiles = List(
        FileManifest(
          path = file1.s3Key,
          size = file1.size,
          fileType = file1.fileType,
          sourcePackageId = Some(pkg1.nodeId)
        )
      )
      val currentFiles = List(
        FileManifest(
          path = file2.s3Key,
          size = file2.size,
          fileType = file2.fileType,
          sourcePackageId = Some(pkg1.nodeId)
        )
      )
      val currentPackageFileList = List(
        PackageFile(
          `package` = pkg1,
          file = file2,
          packageKey = "",
          fileKey = file2.s3Key
        )
      )

      val fileActions = PackagesExport.computeFileActions(
        previousFiles,
        currentFiles,
        currentPackageFileList
      )

      val deleteAction = DeleteAction(
        fromBucket = publishContainer.s3Bucket,
        baseKey = publishContainer.s3Key,
        fileKey = file1.s3Key,
        s3VersionId = None
      )

      val copyAction = CopyAction(
        pkg = pkg1,
        file = file2,
        toBucket = publishContainer.s3Bucket,
        baseKey = publishContainer.s3Key,
        fileKey = file2.s3Key,
        packageKey = "",
        s3VersionId = None
      )

      fileActions.length shouldEqual (2)
      fileActions shouldEqual (List(deleteAction, copyAction))
    }

    "modify a Moved File with a DeleteAction and a CopyAction" in {
      val pkg1 = createPackage(testUser, name = "pkg1")
      val file1 = createFile(
        pkg1,
        name = "file-moved",
        s3Key = "folder-1/file-moved.txt",
        content = "data data",
        size = 1234
      )
      val file2 = createFile(
        pkg1,
        name = "file-moved",
        s3Key = "folder-2/file-moved.txt",
        content = "data data",
        size = 1234
      )

      val previousFiles = List(
        FileManifest(
          path = file1.s3Key,
          size = file1.size,
          fileType = file1.fileType,
          sourcePackageId = Some(pkg1.nodeId)
        )
      )
      val currentFiles = List(
        FileManifest(
          path = file2.s3Key,
          size = file2.size,
          fileType = file2.fileType,
          sourcePackageId = Some(pkg1.nodeId)
        )
      )
      val currentPackageFileList = List(
        PackageFile(
          `package` = pkg1,
          file = file2,
          packageKey = "",
          fileKey = file2.s3Key
        )
      )

      val fileActions = PackagesExport.computeFileActions(
        previousFiles,
        currentFiles,
        currentPackageFileList
      )

      val deleteAction = DeleteAction(
        fromBucket = publishContainer.s3Bucket,
        baseKey = publishContainer.s3Key,
        fileKey = file1.s3Key,
        s3VersionId = None
      )

      val copyAction = CopyAction(
        pkg = pkg1,
        file = file2,
        toBucket = publishContainer.s3Bucket,
        baseKey = publishContainer.s3Key,
        fileKey = file2.s3Key,
        packageKey = "",
        s3VersionId = None
      )

      fileActions.length shouldEqual (2)
      fileActions shouldEqual (List(deleteAction, copyAction))
    }

    "overwrite a Replaced File (in a new Package) with a CopyAction" in {
      val pkg1 = createPackage(testUser, name = "pkg1")
      val file1 = createFile(
        pkg1,
        name = "file-replaced",
        s3Key = "key/file-replaced.txt",
        content = "data data",
        size = 1234
      )
      val pkg2 = createPackage(testUser, name = "pkg2")
      val file2 = createFile(
        pkg2,
        name = "file-replaced",
        s3Key = "key/file-replaced.txt",
        content = "data data",
        size = 1234
      )

      val previousFiles = List(
        FileManifest(
          path = file1.s3Key,
          size = file1.size,
          fileType = file1.fileType,
          sourcePackageId = Some(pkg1.nodeId)
        )
      )
      val currentFiles = List(
        FileManifest(
          path = file2.s3Key,
          size = file2.size,
          fileType = file2.fileType,
          sourcePackageId = Some(pkg2.nodeId)
        )
      )
      val currentPackageFileList = List(
        PackageFile(
          `package` = pkg2,
          file = file2,
          packageKey = "",
          fileKey = file2.s3Key
        )
      )

      val fileActions = PackagesExport.computeFileActions(
        previousFiles,
        currentFiles,
        currentPackageFileList
      )

      val copyAction = CopyAction(
        pkg = pkg2,
        file = file2,
        toBucket = publishContainer.s3Bucket,
        baseKey = publishContainer.s3Key,
        fileKey = file2.s3Key,
        packageKey = "",
        s3VersionId = None
      )

      fileActions.length shouldEqual (1)
      fileActions shouldEqual (List(copyAction))
    }

    "hide a renamed file" in {
      val pkg = createPackage(testUser, name = "pkg1")
      val originalFilePath = "key/original.dat"
      val renamedFilePath = "key/renamed.txt"
      val file = createFile(
        pkg,
        name = "renamed",
        s3Key = renamedFilePath,
        content = "data data",
        size = 1234
      )

      val previousFiles = List(
        FileManifest(
          path = originalFilePath,
          size = file.size,
          fileType = file.fileType,
          sourcePackageId = Some(pkg.nodeId)
        )
      )
      val currentFiles = List(
        FileManifest(
          path = file.s3Key,
          size = file.size,
          fileType = file.fileType,
          sourcePackageId = Some(pkg.nodeId)
        )
      )
      val currentPackageFileList = List(
        PackageFile(
          `package` = pkg,
          file = file,
          packageKey = "",
          fileKey = file.s3Key
        )
      )

      val fileActions = PackagesExport.computeFileActions(
        previousFiles,
        currentFiles,
        currentPackageFileList
      )

      val deleteAction = DeleteAction(
        fromBucket = publishContainer.s3Bucket,
        baseKey = publishContainer.s3Key,
        fileKey = originalFilePath,
        s3VersionId = None
      )

      val copyAction = CopyAction(
        pkg = pkg,
        file = file,
        toBucket = publishContainer.s3Bucket,
        baseKey = publishContainer.s3Key,
        fileKey = file.s3Key,
        packageKey = "",
        s3VersionId = None
      )

      fileActions.length shouldEqual (2)
      fileActions shouldEqual (List(deleteAction, copyAction))

    }

  } // END: "compute file actions " should...

//  "get dataset metadata" should {
//    "load manifest.json into DatasetMetadata" in {
//
//    }
//  }

  "decode Manifest in Publishing 5.0 into DatasetMetadata" should {
    "load manifest.json into a DatasetMetadata 4.0 when there are no S3 Version Ids" in {
      val sampleMetadataV4 =
        """{
        "pennsieveDatasetId": 1,
        "version": 1,
        "revision": 1,
        "name" : "Test Dataset",
        "description" : "Lorem ipsum",
        "creator" : { "first_name": "Blaise", "last_name": "Pascal", "orcid": "0000-0009-1234-5678"},
        "contributors" : [  { "first_name": "Isaac", "last_name": "Newton"}, { "first_name": "Albert", "last_name": "Einstein"}],
        "sourceOrganization" : "1",
        "keywords" : [
        "neuro",
        "neuron"
        ],
        "datePublished": "2019-06-05",
        "license": "MIT",
        "@id": "10.21397/jlt1-xdqn",
        "publisher" : "The University of Pennsylvania",
        "@context" : "http://purl.org/dc/terms",
        "@type":"Dataset",
        "schemaVersion": "http://schema.org/version/3.7/",
        "collections" : [
          {
            "name" : "My great collection"
          }
        ],
        "relatedPublications" : [
          {
            "doi" : "10.26275/t6j6-77pu",
            "relationshipType" : "IsDescribedBy"
          }
        ],
        "files" : [
          {
            "path" : "packages/brain.dcm",
            "size" : 15010,
            "fileType" : "DICOM",
            "sourcePackageId" : "N:package:1"
          }
        ],
        "pennsieveSchemaVersion" : "4.0"
        }"""

      val mdV4 = DatasetMetadataV4_0(
        pennsieveDatasetId = 1,
        version = 1,
        revision = Some(1),
        name = "Test Dataset",
        description = "Lorem ipsum",
        creator =
          PublishedContributor("Blaise", "Pascal", Some("0000-0009-1234-5678")),
        contributors = List(
          PublishedContributor("Isaac", "Newton", None),
          PublishedContributor("Albert", "Einstein", None)
        ),
        sourceOrganization = "1",
        keywords = List("neuro", "neuron"),
        datePublished = LocalDate.of(2019, 6, 5),
        license = Some(License.MIT),
        `@id` = "10.21397/jlt1-xdqn",
        `@context` = "http://purl.org/dc/terms",
        files = List(
          FileManifest(
            "packages/brain.dcm",
            15010,
            FileType.DICOM,
            Some("N:package:1")
          )
        ),
        collections = Some(List(PublishedCollection("My great collection"))),
        relatedPublications = Some(
          List(
            PublishedExternalPublication(
              Doi("10.26275/t6j6-77pu"),
              Some(RelationshipType.IsDescribedBy)
            )
          )
        )
      )

      decode[DatasetMetadata](sampleMetadataV4) shouldBe Right(mdV4)
    }

    "load manifest.json into a DatasetMetadata 4.0 when there are S3 Version Ids" in {
      val sampleMetadataV4 =
        """{
        "pennsieveDatasetId": 1,
        "version": 1,
        "revision": 1,
        "name" : "Test Dataset",
        "description" : "Lorem ipsum",
        "creator" : { "first_name": "Blaise", "last_name": "Pascal", "orcid": "0000-0009-1234-5678"},
        "contributors" : [  { "first_name": "Isaac", "last_name": "Newton"}, { "first_name": "Albert", "last_name": "Einstein"}],
        "sourceOrganization" : "1",
        "keywords" : [
        "neuro",
        "neuron"
        ],
        "datePublished": "2019-06-05",
        "license": "MIT",
        "@id": "10.21397/jlt1-xdqn",
        "publisher" : "The University of Pennsylvania",
        "@context" : "http://purl.org/dc/terms",
        "@type":"Dataset",
        "schemaVersion": "http://schema.org/version/3.7/",
        "collections" : [
          {
            "name" : "My great collection"
          }
        ],
        "relatedPublications" : [
          {
            "doi" : "10.26275/t6j6-77pu",
            "relationshipType" : "IsDescribedBy"
          }
        ],
        "files" : [
          {
            "path" : "packages/brain.dcm",
            "size" : 15010,
            "fileType" : "DICOM",
            "sourcePackageId" : "N:package:1",
            "s3VersionId": "s3-version-abc123"
          }
        ],
        "pennsieveSchemaVersion" : "4.0"
        }"""

      val mdV4 = DatasetMetadataV4_0(
        pennsieveDatasetId = 1,
        version = 1,
        revision = Some(1),
        name = "Test Dataset",
        description = "Lorem ipsum",
        creator =
          PublishedContributor("Blaise", "Pascal", Some("0000-0009-1234-5678")),
        contributors = List(
          PublishedContributor("Isaac", "Newton", None),
          PublishedContributor("Albert", "Einstein", None)
        ),
        sourceOrganization = "1",
        keywords = List("neuro", "neuron"),
        datePublished = LocalDate.of(2019, 6, 5),
        license = Some(License.MIT),
        `@id` = "10.21397/jlt1-xdqn",
        `@context` = "http://purl.org/dc/terms",
        files = List(
          FileManifest(
            "packages/brain.dcm",
            15010,
            FileType.DICOM,
            Some("N:package:1"),
            Some("s3-version-abc123")
          )
        ),
        collections = Some(List(PublishedCollection("My great collection"))),
        relatedPublications = Some(
          List(
            PublishedExternalPublication(
              Doi("10.26275/t6j6-77pu"),
              Some(RelationshipType.IsDescribedBy)
            )
          )
        )
      )

      decode[DatasetMetadata](sampleMetadataV4) shouldBe Right(mdV4)
    }

    "load manifest.json into a DatasetMetadata 4.0 with and without files having S3 Version Ids" in {
      val sampleMetadataV4 =
        """{
        "pennsieveDatasetId": 1,
        "version": 1,
        "revision": 1,
        "name" : "Test Dataset",
        "description" : "Lorem ipsum",
        "creator" : { "first_name": "Blaise", "last_name": "Pascal", "orcid": "0000-0009-1234-5678"},
        "contributors" : [  { "first_name": "Isaac", "last_name": "Newton"}, { "first_name": "Albert", "last_name": "Einstein"}],
        "sourceOrganization" : "1",
        "keywords" : [
        "neuro",
        "neuron"
        ],
        "datePublished": "2019-06-05",
        "license": "MIT",
        "@id": "10.21397/jlt1-xdqn",
        "publisher" : "The University of Pennsylvania",
        "@context" : "http://purl.org/dc/terms",
        "@type":"Dataset",
        "schemaVersion": "http://schema.org/version/3.7/",
        "collections" : [
          {
            "name" : "My great collection"
          }
        ],
        "relatedPublications" : [
          {
            "doi" : "10.26275/t6j6-77pu",
            "relationshipType" : "IsDescribedBy"
          }
        ],
        "files" : [
          {
            "name" : "manifest.json",
            "path" : "manifest.json",
            "size" : 1234,
            "fileType" : "Json"
          },
          {
            "path" : "packages/brain.dcm",
            "size" : 15010,
            "fileType" : "DICOM",
            "sourcePackageId" : "N:package:1",
            "s3VersionId": "s3-version-abc123"
          }
        ],
        "pennsieveSchemaVersion" : "4.0"
        }"""

      val mdV4 = DatasetMetadataV4_0(
        pennsieveDatasetId = 1,
        version = 1,
        revision = Some(1),
        name = "Test Dataset",
        description = "Lorem ipsum",
        creator =
          PublishedContributor("Blaise", "Pascal", Some("0000-0009-1234-5678")),
        contributors = List(
          PublishedContributor("Isaac", "Newton", None),
          PublishedContributor("Albert", "Einstein", None)
        ),
        sourceOrganization = "1",
        keywords = List("neuro", "neuron"),
        datePublished = LocalDate.of(2019, 6, 5),
        license = Some(License.MIT),
        `@id` = "10.21397/jlt1-xdqn",
        `@context` = "http://purl.org/dc/terms",
        files = List(
          FileManifest("manifest.json", "manifest.json", 1234, FileType.Json),
          FileManifest(
            "packages/brain.dcm",
            15010,
            FileType.DICOM,
            Some("N:package:1"),
            Some("s3-version-abc123")
          )
        ),
        collections = Some(List(PublishedCollection("My great collection"))),
        relatedPublications = Some(
          List(
            PublishedExternalPublication(
              Doi("10.26275/t6j6-77pu"),
              Some(RelationshipType.IsDescribedBy)
            )
          )
        )
      )

      decode[DatasetMetadata](sampleMetadataV4) shouldBe Right(mdV4)
    }

//    "load an actual manifest.json into a DatasetMetadata 4.0" in {
//      val sampleMetadataV4 =
//        """{
//          |  "pennsieveDatasetId" : 5068,
//          |  "version" : 1,
//          |  "name" : "publishing-5x-8",
//          |  "description" : "test dataset 8",
//          |  "creator" : {
//          |    "first_name" : "Michael",
//          |    "last_name" : "Uftring",
//          |    "orcid" : "0000-0001-7054-4685"
//          |  },
//          |  "contributors" : [
//          |    {
//          |      "first_name" : "Michael",
//          |      "last_name" : "Uftring",
//          |      "orcid" : "0000-0001-7054-4685"
//          |    }
//          |  ],
//          |  "sourceOrganization" : "Publishing 5.0 Workspace",
//          |  "keywords" : [
//          |    "publishing",
//          |    "5.0"
//          |  ],
//          |  "datePublished" : "2023-09-11",
//          |  "license" : "Community Data License Agreement – Permissive",
//          |  "@id" : "https://doi.org/10.21397/hsvc-wubl",
//          |  "publisher" : "The University of Pennsylvania",
//          |  "@context" : "http://schema.org/",
//          |  "@type" : "Dataset",
//          |  "schemaVersion" : "http://schema.org/version/3.7/",
//          |  "collections" : [
//          |  ],
//          |  "relatedPublications" : [
//          |  ],
//          |  "files" : [
//          |    {
//          |      "name" : "banner.jpg",
//          |      "path" : "banner.jpg",
//          |      "size" : 5008,
//          |      "fileType" : "JPEG",
//          |      "s3VersionId" : "9u.qGGC5BgkxZhTv4VlvhnSHR8_yK.zs"
//          |    },
//          |    {
//          |      "name" : "changelog.md",
//          |      "path" : "changelog.md",
//          |      "size" : 24,
//          |      "fileType" : "Markdown",
//          |      "s3VersionId" : "llW9Nah2l2JyqylZ6mQW_GwsFjAM1TeT"
//          |    },
//          |    {
//          |      "name" : "file.csv",
//          |      "path" : "metadata/records/file.csv",
//          |      "size" : 355,
//          |      "fileType" : "CSV",
//          |      "s3VersionId" : "4TLWZysTwpqi5JQkZw4VRMnJlMO7umQh"
//          |    },
//          |    {
//          |      "name" : "manifest.json",
//          |      "path" : "manifest.json",
//          |      "size" : 2741,
//          |      "fileType" : "Json"
//          |    },
//          |    {
//          |      "name" : "readme.md",
//          |      "path" : "readme.md",
//          |      "size" : 25,
//          |      "fileType" : "Markdown",
//          |      "s3VersionId" : "x16k1IhceSfRU5KcUUmzEMhZG533sI0I"
//          |    },
//          |    {
//          |      "name" : "schema.json",
//          |      "path" : "metadata/schema.json",
//          |      "size" : 567,
//          |      "fileType" : "Json",
//          |      "s3VersionId" : "5Tiv4AcFDueHJheHibe.XxZDglRnMixq"
//          |    },
//          |    {
//          |      "name" : "test-001.dat",
//          |      "path" : "files/first/test-001.dat",
//          |      "size" : 4100,
//          |      "fileType" : "Persyst",
//          |      "sourcePackageId" : "N:package:a6512ea3-918d-45cd-adef-a5ef8f30600e",
//          |      "s3VersionId" : "L6jMEVVz7.jKms05YP.2DlV_7KjxzFf1"
//          |    },
//          |    {
//          |      "name" : "test-002.dat",
//          |      "path" : "files/first/test-002.dat",
//          |      "size" : 4100,
//          |      "fileType" : "Persyst",
//          |      "sourcePackageId" : "N:package:929564cc-9260-41d8-857f-cc78f90eb8ca",
//          |      "s3VersionId" : "vlutn_m4ZOECZKhpMhaeYtFceHCWHQD7"
//          |    },
//          |    {
//          |      "name" : "test-003.dat",
//          |      "path" : "files/first/test-003.dat",
//          |      "size" : 4100,
//          |      "fileType" : "Persyst",
//          |      "sourcePackageId" : "N:package:9bf150bc-c700-4094-a3d0-1e43d22f971d",
//          |      "s3VersionId" : "IFmDw_6sNP_b9Rx0LTjvx4EasTvYjcY6"
//          |    }
//          |  ],
//          |  "pennsieveSchemaVersion" : "4.0"
//          |}""".stripMargin
//
//      val mdV4 = DatasetMetadataV4_0(
//        pennsieveDatasetId = 1,
//        version = 1,
//        revision = Some(1),
//        name = "Test Dataset",
//        description = "Lorem ipsum",
//        creator =
//          PublishedContributor("Blaise", "Pascal", Some("0000-0009-1234-5678")),
//        contributors = List(
//          PublishedContributor("Isaac", "Newton", None),
//          PublishedContributor("Albert", "Einstein", None)
//        ),
//        sourceOrganization = "1",
//        keywords = List("neuro", "neuron"),
//        datePublished = LocalDate.of(2019, 6, 5),
//        license = Some(License.MIT),
//        `@id` = "10.21397/jlt1-xdqn",
//        `@context` = "http://purl.org/dc/terms",
//        files = List(
//          FileManifest("manifest.json", "manifest.json", 1234, FileType.Json),
//          FileManifest(
//            "packages/brain.dcm",
//            15010,
//            FileType.DICOM,
//            Some("N:package:1"),
//            Some("s3-version-abc123")
//          )
//        ),
//        collections = Some(List(PublishedCollection("My great collection"))),
//        relatedPublications = Some(
//          List(
//            PublishedExternalPublication(
//              Doi("10.26275/t6j6-77pu"),
//              Some(RelationshipType.IsDescribedBy)
//            )
//          )
//        )
//      )
//
//      decode[DatasetMetadata](sampleMetadataV4) shouldBe Right(mdV4)
//    }

  }

  /**
    * Delete all objects from bucket, and delete the bucket itself
    */
  def deleteBucket(bucket: String): Assertion = {
    listBucket(bucket)
      .map(o => s3.deleteObject(bucket, o.getKey).isRight shouldBe true)
    s3.deleteBucket(bucket).isRight shouldBe true
  }

  def listBucket(bucket: String): mutable.Seq[S3ObjectSummary] =
    s3.client
      .listObjectsV2(bucket)
      .getObjectSummaries
      .asScala

  /**
    * Read file contents from S3 as a string.
    */
  def downloadFile(s3Bucket: String, s3Key: String): String = {
    val stream: InputStream = s3
      .getObject(s3Bucket, s3Key)
      .leftMap(e => {
        println(s"Error downloading s3://$s3Bucket/$s3Key")
        e
      })
      .map(_.getObjectContent())
      .value

    try {
      IOUtils.toString(stream, "utf-8")
    } finally {
      stream.close()
    }
  }

  /**
    * Mock run `model-publish`, publishing the minimal required graph files to S3.
    */
  def runModelPublish(s3Bucket: String, s3Key: String): Unit = {

    val schemaJsonKey = s3Key + "metadata/schema.json"

    val schemaJson = s"""{
      "models": [],
      "relationships": []
    }"""

    s3.putObject(s3Bucket, schemaJsonKey, schemaJson)
      .leftMap(e => {
        println(s"Error uploading s3://$s3Bucket/$schemaJsonKey")
        e
      })
      .isRight shouldBe true

    // Basic graph manifests
    s3.putObject(s3Bucket, s3Key + Publish.GRAPH_ASSETS_FILENAME, s"""{
      "manifests": [
        {
          "path": "metadata/schema.json",
          "size": ${schemaJson.length},
          "fileType": "Json"
        }
      ]}""")
      .leftMap(e => {
        println(
          s"Error uploading s3://$s3Bucket/${s3Key + Publish.GRAPH_ASSETS_FILENAME}"
        )
        e
      })
      .isRight shouldBe true
  }

  def setDatasetIgnoreFiles(
    ignoreFiles: Seq[DatasetIgnoreFile]
  ): Seq[DatasetIgnoreFile] =
    publishContainer.datasetManager
      .setIgnoreFiles(publishContainer.dataset, ignoreFiles)
      .await
      .value

  def createPackage(
    user: User,
    name: String = generateRandomString(),
    nodeId: String = NodeCodes.generateId(NodeCodes.packageCode),
    `type`: PackageType = PackageType.Text,
    state: PackageState = PackageState.READY,
    dataset: Dataset = testDataset,
    parent: Option[Package] = None
  ): Package =
    createPackageInDb(
      databaseContainer,
      user,
      name,
      nodeId,
      `type`,
      state,
      dataset,
      parent
    )

  def createFile(
    `package`: Package,
    name: String = generateRandomString(),
    s3Bucket: String = sourceBucket,
    s3Key: String = "key/" + generateRandomString() + ".txt",
    fileType: FileType = FileType.Text,
    objectType: FileObjectType = FileObjectType.Source,
    processingState: FileProcessingState = FileProcessingState.Processed,
    size: Long = 0,
    content: String = generateRandomString(),
    uploadedState: Option[FileState] = None
  )(implicit
    publishContainer: PublishContainer
  ): File =
    createFileS3Optional(
      publishContainer.fileManager,
      `package`,
      name,
      s3Bucket,
      s3Key,
      fileType,
      objectType,
      processingState,
      size,
      content,
      uploadedState,
      Some(s3)
    )

  def s3FilesExistUnderKey(
    s3Bucket: String,
    s3KeyPrefix: String,
    matchExact: Boolean = false
  ): Boolean = {
    val usePrefix = if (matchExact) {
      s3KeyPrefix
    } else {
      if (s3KeyPrefix.endsWith("/")) {
        s3KeyPrefix
      } else {
        s3KeyPrefix + "/"
      }
    }
    s3.client
      .listObjects(s3Bucket, usePrefix)
      .getObjectSummaries
      .size() > 0
  }

}
