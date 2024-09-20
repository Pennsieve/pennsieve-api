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

package com.pennsieve.api

import cats.implicits._
import com.pennsieve.clients.{
  MockJobSchedulingServiceClient,
  MockModelServiceClient
}
import com.pennsieve.dtos.PackageDTO
import com.pennsieve.helpers.{
  DataSetTestMixin,
  MockAuditLogger,
  MockObjectStore
}
import com.pennsieve.models.PackageState.READY
import com.pennsieve.models.{
  CollectionUpload,
  Dataset,
  JobId,
  ModelProperty,
  Package,
  PackageType,
  _
}
import com.pennsieve.uploads.{ PackagePreview, S3File }
import com.pennsieve.web.Settings
import org.json4s.jackson.Serialization.write
import org.scalatest.EitherValues._
import org.scalatest.flatspec.AnyFlatSpec

import scala.concurrent.Future

class TestFilesController
    extends AnyFlatSpec
    with DataSetTestMixin
    with ApiSuite {

  // hack to get around implicit resolution issues with com.pennsieve.test.helpers.EitherValue
  //object EitherSyntax extends cats.syntax.EitherSyntax

  var destination: Package = _
  var tsdestination: Package = _

  var importId = new JobId(java.util.UUID.randomUUID())
  val groupId: String = importId.toString
  var ds: Dataset = _
  var mockModelServiceClient: MockModelServiceClient = _

  val validProxyPayloadBody =
    """
  {
    "conceptId": "e91d-6004-41eb-a98f-bffcdd2158c9",
    "instanceId": "c6fd-6ae1-2770-be24-7d06a7ee5129",
    "targets": [
      {
        "linkTarget": "c6fd-6ae1-2770-be24-7d06a7ee5129",
        "relationshipType": "belongs_to",
        "relationshipDirection": "FromTarget"
      }
    ]
  }
  """

  val files = List(
    S3File(Some(1), "testIMG.img", Some(500L))
      .copy(fileHash = Some(FileHash("abcde12345")), chunkSize = Some(50000L)),
    S3File(Some(2), "testIMG.hdr", Some(500L))
  )

  var dataCollectionId: String = _
  var imagesCollectionId: String = _

  override def beforeEach(): Unit = {
    super.beforeEach()

    ds = createDataSet("Foo")
    destination = packageManager
      .create(
        "Foo",
        PackageType.Collection,
        READY,
        ds,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    tsdestination = packageManager
      .create(
        "TimeSeries",
        PackageType.TimeSeries,
        READY,
        ds,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    mockModelServiceClient.clearCounts()
    // coerce to LocalETLServiceClient here in order to access the
    // payloadsSent property
    //
    // TODO: Look for a way around coercion here
    insecureContainer.jobsClient
      .asInstanceOf[MockJobSchedulingServiceClient]
      .payloadsSent
      .clear()

    dataCollectionId = s"N:collection:${java.util.UUID.randomUUID().toString}"
    imagesCollectionId = s"N:collection:${java.util.UUID.randomUUID().toString}"
  }

  override def afterStart(): Unit = {
    super.afterStart()
    mockModelServiceClient = new MockModelServiceClient()
    addServlet(
      new FilesController(
        insecureContainer,
        secureContainerBuilder,
        system,
        new MockAuditLogger(),
        new MockObjectStore("test.avi"),
        mockModelServiceClient,
        insecureContainer.jobsClient,
        system.dispatcher
      )(swagger),
      "/*"
    )
  }

//  behavior of "File Uploads"
//
//  it should "fail with an invalid import ID" in {
//
//    val preview1 = PackagePreview(
//      "test+IMG",
//      PackageType.Image,
//      "Image",
//      FileType.ANALYZE,
//      files,
//      Iterable(""),
//      1000L,
//      true,
//      importId.value.toString,
//      "Image",
//      None,
//      None,
//      Some("test%2BIMG")
//    )
//
//    val request1 = UploadCompleteRequest(preview1, None)
//
//    postJson(
//      s"/upload/complete/invalid-key-0000-1234?organization_id=${loggedInOrganization.id}&destinationId=${destination.nodeId}&datasetId=${ds.nodeId}",
//      write(request1),
//      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
//    ) {
//      println(response.body)
//      status should equal(400)
//      response.body should include("not a valid UUID")
//    }
//  }
//
//  it should "fail with a malformed POST body" in {
//    postJson(
//      s"/upload/complete/$groupId?organization_id=${loggedInOrganization.id}&destinationId=${destination.nodeId}&datasetId=${ds.nodeId}",
//      """{ "concept": "fooo" }""",
//      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
//    ) {
//      status should equal(400)
//    }
//  }
//
//  it should "succeed with no POST body" in {
//    post(
//      s"/upload/complete/$groupId?organization_id=${loggedInOrganization.id}&destinationId=${tsdestination.nodeId}&datasetId=${ds.nodeId}&append=true",
//      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
//    ) {
//      status should equal(403)
//      response.body should include("package preview must be provided")
//    }
//  }
//
//  it should "send the payload to Job Scheduling Service" in {
//
//    val preview1 = PackagePreview(
//      "test+IMG",
//      PackageType.Image,
//      "Image",
//      FileType.ANALYZE,
//      files,
//      Iterable(""),
//      1000L,
//      true,
//      importId.value.toString,
//      "Image",
//      None,
//      None,
//      Some("test%2BIMG")
//    )
//
//    val request1 = UploadCompleteRequest(preview1, None)
//
//    postJson(
//      s"/upload/complete/$importId?organization_id=${loggedInOrganization.id}"
//        + s"&destinationId=${tsdestination.nodeId}&datasetId=${ds.nodeId}&append=true",
//      write(request1),
//      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
//    ) {
//      status should equal(200)
//
//      assert(
//        (parsedBody \ "package")
//          .extract[List[Option[PackageDTO]]]
//          .headOption
//          .flatten
//          .isDefined
//      )
//
//      val payload: Payload =
//        (parsedBody \ "manifest" \ "content").extract[List[Upload]].head
//      val payloadsSent = insecureContainer.jobsClient
//        .asInstanceOf[MockJobSchedulingServiceClient]
//        .payloadsSent
//
//      payloadsSent should contain(payload)
//    }
//  }
//
//  it should "create a package when hasPreview flag is set to true" in {
//
//    val preview1 = PackagePreview(
//      "test+IMG",
//      PackageType.Image,
//      "Image",
//      FileType.ANALYZE,
//      files,
//      Iterable(""),
//      1000L,
//      true,
//      importId.value.toString,
//      "Image",
//      None,
//      None,
//      Some("test%2BIMG")
//    )
//
//    val request1 = UploadCompleteRequest(preview1, None)
//
//    postJson(
//      s"/upload/complete/$importId?organization_id=${loggedInOrganization.id}"
//        + s"&destinationId=${destination.nodeId}&datasetId=${ds.nodeId}&append=false&uploadService=true&hasPreview=true",
//      write(request1),
//      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
//    ) {
//      status should equal(200)
//
//      val job1 =
//        (parsedBody \ "manifest" \ "content").extract[List[Upload]].head
//
//      // assert that paths are set correctly when uploadService flag is set to true
//      assert(
//        job1.files == List(
//          s"s3://${Settings.s3_upload_bucketName}/${loggedInUser.id}/$importId/testIMG.img",
//          s"s3://${Settings.s3_upload_bucketName}/${loggedInUser.id}/$importId/testIMG.hdr"
//        )
//      )
//
//      val pkg1 = packageManager.get(job1.packageId).await.value
//      val parent1 = packageManager.get(pkg1.parentId.get).await.value
//
//      assert(pkg1.name == "test+IMG")
//      assert(parent1.name == destination.name)
//      assert(parent1.parentId.isEmpty)
//
//      // Check that the corresponding source files were created as well:
//      val srcFiles = fileManager.getSources(pkg1).await.value
//      assert(
//        srcFiles.map(f => (f.name, f.uploadedState, f.checksum)).toSet ==
//          Set(
//            (
//              "testIMG.img",
//              Some(FileState.SCANNING),
//              Some(FileChecksum(50000L, "abcde12345"))
//            ),
//            ("testIMG.hdr", Some(FileState.SCANNING), None)
//          )
//      )
//    }
//  }
//
//  it should "fail when hasPreview flag is set to true and POST body is malformed" in {
//    postJson(
//      s"/upload/complete/$importId?organization_id=${loggedInOrganization.id}"
//        + s"&datasetId=${ds.nodeId}&append=false&hasPreview=true",
//      """{ "testt": "foo" }""",
//      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
//    ) {
//      status should equal(400)
//    }
//  }
//
//  it should "create a package when hasPreview flag is set to true (no destination package)" in {
//
//    val preview1 = PackagePreview(
//      "testIMG",
//      PackageType.Image,
//      "Image",
//      FileType.ANALYZE,
//      files,
//      Iterable(""),
//      1000L,
//      true,
//      importId.value.toString,
//      "Image",
//      None,
//      None
//    )
//
//    val request1 = UploadCompleteRequest(preview1, None)
//
//    postJson(
//      s"/upload/complete/$importId?organization_id=${loggedInOrganization.id}"
//        + s"&datasetId=${ds.nodeId}&append=false&hasPreview=true",
//      write(request1),
//      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
//    ) {
//      status should equal(200)
//
//      val job1 =
//        (parsedBody \ "manifest" \ "content").extract[List[Upload]].head
//      val pkg1 = packageManager.get(job1.packageId).await.value
//
//      assert(pkg1.name == "testIMG")
//      assert(pkg1.parentId.isEmpty)
//
//      // Check that the corresponding source files were created as well:
//      val srcFiles = fileManager.getSources(pkg1).await.value
//      assert(srcFiles.map(_.name).toSet == Set("testIMG.img", "testIMG.hdr"))
//    }
//  }
//
//  it should "create nested collections when hasPreview flag is set to true" in {
//
//    /*
//    Sample folder structure:
//      data/images/testIMG.img
//      data/images/testIMG.hdr
//     */
//
//    val dataCollection =
//      CollectionUpload(dataCollectionId, "data", Some(destination.nodeId), 0)
//    val imagesCollection =
//      CollectionUpload(imagesCollectionId, "images", Some(dataCollectionId), 1)
//    val preview1 = PackagePreview(
//      "testIMG",
//      PackageType.Image,
//      "Image",
//      FileType.ANALYZE,
//      files,
//      Iterable(""),
//      1000L,
//      true,
//      importId.value.toString,
//      "Image",
//      Some(imagesCollection),
//      Some(List(dataCollection))
//    )
//
//    val request1 = UploadCompleteRequest(preview1, None)
//
//    val importId2 = java.util.UUID.randomUUID().toString
//    val files2 = List(S3File(Some(1), "testJPG.jpg", Some(500L)))
//
//    val preview2 = PackagePreview(
//      "testJPG",
//      PackageType.Image,
//      "Image",
//      FileType.ANALYZE,
//      files2,
//      Iterable(""),
//      500L,
//      true,
//      importId2,
//      "Image",
//      Some(imagesCollection),
//      Some(List(dataCollection))
//    )
//
//    val request2 = UploadCompleteRequest(preview2, None)
//
//    val importId3 = java.util.UUID.randomUUID().toString
//    val files3 = List(
//      S3File(Some(1), "testIMG.img", Some(500L)),
//      S3File(Some(2), "testIMG.hdr", Some(500L))
//    )
//
//    val dataCollectionId2 =
//      s"N:collection:${java.util.UUID.randomUUID().toString}"
//    val imagesCollectionId2 =
//      s"N:collection:${java.util.UUID.randomUUID().toString}"
//
//    val dataCollection2 =
//      CollectionUpload(dataCollectionId2, "data", Some(destination.nodeId), 0)
//    val imagesCollection2 =
//      CollectionUpload(
//        imagesCollectionId2,
//        "images",
//        Some(dataCollectionId2),
//        1
//      )
//
//    val preview3 = PackagePreview(
//      "testIMG",
//      PackageType.Image,
//      "Image",
//      FileType.ANALYZE,
//      files3,
//      Iterable(""),
//      500L,
//      true,
//      importId3,
//      "Image",
//      Some(imagesCollection2),
//      Some(List(dataCollection2))
//    )
//
//    val request3 = UploadCompleteRequest(preview3, None)
//
//    postJson(
//      s"/upload/complete/$importId?organization_id=${loggedInOrganization.id}"
//        + s"&destinationId=${destination.nodeId}&datasetId=${ds.nodeId}&append=false&hasPreview=true",
//      write(request1),
//      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
//    ) {
//
//      assert(status == 200)
//
//      val job1 =
//        (parsedBody \ "manifest" \ "content").extract[List[Upload]].head
//      val pkg1 = packageManager.get(job1.packageId).await.value
//      val parent1 = packageManager.get(pkg1.parentId.get).await.value
//      val grandparent1 =
//        packageManager.get(parent1.parentId.get).await.value
//      val greatGrandparent1 =
//        packageManager.get(grandparent1.parentId.get).await.value
//
//      assert(pkg1.name == "testIMG")
//      assert(parent1.name == "images")
//      assert(grandparent1.name == "data")
//      assert(greatGrandparent1.name == destination.name)
//      assert(greatGrandparent1.parentId.isEmpty)
//
//      val srcFiles = fileManager.getSources(pkg1).await.value
//      assert(srcFiles.map(_.name).toSet == Set("testIMG.img", "testIMG.hdr"))
//
//      // upload a package from the same upload session (matching collection ids)
//      postJson(
//        s"/upload/complete/$importId2?organization_id=${loggedInOrganization.id}"
//          + s"&destinationId=${destination.nodeId}&datasetId=${ds.nodeId}&append=false&hasPreview=true",
//        write(request2),
//        headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
//      ) {
//
//        assert(status == 200)
//
//        val job2 =
//          (parsedBody \ "manifest" \ "content").extract[List[Upload]].head
//        val pkg2 = packageManager.get(job2.packageId).await.value
//        val parent2 = packageManager.get(pkg2.parentId.get).await.value
//        val grandparent2 =
//          packageManager.get(parent2.parentId.get).await.value
//        val greatGrandparent2 =
//          packageManager.get(grandparent2.parentId.get).await.value
//
//        assert(pkg2.name == "testJPG")
//        assert(parent2.name == "images")
//        assert(parent2.id == parent1.id)
//        assert(grandparent2.name == "data")
//        assert(grandparent2.id == grandparent1.id)
//        assert(greatGrandparent2.name == destination.name)
//        assert(greatGrandparent2.id == destination.id)
//        assert(greatGrandparent2.parentId.isEmpty)
//
//        val srcFiles = fileManager.getSources(pkg2).await.value
//        assert(srcFiles.head.name == "testJPG.jpg")
//      }
//
//      // upload a package from a different upload session (new collection ids)
//      postJson(
//        s"/upload/complete/$importId3?organization_id=${loggedInOrganization.id}"
//          + s"&destinationId=${destination.nodeId}&datasetId=${ds.nodeId}&append=false&hasPreview=true",
//        write(request3),
//        headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
//      ) {
//        assert(status == 200)
//
//        val job3 =
//          (parsedBody \ "manifest" \ "content").extract[List[Upload]].head
//        val pkg3 = packageManager.get(job3.packageId).await.value
//        val parent3 = packageManager.get(pkg3.parentId.get).await.value
//        val grandparent3 =
//          packageManager.get(parent3.parentId.get).await.value
//        val greatGrandparent3 =
//          packageManager.get(grandparent3.parentId.get).await.value
//
//        assert(pkg3.name == "testIMG")
//        assert(parent3.name == "images")
//        assert(parent3.id != parent1.id)
//        assert(grandparent3.name == "data (1)")
//        assert(grandparent3.id != grandparent1.id)
//        assert(greatGrandparent3.name == destination.name)
//        assert(greatGrandparent3.id == destination.id)
//        assert(greatGrandparent3.parentId.isEmpty)
//
//        val srcFiles = fileManager.getSources(pkg3).await.value
//        assert(srcFiles.map(_.name).toSet == Set("testIMG.img", "testIMG.hdr"))
//      }
//    }
//  }
//
//  it should "create nested collections when hasPreview flag is set to true (no destination package)" in {
//
//    /*
//    Sample folder structure:
//      data/images/testIMG.img
//      data/images/testIMG.hdr
//     */
//
//    val dataCollection =
//      CollectionUpload(dataCollectionId, "data", None, 0)
//    val imagesCollection =
//      CollectionUpload(imagesCollectionId, "images", Some(dataCollectionId), 1)
//    val preview1 = PackagePreview(
//      "testIMG",
//      PackageType.Image,
//      "Image",
//      FileType.ANALYZE,
//      files,
//      Iterable(""),
//      1000L,
//      true,
//      importId.value.toString,
//      "Image",
//      Some(imagesCollection),
//      Some(List(dataCollection))
//    )
//
//    val request1 = UploadCompleteRequest(preview1, None)
//
//    val importId2 = java.util.UUID.randomUUID().toString
//    val files2 = List(S3File(Some(1), "testJPG.jpg", Some(500L)))
//
//    val preview2 = PackagePreview(
//      "testJPG",
//      PackageType.Image,
//      "Image",
//      FileType.ANALYZE,
//      files2,
//      Iterable(""),
//      500L,
//      true,
//      importId2.toString,
//      "Image",
//      Some(imagesCollection),
//      Some(List(dataCollection))
//    )
//
//    val request2 = UploadCompleteRequest(preview2, None)
//
//    val importId3 = java.util.UUID.randomUUID().toString
//
//    val dataCollectionId2 =
//      s"N:collection:${java.util.UUID.randomUUID().toString}"
//    val imagesCollectionId2 =
//      s"N:collection:${java.util.UUID.randomUUID().toString}"
//
//    val dataCollection2 =
//      CollectionUpload(dataCollectionId2, "data", None, 0)
//    val imagesCollection2 =
//      CollectionUpload(
//        imagesCollectionId2,
//        "images",
//        Some(dataCollectionId2),
//        1
//      )
//
//    val preview3 = PackagePreview(
//      "testIMG",
//      PackageType.Image,
//      "Image",
//      FileType.ANALYZE,
//      files,
//      Iterable(""),
//      500L,
//      true,
//      importId3,
//      "Image",
//      Some(imagesCollection2),
//      Some(List(dataCollection2))
//    )
//
//    val request3 = UploadCompleteRequest(preview3, None)
//
//    postJson(
//      s"/upload/complete/$importId?organization_id=${loggedInOrganization.id}"
//        + s"&datasetId=${ds.nodeId}&append=false&hasPreview=true",
//      write(request1),
//      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
//    ) {
//
//      assert(status == 200)
//
//      val job1 =
//        (parsedBody \ "manifest" \ "content").extract[List[Upload]].head
//      val pkg1 = packageManager.get(job1.packageId).await.value
//      val parent1 = packageManager.get(pkg1.parentId.get).await.value
//      val grandparent1 =
//        packageManager.get(parent1.parentId.get).await.value
//
//      assert(pkg1.name == "testIMG")
//      assert(parent1.name == "images")
//      assert(grandparent1.name == "data")
//      assert(grandparent1.parentId.isEmpty)
//
//      val srcFiles = fileManager.getSources(pkg1).await.value
//      assert(srcFiles.map(_.name).toSet == Set("testIMG.img", "testIMG.hdr"))
//
//      // upload a package from the same upload session (matching collection ids)
//      postJson(
//        s"/upload/complete/$importId2?organization_id=${loggedInOrganization.id}"
//          + s"&datasetId=${ds.nodeId}&append=false&hasPreview=true",
//        write(request2),
//        headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
//      ) {
//        assert(status == 200)
//
//        val job2 =
//          (parsedBody \ "manifest" \ "content").extract[List[Upload]].head
//        val pkg2 = packageManager.get(job2.packageId).await.value
//        val parent2 = packageManager.get(pkg2.parentId.get).await.value
//        val grandparent2 =
//          packageManager.get(parent2.parentId.get).await.value
//
//        assert(pkg2.name == "testJPG")
//        assert(parent2.name == "images")
//        assert(parent2.id == parent1.id)
//        assert(grandparent2.name == "data")
//        assert(grandparent2.id == grandparent1.id)
//        assert(grandparent2.parentId.isEmpty)
//
//        val srcFiles = fileManager.getSources(pkg2).await.value
//        assert(srcFiles.head.name == "testJPG.jpg")
//      }
//
//      // upload a package from a different upload session (new collection ids)
//      postJson(
//        s"/upload/complete/$importId3?organization_id=${loggedInOrganization.id}"
//          + s"&datasetId=${ds.nodeId}&append=false&hasPreview=true",
//        write(request3),
//        headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
//      ) {
//        assert(status == 200)
//        val job3 =
//          (parsedBody \ "manifest" \ "content").extract[List[Upload]].head
//        val pkg3 = packageManager.get(job3.packageId).await.value
//        val parent3 = packageManager.get(pkg3.parentId.get).await.value
//        val grandparent3 =
//          packageManager.get(parent3.parentId.get).await.value
//
//        assert(pkg3.name == "testIMG")
//        assert(parent3.name == "images")
//        assert(parent3.id != parent1.id)
//        assert(grandparent3.name == "data (1)")
//        assert(grandparent3.id != grandparent1.id)
//        assert(grandparent3.parentId.isEmpty)
//
//        val srcFiles = fileManager.getSources(pkg3).await.value
//        assert(srcFiles.map(_.name).toSet == Set("testIMG.img", "testIMG.hdr"))
//      }
//    }
//  }
//
//  it should "create a single collection when hasPreview flag is set to true" in {
//
//    /*
//    Sample folder structure:
//      images/testIMG.img
//      images/testIMG.hdr
//     */
//
//    val imagesCollection =
//      CollectionUpload(
//        imagesCollectionId,
//        "images",
//        Some(destination.nodeId),
//        0
//      )
//
//    val preview1 = PackagePreview(
//      "testIMG",
//      PackageType.Image,
//      "Image",
//      FileType.ANALYZE,
//      files,
//      Iterable(""),
//      1000L,
//      true,
//      importId.value.toString,
//      "Image",
//      Some(imagesCollection),
//      None
//    )
//
//    val request1 = UploadCompleteRequest(preview1, None)
//
//    postJson(
//      s"/upload/complete/$importId?organization_id=${loggedInOrganization.id}"
//        + s"&destinationId=${destination.nodeId}&datasetId=${ds.nodeId}&append=false&hasPreview=true",
//      write(request1),
//      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
//    ) {
//      val job1 =
//        (parsedBody \ "manifest" \ "content").extract[List[Upload]].head
//      val pkg1 = packageManager.get(job1.packageId).await.value
//      val parent1 = packageManager.get(pkg1.parentId.get).await.value
//      val grandparent1 =
//        packageManager.get(parent1.parentId.get).await.value
//
//      assert(pkg1.name == "testIMG")
//      assert(parent1.name == "images")
//      assert(grandparent1.name == destination.name)
//      assert(grandparent1.parentId.isEmpty)
//
//      val srcFiles = fileManager.getSources(pkg1).await.value
//      assert(srcFiles.map(_.name).toSet == Set("testIMG.img", "testIMG.hdr"))
//    }
//  }
//
//  it should "create a single collection when hasPreview flag is set to true (no destination package)" in {
//
//    /*
//    Sample folder structure:
//      images/testIMG.img
//      images/testIMG.hdr
//     */
//
//    val imagesCollection =
//      CollectionUpload(imagesCollectionId, "images", None, 0)
//
//    val preview1 = PackagePreview(
//      "testIMG",
//      PackageType.Image,
//      "Image",
//      FileType.ANALYZE,
//      files,
//      Iterable(""),
//      1000L,
//      true,
//      importId.value.toString,
//      "Image",
//      Some(imagesCollection),
//      None
//    )
//
//    val request1 = UploadCompleteRequest(preview1, None)
//
//    postJson(
//      s"/upload/complete/$importId?organization_id=${loggedInOrganization.id}"
//        + s"&datasetId=${ds.nodeId}&append=false&hasPreview=true",
//      write(request1),
//      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
//    ) {
//      val job1 =
//        (parsedBody \ "manifest" \ "content").extract[List[Upload]].head
//      val pkg1 = packageManager.get(job1.packageId).await.value
//      val parent1 = packageManager.get(pkg1.parentId.get).await.value
//
//      assert(pkg1.name == "testIMG")
//      assert(parent1.name == "images")
//      assert(parent1.parentId.isEmpty)
//
//      val srcFiles = fileManager.getSources(pkg1).await.value
//      assert(srcFiles.map(_.name).toSet == Set("testIMG.img", "testIMG.hdr"))
//    }
//  }
//
//  it should "set package attributes appropriately" in {
//
//    val preview1 = PackagePreview(
//      "test",
//      PackageType.Video,
//      "Video",
//      FileType.AVI,
//      files,
//      Iterable(""),
//      1000L,
//      true,
//      importId.value.toString,
//      "Video",
//      None,
//      None,
//      Some("test")
//    )
//
//    val request1 = UploadCompleteRequest(preview1, None)
//
//    postJson(
//      s"/upload/complete/$importId?organization_id=${loggedInOrganization.id}&destinationId=${destination.nodeId}&datasetId=${ds.nodeId}&append=false",
//      write(request1),
//      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
//    ) {
//      val job = (parsedBody \ "manifest" \ "content").extract[List[Upload]].head
//      val pkg = packageManager.get(job.packageId).await.value
//      assert(
//        pkg.attributes == List(
//          ModelProperty("subtype", "Video", "string", "Pennsieve", false, true),
//          ModelProperty("icon", "Video", "string", "Pennsieve", false, true)
//        )
//      )
//    }
//  }
//
//  it should "be idempotent with consecutive requests" in {
//
//    val preview1 = PackagePreview(
//      "test.avi",
//      PackageType.Video,
//      "Video",
//      FileType.AVI,
//      files,
//      Iterable(""),
//      1000L,
//      true,
//      importId.value.toString,
//      "Video",
//      None,
//      None,
//      Some("test.avi")
//    )
//
//    val request1 = UploadCompleteRequest(preview1, None)
//
//    postJson(
//      s"/upload/complete/$importId?organization_id=${loggedInOrganization.id}&destinationId=${destination.nodeId}&datasetId=${ds.nodeId}&append=false",
//      write(request1),
//      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
//    ) {
//      status should equal(200)
//
//      val job = (parsedBody \ "manifest" \ "content").extract[List[Upload]].head
//      val pkg = packageManager.get(job.packageId).await.value
//      assert(pkg.name == "test.avi")
//    }
//  }
//
//  it should "be idempotent and not have race conditions with interleaved requests " in {
//
//    val preview = PackagePreview(
//      "test+IMG.img",
//      PackageType.Image,
//      "Image",
//      FileType.ANALYZE,
//      files,
//      Iterable(""),
//      1000L,
//      true,
//      importId.value.toString,
//      "Image",
//      None,
//      None,
//      Some("test_IMG")
//    )
//
//    val request = UploadCompleteRequest(preview, None)
//
//    val responses = List
//      .fill(5)(Future {
//        postJson(
//          s"/upload/complete/$importId?organization_id=${loggedInOrganization.id}"
//            + s"&destinationId=${destination.nodeId}&datasetId=${ds.nodeId}&append=false&uploadService=true&hasPreview=true",
//          write(request),
//          headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
//        ) {
//          status should equal(200)
//          val job =
//            (parsedBody \ "manifest" \ "content").extract[List[Upload]].head
//          val pkg = packageManager.get(job.packageId).await.value
//          val srcFiles = fileManager.getSources(pkg).await.value
////          assert(srcFiles == 1)
//          assert(
//            srcFiles.map(_.name).toSet == Set("testIMG.img", "testIMG.hdr")
//          )
//        }
//      })
//      .sequence
//      .await
//
//    // All responses should be identical
//    responses.foldLeft(true)((b, r) => b && r == responses.head) shouldBe true
//  }
}
