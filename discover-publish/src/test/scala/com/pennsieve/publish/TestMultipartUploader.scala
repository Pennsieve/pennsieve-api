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
import com.amazonaws.services.s3.model.S3ObjectSummary
import com.pennsieve.aws.s3.S3
import com.pennsieve.models.Organization
import com.pennsieve.test.helpers.TestDatabase
import com.pennsieve.test.{
  PersistantTestContainers,
  PostgresDockerContainer,
  S3DockerContainer
}
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.{
  Assertion,
  BeforeAndAfterAll,
  BeforeAndAfterEach,
  PrivateMethodTester,
  Suite
}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import software.amazon.awssdk.auth.credentials.{
  AwsBasicCredentials,
  StaticCredentialsProvider
}
import software.amazon.awssdk.endpoints.Endpoint
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.{ S3Client, S3Configuration }
import software.amazon.awssdk.services.s3.endpoints.{
  S3EndpointParams,
  S3EndpointProvider
}
import software.amazon.awssdk.services.s3.internal.crossregion.endpointprovider.BucketEndpointProvider

import java.net.URI
import java.util.concurrent.CompletableFuture
import scala.jdk.CollectionConverters._
import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, ExecutionContext, Future }

class MockS3EndpointProvider(awsS3EndpointUrl: String)
    extends S3EndpointProvider {
  override def resolveEndpoint(
    endpointParams: S3EndpointParams
  ): CompletableFuture[Endpoint] =
    CompletableFuture.completedFuture(
      Endpoint
        .builder()
        .url(URI.create(awsS3EndpointUrl + "/" + endpointParams.bucket()))
        .build()
    )
}

class TestMultipartUploader
    extends AnyWordSpec
    with Matchers
    with PrivateMethodTester
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with PersistantTestContainers
    with S3DockerContainer
    with ValueHelper {
  self: Suite =>

  implicit var system: ActorSystem = _
  implicit var executionContext: ExecutionContext = _

  var s3: S3 = _
  var s3Client: S3Client = _
  var multipartUploader: MultipartUploader = _
  val maxPartSize = (1024 * 1024).toLong

  val testOrganization: Organization = sampleOrganization

  override def afterStart(): Unit = {
    super.afterStart()

    system = ActorSystem("discover-publish")
    executionContext = system.dispatcher

    println(s"afterStart() s3Container.endpointUrl: ${s3Container.endpointUrl}")
    s3 = new S3(s3Container.s3Client)

    s3Client = {
      val region = Region.US_EAST_1
      val sharedHttpClient = UrlConnectionHttpClient
        .builder()
        .build()
      S3Client.builder
        .region(region)
        .httpClient(sharedHttpClient)
        .endpointOverride(URI.create(s3Container.endpointUrl))
        .endpointProvider(new MockS3EndpointProvider(s3Container.endpointUrl))
        .serviceConfiguration(
          S3Configuration
            .builder()
            .pathStyleAccessEnabled(false)
            .build()
        )
        .credentialsProvider(
          StaticCredentialsProvider.create(
            AwsBasicCredentials
              .create(S3DockerContainer.accessKey, S3DockerContainer.secretKey)
          )
        )
        .build
    }
  }

  override def beforeEach(): Unit = {
    super.beforeEach()

    s3.createBucket(publishBucket).isRight shouldBe true
    s3.createBucket(sourceBucket).isRight shouldBe true

    multipartUploader = MultipartUploader(s3Client, maxPartSize)
  }

  override def afterEach(): Unit = {
    super.afterEach()
    deleteBucket(publishBucket)
    deleteBucket(sourceBucket)
  }

  override def afterAll(): Unit = {
    system.terminate()
    super.afterAll()
  }

  def await[A](e: Future[A]): A = Await.result(e, Duration.Inf)

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

  "multipart uploader" should {
    "generate one part for a file size less than maxPartSize" in {
      val objectSize = maxPartSize - 1
      val parts = PrivateMethod[List[String]](Symbol("parts"))
      val partList = multipartUploader invokePrivate parts(
        0L,
        objectSize,
        maxPartSize,
        List[String]()
      )
      partList.length shouldEqual (1)
    }

    "generate one part for a file size equal to maxPartSize" in {
      val objectSize = maxPartSize
      val parts = PrivateMethod[List[String]](Symbol("parts"))
      val partList = multipartUploader invokePrivate parts(
        0L,
        objectSize,
        maxPartSize,
        List[String]()
      )
      partList.length shouldEqual (1)
    }

    "generate two parts for a file size one byte greater than maxPartSize" in {
      val objectSize = maxPartSize + 1
      val parts = PrivateMethod[List[String]](Symbol("parts"))
      val partList = multipartUploader invokePrivate parts(
        0L,
        objectSize,
        maxPartSize,
        List[String]()
      )
      partList.length shouldEqual (2)
    }

    "generate thirteen parts for a file size 13x maxPartSize" in {
      val multiplier = 13
      val objectSize = maxPartSize * multiplier
      val parts = PrivateMethod[List[String]](Symbol("parts"))
      val partList = multipartUploader invokePrivate parts(
        0L,
        objectSize,
        maxPartSize,
        List[String]()
      )
      partList.length shouldEqual (multiplier)
    }

    "produce expected byteRange for (0, 1024)" in {
      val byteRange = PrivateMethod[String](Symbol("byteRange"))
      val expected = "bytes=0-1023"
      val produced = multipartUploader invokePrivate byteRange(0L, 1024L)
      produced shouldEqual (expected)
    }

    "produce expected byteRange for (1024, 2048)" in {
      val byteRange = PrivateMethod[String](Symbol("byteRange"))
      val expected = "bytes=1024-3071"
      val produced = multipartUploader invokePrivate byteRange(1024L, 2048L)
      produced shouldEqual (expected)
    }

//    "should copy a file" in {
//      val s3Key = "99/66/test.dat"
//      val expectedETag = "test"
//      val expectedSHA256 = "test"
//      createS3File(s3, sourceBucket, s3Key)
//      val completedUploadF = multipartUploader
//        .copy(
//          UploadRequest(
//            sourceBucket = sourceBucket,
//            sourceKey = s3Key,
//            destinationBucket = publishBucket,
//            destinationKey = s3Key
//          )
//        )
//      val completedUpload = await(completedUploadF)
//      println(s"completedUpload: ${completedUpload}")
//    }

  }

}
