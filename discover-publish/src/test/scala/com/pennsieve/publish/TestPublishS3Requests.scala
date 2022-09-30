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
  DeleteObjectRequest,
  GetObjectRequest,
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
import com.pennsieve.clients.{ DatasetAssetClient, S3DatasetAssetClient }
import com.pennsieve.models.{
  Dataset,
  FileManifest,
  FileType,
  Organization,
  Role,
  User
}
import com.pennsieve.publish.models.{ ExportedGraphResult, PublishAssetResult }
import com.pennsieve.test.PersistantTestContainers
import com.pennsieve.test.helpers.AwaitableImplicits.toAwaitable
import com.pennsieve.test.helpers.EitherBePropertyMatchers
import com.typesafe.config.{ Config, ConfigFactory }
import org.apache.http.client.methods.HttpRequestBase
import org.mockserver.client.MockServerClient
import org.mockserver.model.{ ClearType, HttpRequest, MediaType }
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.scalamock.matchers.ArgCapture.CaptureAll
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach }
import org.scalatest.Inspectors._
import scala.jdk.CollectionConverters._

import java.util.UUID
import scala.concurrent.ExecutionContext

class TestPublishS3Requests
    extends AnyWordSpec
    with Matchers
    with MockServerDockerContainer
    with PersistantTestContainers
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with MockFactory
    with ValueHelper
    with EitherBePropertyMatchers {

  val testDataset: Dataset = newDataset()
  val testOrganization: Organization = sampleOrganization
  val publishBucket = "publish-bucket"
  val assetBucket = "asset-bucket"

  implicit var system: ActorSystem = _
  implicit var executionContext: ExecutionContext = _

  var config: Config = _
  var mockAmazonS3: AmazonS3 = _
  var publishMetadata: PublishContainerConfig = _
  var publishAssetResult: PublishAssetResult = _
  var mockServerClient: MockServerClient = _

  override def afterStart(): Unit = {
    super.afterStart()

    // alpakka-s3 v1.0 can only be configured via Typesafe config passed to the
    // actor system, or as S3Settings that are attached to every graph
    config = ConfigFactory
      .empty()
      .withFallback(mockServerContainer.config)
      .withFallback(ConfigFactory.load())

    system = ActorSystem("discover-publish", config)
    executionContext = system.dispatcher

    mockServerClient = mockServerContainer.mockServerClient

  }

  override def beforeEach(): Unit = {
    mockAmazonS3 = mock[AmazonS3]
    val testS3 = new S3(mockAmazonS3)

    publishMetadata = new PublishContainerConfig {
      def s3: S3 = testS3
      def s3Bucket = publishBucket
      def s3AssetBucket: String = assetBucket
      def s3Key = "key"
      def s3AssetKeyPrefix = "asset-key-prefix"
      def s3CopyChunkSize = 1024
      def s3CopyChunkParallelism = 1
      def s3CopyFileParallelism = 1
      def doi = "doi"
      def dataset: Dataset = testDataset
      def publishedDatasetId = 100
      def version = 10
      def organization: Organization = testOrganization
      def user: User = newUser()
      def userOrcid = "user-orcid"
      def datasetRole: Option[Role] = Some(Role.Owner)
      def contributors = List(contributor)
      def collections = List(collection)
      def externalPublications = List(externalPublication)
      def datasetAssetClient: DatasetAssetClient =
        new S3DatasetAssetClient(testS3, assetBucket)
    }

    publishAssetResult = PublishAssetResult(
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

  }

  override def afterEach(): Unit = {
    mockServerClient.clear(request(), ClearType.LOG)
  }

  override def afterAll(): Unit = {
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

  "finalizeDataset" should {
    "include requester pays on all AWS S3 requests" in {

      mockServerClient
        .when(
          request()
            .withMethod("GET")
            .withPath(s"/${publishBucket}")
        )
        .respond(
          response()
            .withStatusCode(200)
            .withContentType(MediaType.APPLICATION_XML)
            .withBody("""<?xml version="1.0" encoding="UTF-8"?>
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
                |</ListBucketResult>""".stripMargin)
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

      (mockAmazonS3.putObject(_: PutObjectRequest)).expects(*).twice()

      (mockAmazonS3.deleteObject(_: DeleteObjectRequest)).expects(*).twice()

      Publish
        .finalizeDataset(publishMetadata)
        .await should be a right

      val akkaRequests: Seq[HttpRequest] =
        mockServerClient.retrieveRecordedRequests(request()).toSeq

      forAll(akkaRequests) {
        _.containsHeader("x-amz-request-payer", "requester") should be(true)
      }
      forAll(getObjectCapture.values) { _.isRequesterPays should be(true) }
      forAll(putObjectCapture.values) { _.isRequesterPays should be(true) }
      forAll(deleteObjectCapture.values) { _.isRequesterPays should be(true) }

    }
  }

}
