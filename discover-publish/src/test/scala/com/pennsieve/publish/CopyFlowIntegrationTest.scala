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

import akka.actor.ActorSystem
import akka.stream.scaladsl.{ Keep, Sink, Source }
import cats.implicits._
import com.amazonaws.ClientConfiguration
import com.amazonaws.services.s3.{ AmazonS3, AmazonS3ClientBuilder }
import com.amazonaws.services.s3.model.S3ObjectSummary
import com.pennsieve.aws.s3.S3
import com.pennsieve.clients.{ DatasetAssetClient, S3DatasetAssetClient }
import com.pennsieve.models._
import com.pennsieve.publish.models.CopyAction
import com.typesafe.config.ConfigValueFactory
import org.scalatest.Inspectors.forAll
import com.typesafe.config.{ Config, ConfigFactory }
import net.ceedubs.ficus.Ficus._
import org.scalatest.AppendedClues.convertToClueful
import org.scalatest.EitherValues._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{
  BeforeAndAfterAll,
  BeforeAndAfterEach,
  DoNotDiscover,
  Suite
}

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext }
import scala.jdk.CollectionConverters._

/**
  * Runs tests of CopyFlow against real buckets in AWS.
  * Expects several environment variables to be in place:
  *
  * AWS_ACCESS_KEY_ID
  * AWS_SECRET_ACCESS_KEY
  * AWS_SESSION_TOKEN
  * These are the credentials of a user/role that can perform the copy from the source bucket to the target bucket.
  * Easy way to get these is to run `assume-role non-prod admin` in a terminal and then run `env` in that terminal to
  * get these values.
  *
  * SOURCE_BUCKET: the bucket containing the files to be copied
  * SOURCE_KEY1: the key of a file to be copied
  * SOURCE_KEY2: the key of a file to be copied
  * TARGET_BUCKET: the destination bucket of the copy.
  */
@DoNotDiscover
class CopyFlowIntegrationTest
    extends AnyWordSpec
    with Matchers
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with ValueHelper {
  self: Suite =>

  implicit var system: ActorSystem = _
  implicit var executionContext: ExecutionContext = _

  implicit var s3: S3 = _
  var sourceBucketName: String = _
  var targetBucketName: String = _
  var sourceS3Key1: String = _
  var sourceS3Key2: String = _
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
  var s3CopyChunkSize: Int = _
  var s3CopyChunkParallelism: Int = _
  var s3CopyFileParallelism: Int = _
  var s3Client: AmazonS3 = _

  implicit var publishContainer: PublishContainer = _
  var embargoContainer: PublishContainer = _
  var datasetAssetClient: DatasetAssetClient = _

  override def beforeAll(): Unit = {
    super.beforeAll()

    sourceBucketName = sys.env("SOURCE_BUCKET")
    sourceS3Key1 = sys.env("SOURCE_KEY1")
    sourceS3Key2 = sys.env("SOURCE_KEY2")
    targetBucketName = sys.env("TARGET_BUCKET")

    // alpakka-s3 v1.0 can only be configured via Typesafe config passed to the
    // actor system, or as S3Settings that are attached to every graph
    // There is no config in test/resources, so the load() below will read the
    // prod config in main/resources
    config = ConfigFactory
      .empty()
      .withValue(
        "s3.copy-chunk-size",
        ConfigValueFactory
          .fromAnyRef(1073741824) //same value set from terraform in dev/prod
      )
      .withFallback(ConfigFactory.load())

    system = ActorSystem("discover-publish", config)
    executionContext = system.dispatcher
    s3Client = {
      val clientConfig =
        new ClientConfiguration().withSignerOverride("AWSS3V4SignerType")
      AmazonS3ClientBuilder
        .standard()
        .withPathStyleAccessEnabled(true)
        .withClientConfiguration(clientConfig)
        .build()
    }
    s3 = new S3(s3Client)
  }

  override def beforeEach(): Unit = {
    super.beforeEach()

    datasetAssetClient = new S3DatasetAssetClient(s3, assetBucket)

    s3.headBucket(sourceBucketName).toTry.get
    s3.headBucket(targetBucketName).isRight shouldBe true

    testUser = newUser()
    testDataset = newDataset()

    s3CopyChunkSize = config.as[Int]("s3.copy-chunk-size")
    s3CopyChunkParallelism = config.as[Int]("s3.copy-chunk-parallelism")
    s3CopyFileParallelism = config.as[Int]("s3.copy-file-parallelism")

    publishContainer = {
      PublishContainer(
        config = config,
        s3 = s3,
        s3Bucket = publishBucket,
        s3AssetBucket = assetBucket,
        s3Key = testKey,
        s3AssetKeyPrefix = assetKeyPrefix,
        s3CopyChunkSize = s3CopyChunkSize,
        s3CopyChunkParallelism = s3CopyChunkParallelism,
        s3CopyFileParallelism = s3CopyFileParallelism,
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
        s3CopyChunkSize = s3CopyChunkSize,
        s3CopyChunkParallelism = s3CopyChunkParallelism,
        s3CopyFileParallelism = s3CopyFileParallelism,
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

  }

  override def afterEach(): Unit = {
    super.afterEach()
    publishContainer.db.close()
  }

  override def afterAll(): Unit = {
    system.terminate()
    super.afterAll()
  }

  "this test" should {

    "be able to ls the dev storage bucket" in {
      val summaries = listBucket(sourceBucketName)
      summaries should not be empty
    }
  }

  "CopyS3ObjectsFlow" should {

    "copy several big files" in {
      val targetKeyPrefix = "large-file-test"

      val copyActions =
        makeCopyActions(9, sourceS3Key2, sourceS3Key2, targetKeyPrefix)

      Await.result(
        Source(copyActions)
          .via(CopyS3ObjectsFlow())
          .toMat(Sink.ignore)(Keep.right)
          .run(),
        Duration.Inf
      )

      forAll(copyActions) { copyAction =>
        s3FilesExistUnderKey(
          targetBucketName,
          copyAction.copyToKey,
          matchExact = true
        ) shouldBe true withClue s"file not found at ${copyAction.copyToKey}"
      }
    }
  }

  "ExecuteS3ObjectActions" should {
    "copy several big files" in {
      val targetKeyPrefix = "large-file-test-5x"

      val copyActions =
        makeCopyActions(9, sourceS3Key2, sourceS3Key2, targetKeyPrefix)

      Await.result(
        Source(copyActions)
          .via(ExecuteS3ObjectActions())
          .toMat(Sink.ignore)(Keep.right)
          .run(),
        Duration.Inf
      )

      forAll(copyActions) { copyAction =>
        s3FilesExistUnderKey(
          targetBucketName,
          copyAction.copyToKey,
          matchExact = true
        ) shouldBe true withClue s"file not found at ${copyAction.copyToKey}"
      }
    }
  }

  def makeCopyActions(
    size: Int,
    evenSourceS3Key: String,
    oddSourceS3Key: String,
    targetKeyPrefix: String
  ): Seq[CopyAction] =
    for (fileNo <- 1 to size)
      yield {
        val fileName = s"random-file$fileNo.data"
        val pkg = createPackageObject(
          testUser,
          name = fileName,
          `type` = PackageType.Unsupported
        )
        val sourceS3Key =
          if (fileNo % 2 == 0) evenSourceS3Key else oddSourceS3Key
        val file = createFileObject(
          `package` = pkg,
          name = fileName,
          s3Bucket = sourceBucketName,
          s3Key = sourceS3Key,
          fileType = FileType.Data,
          objectType = FileObjectType.Source
        )
        CopyAction(
          pkg = pkg,
          file = file,
          toBucket = targetBucketName,
          baseKey = targetKeyPrefix,
          fileKey = fileName,
          packageKey = fileName
        )
      }

  def listBucket(bucket: String): mutable.Seq[S3ObjectSummary] =
    s3.client
      .listObjectsV2(bucket)
      .getObjectSummaries
      .asScala

  def createPackageObject(
    user: User,
    name: String = generateRandomString(),
    nodeId: String = NodeCodes.generateId(NodeCodes.packageCode),
    `type`: PackageType = PackageType.Text,
    state: PackageState = PackageState.READY,
    dataset: Dataset = testDataset,
    parent: Option[Package] = None
  ): Package = Package(
    nodeId = nodeId,
    name = name,
    `type` = `type`,
    datasetId = dataset.id,
    state = state,
    ownerId = Some(user.id),
    parentId = parent.map(_.id)
  )

  def createFileObject(
    `package`: Package,
    name: String = generateRandomString(),
    s3Bucket: String = sourceBucket,
    s3Key: String = "key/" + generateRandomString() + ".txt",
    fileType: FileType = FileType.Text,
    objectType: FileObjectType = FileObjectType.Source,
    processingState: FileProcessingState = FileProcessingState.Processed,
    size: Long = 0,
    uploadedState: Option[FileState] = None
  ): File =
    File(
      `package`.id,
      name,
      fileType,
      s3Bucket,
      s3Key,
      objectType,
      processingState,
      size,
      uploadedState = uploadedState
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
