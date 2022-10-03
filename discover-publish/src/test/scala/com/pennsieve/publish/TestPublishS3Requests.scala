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
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{
  CopyObjectRequest,
  CopyObjectResult,
  DeleteObjectRequest,
  GetObjectRequest,
  ObjectMetadata,
  PutObjectRequest,
  S3Object,
  S3ObjectInputStream
}
import com.amazonaws.util.StringInputStream
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import io.circe.syntax._
import com.pennsieve.aws.s3.S3
import com.pennsieve.clients.S3DatasetAssetClient
import com.pennsieve.managers.FileManager
import com.pennsieve.models.{
  Dataset,
  FileManifest,
  FileType,
  Organization,
  Role,
  User
}
import com.pennsieve.publish.models.{ ExportedGraphResult, PublishAssetResult }
import com.pennsieve.publish.utils.joinKeys
import com.pennsieve.test.{ PersistantTestContainers, PostgresDockerContainer }
import com.pennsieve.test.helpers.{ EitherBePropertyMatchers, TestDatabase }
import com.typesafe.config.{ Config, ConfigFactory }
import org.apache.http.client.methods.HttpRequestBase
import org.mockserver.client.MockServerClient
import org.mockserver.model.{
  ClearType,
  HttpRequest,
  MediaType,
  RequestDefinition
}
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.scalamock.matchers.ArgCapture.CaptureAll
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach }
import org.scalatest.Inspectors._

import java.util.UUID
import scala.concurrent.ExecutionContext

/**
  * This class is testing that all S3 requests for the publish bucket
  * are setting the requestor pays header.
  *
  * Publishing accesses S3 two ways: 1) using our own S3 which wraps an AmazonS3 and 2) using Akka's Alpakka library
  * For 1) we use a ScalaMock in place of a real AmazonS3 and capture the requests.
  * For 2) there is no client to mock, so we use MockServer to mock the S3 backend and
  * again check all requests sent for the desired header.
  */
class TestPublishS3Requests
    extends AnyWordSpec
    with Matchers
    with PersistantTestContainers
    with MockServerDockerContainer
    with PostgresDockerContainer
    with TestDatabase
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with MockFactory
    with ValueHelper
    with EitherBePropertyMatchers {

  val testOrganization: Organization = sampleOrganization

  val listObjectsV2ResponseBody: String =
    """<?xml version="1.0" encoding="UTF-8"?>
                                    |<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                                    |    <Name>bucket</Name>
                                    |    <Prefix/>
                                    |    <KeyCount>205</KeyCount>
                                    |    <MaxKeys>1000</MaxKeys>
                                    |    <IsTruncated>false</IsTruncated>
                                    |    <Contents>
                                    |        <Key>my-image.jpg</Key>
                                    |        <LastModified>2009-10-12T17:50:30.000Z</LastModified>
                                    |        <ETag>"fba9dede5f27731c9771645a39863328"</ETag>
                                    |        <Size>434234</Size>
                                    |        <StorageClass>STANDARD</StorageClass>
                                    |    </Contents>
                                    |</ListBucketResult>""".stripMargin
  val publishAssetResult: PublishAssetResult = PublishAssetResult(
    externalIdToPackagePath = Map.empty,
    packageManifests = Nil,
    bannerKey = Publish.BANNER_FILENAME,
    bannerManifest = FileManifest(
      Publish.BANNER_FILENAME,
      "a/b",
      0,
      FileType.PNG,
      None,
      Some(UUID.randomUUID)
    ),
    readmeKey = Publish.README_FILENAME,
    readmeManifest = FileManifest(
      Publish.README_FILENAME,
      "a/b",
      0,
      FileType.Markdown,
      None,
      Some(UUID.randomUUID)
    ),
    changelogKey = "changelog.md",
    changelogManifest = FileManifest(
      "changelog.md",
      "a/b",
      0,
      FileType.Markdown,
      None,
      Some(UUID.randomUUID)
    )
  )

  implicit var system: ActorSystem = _
  implicit var executionContext: ExecutionContext = _

  var config: Config = _
  var databaseContainer: InsecureDatabaseContainer = _
  var mockAmazonS3: AmazonS3 = _
  var publishContainer: PublishContainer = _
  var mockServerClient: MockServerClient = _
  var testUser: User = _
  var testDataset: Dataset = _
  var datasetFileInfos: Vector[DatasetFileInfo] = _

  class DatasetFileInfo(
    val sourceKey: String,
    val size: Long,
    var uploadId: String = null,
    val packageName: Option[String] = None // leave as None if only one file in package
  ) {
    //This is brittle and will break these  tests if we start using a different format for publish keys.
    def targetKey: String =
      joinKeys(
        Seq(
          publishContainer.s3Key,
          "files",
          packageName.getOrElse(""),
          sourceKey.split('/').last
        )
      )
  }

  override def afterStart(): Unit = {
    super.afterStart()

    // alpakka-s3 v1.0 can only be configured via Typesafe config passed to the
    // actor system, or as S3Settings that are attached to every graph
    config = ConfigFactory
      .empty()
      .withFallback(postgresContainer.config)
      .withFallback(mockServerContainer.config)
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
    mockServerClient = mockServerContainer.mockServerClient

  }

  override def beforeEach(): Unit = {
    mockAmazonS3 = mock[AmazonS3]
    val testS3 = new S3(mockAmazonS3)

    testUser = createUser(databaseContainer)
    testDataset = createDatasetWithAssets(databaseContainer = databaseContainer)

    publishContainer = PublishContainer(
      config = config,
      s3 = testS3,
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
      datasetAssetClient = new S3DatasetAssetClient(testS3, assetBucket)
    )

    datasetFileInfos =
      addPackagesToDataset(databaseContainer, publishContainer.fileManager)

  }

  override def afterEach(): Unit = {
    mockServerClient.clear(request(), ClearType.LOG)
    publishContainer.db.close()
  }

  override def afterAll(): Unit = {
    databaseContainer.db.close()
    system.terminate()
  }

  private def mockS3Object(content: String): S3Object = {
    val s3Object = mock[S3Object]
    (s3Object.getObjectContent _)
      .expects()
      .returns(
        new S3ObjectInputStream(
          new StringInputStream(content),
          mock[HttpRequestBase]: @scala.annotation.nowarn //HttpRequestBase is pulling in something deprecated
        )
      )
    s3Object
  }

  "Publish.finalizeDataset" should {
    "include requester pays on all AWS S3 requests" in {

      mockServerClient
        .when(
          request()
            .withMethod("GET")
            .withPath(s"/$publishBucket")
        )
        .respond(
          response()
            .withStatusCode(200)
            .withContentType(MediaType.APPLICATION_XML)
            .withBody(listObjectsV2ResponseBody)
        )

      val publishAssetResultObject =
        mockS3Object(publishAssetResult.asJson.toString)

      val exportedGraphResultObject =
        mockS3Object(ExportedGraphResult(Nil).asJson.toString)

      val getObjectCapture = CaptureAll[GetObjectRequest]()
      val putObjectCapture = CaptureAll[PutObjectRequest]()
      val deleteObjectCapture = CaptureAll[DeleteObjectRequest]()

      (mockAmazonS3
        .getObject(_: GetObjectRequest))
        .expects(capture(getObjectCapture))
        .twice()
        .onCall { r: GetObjectRequest =>
          if (r.getKey.endsWith(Publish.PUBLISH_ASSETS_FILENAME)) {
            publishAssetResultObject
          } else if (r.getKey.endsWith(Publish.GRAPH_ASSETS_FILENAME)) {
            exportedGraphResultObject
          } else fail(s"Unexpected get object key: ${r.getKey}")
        }

      (mockAmazonS3
        .putObject(_: PutObjectRequest))
        .expects(capture(putObjectCapture))
        .twice()

      (mockAmazonS3
        .deleteObject(_: DeleteObjectRequest))
        .expects(capture(deleteObjectCapture))
        .twice()

      Publish
        .finalizeDataset(publishContainer)
        .await should be a right

      forAll(retrieveMockServerRequests(request())) {
        _.containsHeader("x-amz-request-payer", "requester") should be(true)
      }
      forAll(getObjectCapture.values.filter(_.getBucketName == publishBucket)) {
        _.isRequesterPays should be(true)
      }
      forAll(putObjectCapture.values.filter(_.getBucketName == publishBucket)) {
        _.isRequesterPays should be(true)
      }
      forAll(
        deleteObjectCapture.values.filter(_.getBucketName == publishBucket)
      ) { _.isRequesterPays should be(true) }

    }
  }

  "Publish.publishAssets" should {
    "include requestor pays in all requests for publish bucket to AWS" in {

      val akkaStartMultipartRequests: Vector[HttpRequest] =
        akkaStartMultipartExpectation()

      akkaSourceHeadExpectation()

      val akkaCopyPartRequests: Vector[HttpRequest] = akkaCopyPartExpectation()

      val akkaCompleteMultipartRequests: Vector[HttpRequest] =
        akkaCompleteMultipartExpectation()

      val copyObjectCapture = CaptureAll[CopyObjectRequest]()
      val putObjectCapture = CaptureAll[PutObjectRequest]()

      (mockAmazonS3
        .copyObject(_: CopyObjectRequest))
        .expects(capture(copyObjectCapture))
        .repeated(6)
        .returning(new CopyObjectResult())

      (mockAmazonS3
        .putObject(_: PutObjectRequest))
        .expects(capture(putObjectCapture))

      // We don't capture these requests because they read from the asset bucket, not publish.
      (mockAmazonS3
        .getObjectMetadata(_: String, _: String))
        .expects(where { (b: String, _: String) =>
          b == assetBucket
        })
        .repeat(3)
        .onCall { _ =>
          val response = new ObjectMetadata()
          response.setContentLength(201)
          response
        }

      Publish.publishAssets(publishContainer).await should be a right

      forAll(
        copyObjectCapture.values
          .filter(_.getDestinationBucketName == publishBucket)
      ) { _.isRequesterPays should be(true) }

      forAll(putObjectCapture.values.filter(_.getBucketName == publishBucket)) {
        _.isRequesterPays should be(true)
      }

      assertAkkaRequestsAreRequestorPays(akkaStartMultipartRequests)
      assertAkkaRequestsAreRequestorPays(akkaCopyPartRequests)
      assertAkkaRequestsAreRequestorPays(akkaCompleteMultipartRequests)

    }
  }

  private def assertAkkaRequestsAreRequestorPays(
    requests: Seq[RequestDefinition]
  ): Unit = forAll(retrieveMockServerRequests(requests)) {
    _.containsHeader("x-amz-request-payer", "requester") should be(true)
  }

  //Nothing returned since we don't need to capture these requests:
  // they are for the source, not publish bucket.
  private def akkaSourceHeadExpectation(): Unit =
    for (fileInfo <- datasetFileInfos)
      yield {
        val requestMatcher = request()
          .withMethod("HEAD")
          .withPath(s"/$sourceBucket/${fileInfo.sourceKey}")

        mockServerClient
          .when(requestMatcher)
          .respond(
            response
              .withStatusCode(200)
              .withHeader("content-length", fileInfo.size.toString)
          )

      }

  private def akkaStartMultipartExpectation(): Vector[HttpRequest] =
    for (fileInfo <- datasetFileInfos)
      yield {
        fileInfo.uploadId = generateRandomString()
        val requestMatcher = request()
          .withMethod("POST")
          .withPath(s"/$publishBucket/${fileInfo.targetKey}")
          .withQueryStringParameter("uploads")
        mockServerClient
          .when(requestMatcher)
          .respond(
            response
              .withStatusCode(200)
              .withContentType(MediaType.APPLICATION_XML)
              .withBody(
                s"""<?xml version="1.0" encoding="UTF-8"?>
                               |            <InitiateMultipartUploadResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                               |              <Bucket>$publishBucket</Bucket>
                               |              <Key>${fileInfo.targetKey}</Key>
                               |              <UploadId>${fileInfo.uploadId}</UploadId>
                               |            </InitiateMultipartUploadResult>""".stripMargin
              )
          )

        requestMatcher

      }

  private def akkaCopyPartExpectation(): Vector[HttpRequest] =
    for (fileInfo <- datasetFileInfos)
      yield {
        fileInfo.uploadId = generateRandomString()
        val requestMatcher = request()
          .withMethod("PUT")
          .withPath(s"/$publishBucket/${fileInfo.targetKey}")
          .withQueryStringParameter("partNumber")
          .withQueryStringParameter("uploadId")
        mockServerClient
          .when(requestMatcher)
          .respond(
            response
              .withStatusCode(200)
              .withBody(s"""<CopyPartResult>
                          |   <LastModified>2011-04-11T20:34:56.000Z</LastModified>
                          |   <ETag>"${generateRandomString()}"</ETag>
                          |</CopyPartResult>""".stripMargin)
          )

        requestMatcher

      }
  private def akkaCompleteMultipartExpectation(): Vector[HttpRequest] =
    for (fileInfo <- datasetFileInfos)
      yield {
        fileInfo.uploadId = generateRandomString()
        val requestMatcher = request()
          .withMethod("POST")
          .withPath(s"/$publishBucket/${fileInfo.targetKey}")
          .withQueryStringParameter("uploadId")
        mockServerClient
          .when(requestMatcher)
          .respond(
            response
              .withStatusCode(200)
              .withContentType(MediaType.APPLICATION_XML)
              .withBody(s"""<?xml version="1.0" encoding="UTF-8"?>
                   |            <CompleteMultipartUploadResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                   |             <Location>https://$publishBucket.s3.amazonaws.com/${fileInfo.targetKey}</Location>
                   |             <Bucket>$publishBucket</Bucket>
                   |             <Key>${fileInfo.targetKey}</Key>
                   |             <ETag>"${generateRandomString()}"</ETag>
                   |            </CompleteMultipartUploadResult>""".stripMargin)
          )

        requestMatcher

      }

  private def retrieveMockServerRequests(
    requestMatcher: RequestDefinition
  ): Seq[HttpRequest] =
    mockServerClient.retrieveRecordedRequests(requestMatcher).toSeq

  private def retrieveMockServerRequests(
    requestMatchers: Seq[RequestDefinition]
  ): Seq[HttpRequest] = {
    requestMatchers.flatMap(mockServerClient.retrieveRecordedRequests(_))
  }

  private def addPackagesToDataset(
    databaseContainer: InsecureDatabaseContainer,
    fileManager: FileManager
  ): Vector[DatasetFileInfo] = {

    val fileInfos = Vector(
      new DatasetFileInfo("key/pkg1.txt", 1234),
      new DatasetFileInfo("key/file2.dcm", 2222, packageName = Some("pkg2")),
      new DatasetFileInfo("key/file3.dcm", 3333, packageName = Some("pkg2"))
    )
// Add files to the dataset
    val pkg1 = createPackageInDb(
      databaseContainer,
      testUser,
      dataset = testDataset,
      name = "pkg1"
    )
    createFileS3Optional(
      fileManager,
      pkg1,
      name = "file1",
      s3Key = fileInfos(0).sourceKey,
      content = "data data",
      size = fileInfos(0).size
    )

    // Package with multiple file sources
    val pkg2 = createPackageInDb(
      databaseContainer,
      testUser,
      dataset = testDataset,
      name = "pkg2"
    )
    createFileS3Optional(
      fileManager,
      pkg2,
      name = "file2",
      s3Key = fileInfos(1).sourceKey,
      content = "atad atad",
      size = fileInfos(1).size,
      fileType = FileType.DICOM
    )
    createFileS3Optional(
      fileManager,
      pkg2,
      name = "file3",
      s3Key = fileInfos(2).sourceKey,
      content = "double data",
      size = fileInfos(2).size,
      fileType = FileType.DICOM
    )
    fileInfos
  }
}
