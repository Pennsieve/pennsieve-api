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

import java.net.URL
import java.time.ZonedDateTime
import java.util.UUID
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import com.pennsieve.clients.MockJobSchedulingServiceClient
import com.pennsieve.domain.StorageAggregation.{
  sdatasets,
  sorganizations,
  susers
}
import com.pennsieve.dtos.{
  DownloadManifestDTO,
  ExtendedPackageDTO,
  FileDTO,
  PackageDTO,
  PagedResponse
}
import com.pennsieve.helpers.{
  DataSetTestMixin,
  MockAuditLogger,
  MockObjectStore,
  MockUrlShortenerClient,
  PackagesTestMixin
}
import com.pennsieve.managers.{ FileManager, PackageManager }
import com.pennsieve.models.PackageState.{
  namesToValuesMap,
  PROCESSING,
  READY,
  UNAVAILABLE,
  UPLOADED
}
import com.pennsieve.models.{
  Dataset,
  File,
  FileChecksum,
  FileObjectType,
  FileProcessingState,
  FileType,
  ModelProperty,
  Package,
  PackageState,
  PackageType,
  Role,
  User
}
import io.circe.parser.decode
import io.circe.Json
import io.circe.syntax._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.write
import org.scalatest.EitherValues._
import org.scalatest.OptionValues._

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

class TestPackagesController
    extends BaseApiTest
    with DataSetTestMixin
    with PackagesTestMixin {

  implicit val httpClient: HttpRequest => Future[HttpResponse] = { _ =>
    Future.successful(HttpResponse())
  }

  var dataPackage: Package = _

  val mockAuditLogger = new MockAuditLogger()

  val mockJobSchedulingServiceClient: MockJobSchedulingServiceClient =
    new MockJobSchedulingServiceClient()

  val mockUrlShortenerClient: MockUrlShortenerClient =
    new MockUrlShortenerClient()

  override def afterStart(): Unit = {
    super.afterStart()

    addServlet(
      new PackagesController(
        insecureContainer,
        secureContainerBuilder,
        mockAuditLogger,
        new MockObjectStore("test.avi"),
        mockJobSchedulingServiceClient,
        mockUrlShortenerClient,
        system,
        system.dispatcher
      ),
      "/*"
    )
  }

  override def beforeEach(): Unit = {

    super.beforeEach()
    dataPackage = packageManager
      .create(
        "Foo",
        PackageType.Collection,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

  }

  test("swagger") {
    import com.pennsieve.web.ResourcesApp
    addServlet(new ResourcesApp, "/api-docs/*")

    get("/api-docs/swagger.json") {
      status should equal(200)
      println(body)
    }
  }

  // Create Packages
  //////////////////////////////////////////////////////////////////////////////

  test("create package") {

    val request = s"""{"name": "New Package",
                       "dataset": "${dataset.nodeId}",
                       "packageType": "PDF",
                       "owner": "${loggedInUser.nodeId}",
                       "properties": [{"key": "meta",
                                       "value": 123,
                                       "dataType": "integer",
                                       "category": "user-defined",
                                       "fixed": false,
                                       "hidden": false}]}"""

    postJson(
      "",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)

      val json = parse(response.body)
      compact(render(json \ "content" \ "name")) should include("New Package")
      compact(render(json \ "content" \ "id")) should include("N:package:")
      compact(render(json \ "content" \ "state")) should include("UNAVAILABLE")
      compact(render(json \ "content" \ "ownerId")) should include(
        s"${loggedInUser.id}"
      )
      compact(render(json \ "properties" \\ "key")) should include("meta")
      compact(render(json \ "properties" \\ "value")) should include("123")
      response.body should include(""""value":123""")
    }

    //creating the same file again should add the file with a different name
    postJson(
      "",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
      val json = parse(response.body)
      compact(render(json \ "content" \ "name")) should include(
        "New Package (1)"
      )
    }
  }

  test(
    "create package as super admin without explicit owner id set will default to super admin's id"
  ) {

    val request = s"""{"name": "New Package",
                       "dataset": "${dataset.nodeId}",
                       "packageType": "PDF",
                       "properties": [{"key": "meta",
                                       "value": 123,
                                       "dataType": "integer",
                                       "category": "user-defined",
                                       "fixed": false,
                                       "hidden": false}]}"""

    postJson(
      "",
      request,
      headers = authorizationHeader(adminJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
      val createdPackage = parsedBody.extract[PackageDTO]
      val pkg = secureContainer.packageManager
        .getByNodeId(createdPackage.content.id)
        .await
        .value
      pkg.ownerId shouldBe Some(superAdmin.id)
    }
  }

  test("create package without dataset permissions should fail") {

    val request = s"""{"name": "New Package",
                       "dataset": "${dataset.nodeId}",
                       "packageType": "PDF",
                       "owner": "${colleagueUser.nodeId}",
                       "properties": [{"key": "meta",
                                       "value": 123,
                                       "dataType": "integer",
                                       "category": "user-defined",
                                       "fixed": false,
                                       "hidden": false}]}"""

    postJson(
      s"",
      request,
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }
  }

  test("create package with dataset permissions should succeed") {
    secureContainer.datasetManager
      .addUserCollaborator(dataset, colleagueUser, Role.Editor)
      .await

    val request = s"""{"name": "New Package",
                       "dataset": "${dataset.nodeId}",
                       "packageType": "PDF",
                       "owner": "${colleagueUser.nodeId}",
                       "properties": [{"key": "meta",
                                       "value": 123,
                                       "dataType": "integer",
                                       "category": "user-defined",
                                       "fixed": false,
                                       "hidden": false}]}"""

    postJson(
      s"",
      request,
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
      val json = parse(response.body)
      compact(render(json \ "content" \ "name")) should include("New Package")
      compact(render(json \ "content" \ "id")) should include("N:package:")
      compact(render(json \ "content" \ "state")) should include("UNAVAILABLE")
      compact(render(json \ "content" \ "ownerId")) should include(
        s"${colleagueUser.id}"
      )
      compact(render(json \ "properties" \\ "key")) should include("meta")
      compact(render(json \ "properties" \\ "value")) should include("123")
      response.body should include(""""value":123""")
    }
  }

  test("create package without properties") {

    val request = s"""{"name": "New Package no props",
                       "dataset": "${dataset.nodeId}",
                       "packageType": "PDF" ,
                       "owner": "${loggedInUser.nodeId}"}"""

    postJson(
      "",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
    }
  }

  test("create package with empty name should fail") {

    val request = s"""{"name": "",
                       "dataset": "${dataset.nodeId}",
                       "packageType": "PDF",
                       "owner": "${loggedInUser.nodeId}"
                       }"""

    postJson(
      "",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
    }
  }

  test("create package without name fails") {

    val request = s"""{"dataset": "${dataset.nodeId}",
                       "packageType": "PDF",
                       "owner": "${loggedInUser.nodeId}"}"""

    postJson(
      "",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
    }
  }

  test("create package ignores id") {

    val request = s"""{"id": "fakeid",
                       "name": "New Package",
                       "dataset": "${dataset.nodeId}",
                       "packageType": "PDF",
                       "owner": "${loggedInUser.nodeId}"}"""

    postJson(
      "",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)

      val json = parse(response.body)

      compact(render(json)) should not include ("fakeid")
      compact(render(json \ "content" \ "id")) should include("N:package:")
    }
  }

  test("create package without packageType fails") {

    val request = s"""{"name": "New Package",
                       "dataset": "${dataset.nodeId}",
                       "owner": "${loggedInUser.nodeId}"}"""

    postJson(
      "",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
    }
  }

  test("create package with state works for super admin") {

    val request = s"""{"name": "New Package",
                       "dataset": "${dataset.nodeId}",
                       "packageType": "PDF",
                       "owner": "${loggedInUser.nodeId}",
                       "state": "READY"}"""

    postJson(
      "",
      request,
      headers = jwtServiceAuthorizationHeader(loggedInOrganization) ++ traceIdHeader()
    ) {
      status should equal(201)

      val json = parse(response.body)

      compact(render(json \ "content" \ "state")) should include("READY")
    }
  }

  test("create package with state does nothing for regular user") {

    val request = s"""{"name": "New Package",
                       "dataset": "${dataset.nodeId}",
                       "packageType": "PDF",
                       "owner": "${loggedInUser.nodeId}",
                       "state": "READY"}"""

    postJson(
      "",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)

      val json = parse(response.body)

      compact(render(json \ "content" \ "state")) should include("UNAVAILABLE")
    }
  }

  test("create package with external file works") {
    val request = s"""{"name": "New Package",
                       "dataset": "${dataset.nodeId}",
                       "packageType": "EXTERNAL",
                       "owner": "${loggedInUser.nodeId}",
                       "state": "READY",
                       "externalLocation": "https://www.dropbox.com/my-external-file",
                       "description": "An extraordinary file from my dropbox"}"""

    postJson(
      "",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)

      val json = parse(response.body)

      val `package` =
        Await
          .result(
            packageManager
              .getByNodeId(
                compact(render(json \ "content" \ "id")).replace("\"", "")
              )
              .value,
            10 seconds
          )
          .value

      val externalFiles =
        Await
          .result(
            externalFileManager
              .get(`package`)
              .value,
            10 seconds
          )
          .value

      externalFiles.location shouldBe "https://www.dropbox.com/my-external-file"
      externalFiles.description shouldBe Some(
        "An extraordinary file from my dropbox"
      )
    }
  }

  test("updating an package with external file works") {
    val createRequest = s"""{"name": "Test Package",
                       "dataset": "${dataset.nodeId}",
                       "packageType": "EXTERNAL",
                       "owner": "${loggedInUser.nodeId}",
                       "state": "READY",
                       "externalLocation": "https://www.dropbox.com/my-external-file",
                       "description": "low energy description"}"""

    val packageNodeId =
      postJson(
        "",
        createRequest,
        headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
      ) {
        status should equal(201)
        val json = parse(response.body)
        compact(render(json \ "content" \ "id")).replace("\"", "")
      }

    val updateRequest = s"""{"name": "Test Package",
                       "dataset": "${dataset.nodeId}",
                       "packageType": "EXTERNAL",
                       "owner": "${loggedInUser.nodeId}",
                       "state": "READY",
                       "externalLocation": "file:///home/ascended/to/the/cloud",
                       "description": "!HIGH ENERGY DESCRIPTION!"}"""
    putJson(
      s"/${packageNodeId}",
      updateRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val json = parse(response.body)

      val `package` =
        Await
          .result(
            packageManager
              .getByNodeId(
                compact(render(json \ "content" \ "id")).replace("\"", "")
              )
              .value,
            10 seconds
          )
          .value

      val externalFiles =
        Await
          .result(
            externalFileManager
              .get(`package`)
              .value,
            10 seconds
          )
          .value

      externalFiles.location shouldBe "file:///home/ascended/to/the/cloud"
      externalFiles.description shouldBe Some("!HIGH ENERGY DESCRIPTION!")
    }
  }

  test("updating an package without a trace ID works") {
    val createRequest = s"""{"name": "Test Package",
                       "dataset": "${dataset.nodeId}",
                       "packageType": "EXTERNAL",
                       "owner": "${loggedInUser.nodeId}",
                       "state": "READY",
                       "externalLocation": "https://www.dropbox.com/my-external-file",
                       "description": "whatever"}"""

    val packageNodeId =
      postJson(
        "",
        createRequest,
        headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
      ) {
        status should equal(201)
        val json = parse(response.body)
        compact(render(json \ "content" \ "id")).replace("\"", "")
      }

    val updateRequest = s"""{"name": "Test Package",
                       "dataset": "${dataset.nodeId}",
                       "packageType": "EXTERNAL",
                       "owner": "${loggedInUser.nodeId}",
                       "state": "READY",
                       "externalLocation": "file:///home/ascended/to/the/cloud",
                       "description": "!HIGH ENERGY DESCRIPTION!"}"""
    putJson(
      s"/${packageNodeId}",
      updateRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val json = parse(response.body)
    }
  }

  // Update Packages
  //////////////////////////////////////////////////////////////////////////////

  test(
    "update package name and packageType without parent or properties in request"
  ) {

    val pdfPackage = packageManager
      .create(
        "Foo1",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    val request = """{"name": "Updated Package",
                      "packageType": "MRI"}"""

    putJson(
      s"/${pdfPackage.nodeId}",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val json = parse(response.body)

      compact(render(json \ "content" \ "id")) should include("N:package:")
      compact(render(json \ "content" \\ "name")) should include(
        "Updated Package"
      )
      compact(render(json \ "content" \\ "packageType")) should include("PDF") //package type ignored for non super users
    }

    putJson(
      s"/${pdfPackage.nodeId}",
      request,
      headers = authorizationHeader(adminJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val json = parse(response.body)

      compact(render(json \ "content" \ "id")) should include("N:package:")
      compact(render(json \ "content" \\ "name")) should include(
        "Updated Package"
      )
      compact(render(json \ "content" \\ "packageType")) should include("MRI") //package type ignored for non super users
    }
  }

  test("update package merges properties with existing properties") {

    val props = List(
      ModelProperty("meta", "data", "string", "user-defined"),
      ModelProperty("other", "unchanged", "string", "user-definied")
    )

    val pdfPackage = packageManager
      .create(
        "Foo2",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None,
        attributes = props
      )
      .await
      .value

    val request = """{"name": "Updated Package",
                       "packageType": "PDF",
                       "properties": [{"key": "meta",
                                       "value": "secrets",
                                       "dataType": "string",
                                       "category": "user-defined",
                                       "fixed": true,
                                       "hidden": true}]}"""

    putJson(
      s"/${pdfPackage.nodeId}",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val json = parse(response.body)

      compact(render(json \ "content" \ "id")) should include("N:package:")
      compact(render(json \ "content" \\ "name")) should include(
        "Updated Package"
      )
      compact(render(json \ "content" \\ "packageType")) should include("PDF")
      compact(render(json \ "properties" \\ "value")) should not include ("data")
      compact(render(json \ "properties" \\ "value")) should include(
        "unchanged"
      )
      compact(render(json \ "properties" \\ "value")) should include("secrets")
    }
  }

  test("update package merges properties with existing properties using circe") {

    val props = List(
      ModelProperty("meta", "data", "string", "user-defined"),
      ModelProperty("other", "unchanged", "string", "user-definied")
    )

    val pdfPackage = packageManager
      .create(
        "Foo2",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None,
        attributes = props
      )
      .await
      .value

    val request = """{"name": "Updated Package",
                       "packageType": "PDF",
                       "properties": [{"key": "meta",
                                       "value": "secrets",
                                       "dataType": "string",
                                       "category": "user-defined",
                                       "fixed": true,
                                       "hidden": true}]}"""

    putJson(
      s"/${pdfPackage.nodeId}",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      decode[PackageDTO](body).isRight shouldBe true
    }
  }

  test("update package works without packageType") {
    val pdfPackage = packageManager
      .create(
        "Foo4",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    val request = """{"name": "Updated Package2"}"""

    putJson(
      s"/${pdfPackage.nodeId}",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val json = parse(response.body)

      compact(render(json \ "content" \\ "name")) should include(
        "Updated Package2"
      )
      compact(render(json \ "content" \\ "packageType")) should include("PDF")
    }
  }

  test("update package works without name") {
    val pdfPackage = packageManager
      .create(
        "Foo5",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    val request = """{"packageType": "MRI"}"""

    putJson(
      s"/${pdfPackage.nodeId}",
      request,
      headers = authorizationHeader(adminJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val json = parse(response.body)

      compact(render(json \ "content" \\ "name")) should include("Foo5")
      compact(render(json \ "content" \\ "packageType")) should include("MRI")
    }
  }

  test("update package users cannot update state") {
    val pdfPackage = packageManager
      .create(
        "Foo6",
        PackageType.PDF,
        UNAVAILABLE,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    val request = """{"state": "READY"}"""

    putJson(
      s"/${pdfPackage.nodeId}",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val json = parse(response.body)

      compact(render(json \ "content" \\ "state")) should include("UNAVAILABLE")

      val pdf = packageManager.getByNodeId(pdfPackage.nodeId).await.value

      pdf.state should equal(UNAVAILABLE)
    }
  }

  test("update package service users can update state with a JWT") {
    val pdfPackage = packageManager
      .create(
        "Foo6",
        PackageType.PDF,
        UNAVAILABLE,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    val request = """{"state": "READY"}"""

    putJson(
      s"/${pdfPackage.nodeId}",
      request,
      headers = jwtServiceAuthorizationHeader(loggedInOrganization) ++ traceIdHeader()
    ) {
      status should equal(200)

      val json = parse(response.body)

      compact(render(json \ "content" \\ "state")) should include("READY")

      val pdf = packageManager.getByNodeId(pdfPackage.nodeId).await.value

      pdf.state should equal(READY)
    }
  }

  test("update package updates state with a numeric id") {
    val pdfPackage = packageManager
      .create(
        "Foo6",
        PackageType.PDF,
        UNAVAILABLE,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    val request = """{"state": "READY"}"""

    putJson(
      s"/${pdfPackage.id}",
      request,
      headers = jwtServiceAuthorizationHeader(loggedInOrganization) ++ traceIdHeader()
    ) {
      status should equal(200)

      val json = parse(response.body)

      compact(render(json \ "content" \\ "state")) should include("READY")

      val pdf = packageManager.getByNodeId(pdfPackage.nodeId).await.value

      pdf.state should equal(READY)
    }
  }

  test(
    "update package works without name or packageType but still updates properties"
  ) {

    val props = List(
      ModelProperty("meta", "data", "string", "user-defined"),
      ModelProperty("other", "unchanged", "string", "user-definied")
    )

    val pdfPackage = packageManager
      .create(
        "Foo8",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None,
        attributes = props
      )
      .await
      .value

    val request = """{"properties": [{"key": "meta",
                                      "value": "secrets",
                                      "dataType": "string",
                                      "category": "user-defined",
                                      "fixed": true,
                                      "hidden": true}]}"""

    putJson(
      s"/${pdfPackage.nodeId}",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val json = parse(response.body)

      compact(render(json \ "content" \\ "name")) should include("Foo8")
      compact(render(json \ "content" \\ "packageType")) should include("PDF")
      compact(render(json \ "properties" \\ "value")) should not include ("data")
      compact(render(json \ "properties" \\ "value")) should include(
        "unchanged"
      )
      compact(render(json \ "properties" \\ "value")) should include("secrets")
    }
  }

  test("update package name works with super admin") {
    val pdfPackage = packageManager
      .create(
        "Foo1",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    val request = """{"name":"Updated Package","state": "READY","uploader":"""" + loggedInUser.id + """"}"""

    putJson(
      s"/${pdfPackage.nodeId}",
      request,
      headers = authorizationHeader(adminJwt) ++ organizationHeader(
        loggedInOrganization.nodeId
      ) ++ traceIdHeader()
    ) {
      status should equal(200)
    }
  }

  test(
    "update package name with multiple source file should not updates file names"
  ) {
    val oldName = s"testFileOldName"
    val testPackage = packageManager
      .create(
        oldName,
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    val filesManager = new FileManager(packageManager, loggedInOrganization)
    val testFile = (1 to 2).toList.map { idx =>
      filesManager
        .create(
          name = s"$oldName$idx",
          `type` = FileType.BFTS,
          `package` = testPackage,
          s3Bucket = "testBucket",
          s3Key = "testKey",
          objectType = FileObjectType.Source,
          processingState = FileProcessingState.Unprocessed,
          size = 1
        )
        .await
        .value
    }

    val request = """{"name":"Updated Package","state": "READY","uploader":"""" + loggedInUser.id + """"}"""

    putJson(
      s"/${testPackage.nodeId}",
      request,
      headers = jwtServiceAuthorizationHeader(loggedInOrganization) ++ traceIdHeader()
    ) {
      status should equal(200)

      val updatedFile =
        filesManager.get(testFile.head.id, testPackage).await.value
      updatedFile.name should not equal ("Updated Package")

    }
  }

  test("update package name with single source updates file name") {
    val oldName = s"testFileOldName"
    val testPackage = packageManager
      .create(
        oldName,
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    val filesManager = new FileManager(packageManager, loggedInOrganization)
    val testFile = filesManager
      .create(
        name = oldName,
        `type` = FileType.BFTS,
        `package` = testPackage,
        s3Bucket = "testBucket",
        s3Key = "testKey",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        size = 1
      )
      .await
      .value

    val request = """{"name":"Updated Package","state": "READY","uploader":"""" + loggedInUser.id + """"}"""

    putJson(
      s"/${testPackage.nodeId}",
      request,
      headers = jwtServiceAuthorizationHeader(loggedInOrganization) ++ traceIdHeader()
    ) {
      status should equal(200)

      val updatedFile =
        filesManager.get(testFile.id, testPackage).await.value
      updatedFile.name should equal("Updated Package")

    }
  }

  test("update package name ignores package in DELETING state") {
    val pdfPackage = packageManager
      .create(
        "Foo1",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    val otherPdfPackage = packageManager
      .create(
        "Updated Package",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    val request = """{"name":"Updated Package 2","state": "READY","uploader":"""" + loggedInUser.id + """"}"""

    // Test no longer required, guarunteed by db constraint
    // putJson(
    //   s"/${pdfPackage.nodeId}",
    //   request,
    //   headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    // ) {
    //   status should equal(400)
    //   response.body should include("package name must be unique")
    // }

    secureContainer.packageManager
      .update(otherPdfPackage.copy(state = PackageState.DELETING))
      .await
      .value

    putJson(
      s"/${pdfPackage.nodeId}",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

  }

  test("unshared users cannot update packages") {
    val pdfPackage = packageManager
      .create(
        "Foo1",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    val request = """{"name": "Updated Package",
                      "packageType": "MRI"}"""

    putJson(
      s"/${pdfPackage.nodeId}",
      request,
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }

    putJson(
      s"/${pdfPackage.nodeId}",
      request,
      headers = authorizationHeader(externalJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  test(
    "update package with the updateStorage flag set to true updates the storage cache"
  ) {
    val pkg = packageManager
      .create(
        "Foo1",
        PackageType.TimeSeries,
        PackageState.UNAVAILABLE,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    val fileSize = 10
    val numberOfFiles = 5
    val filesManager = new FileManager(packageManager, loggedInOrganization)
    val bftsFiles = (1 to numberOfFiles).toList.map { idx =>
      filesManager
        .create(
          name = s"test$idx",
          `type` = FileType.BFTS,
          `package` = pkg,
          s3Bucket = "testBucket",
          s3Key = "testKey",
          objectType = FileObjectType.Source,
          processingState = FileProcessingState.Unprocessed,
          size = fileSize
        )
        .await
        .value
    }

    val request = """{"state": "READY"}"""

    putJson(
      s"/${pkg.nodeId}?updateStorage",
      request,
      headers = jwtServiceAuthorizationHeader(loggedInOrganization) ++ traceIdHeader()
    ) {
      status should equal(200)

      val result = parsedBody.extract[PackageDTO]

      val totalSize = fileSize * numberOfFiles
      result.storage.value should be(totalSize)
      // check dataset storage value
      storageManager
        .getStorage(sdatasets, List(dataset.id))
        .await
        .value
        .get(dataset.id)
        .value
        .value should be(totalSize)
      // check organization storage value
      storageManager
        .getStorage(sorganizations, List(loggedInOrganization.id))
        .await
        .value
        .get(loggedInOrganization.id)
        .value
        .value should be(totalSize)

      // TODO: revert this once user storage is re-implemented
      // check user storage value
      // storageManager
      //   .getStorage(susers, List(loggedInUser.id))
      //   .await
      //   .value
      //   .get(loggedInUser.id)
      //   .value
      //   .value should be(totalSize)
    }
  }

  // Get Packages
  //////////////////////////////////////////////////////////////////////////////

  test("gets a package - simple") {
    val props = List(
      ModelProperty("meta", "data", "string", "user-defined"),
      ModelProperty("other", "unchanged", "string", "user-defined")
    )

    val pdfPackage = packageManager
      .create(
        "Foo10",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None,
        attributes = props
      )
      .await
      .value

    get(
      s"/${pdfPackage.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val json = parse(response.body)

      compact(render(json \ "content" \ "id")) should include("N:package:")
      compact(render(json \ "content" \\ "name")) should include("Foo10")
      compact(render(json \ "content" \\ "packageType")) should include("PDF")
      compact(render(json \ "properties" \\ "key")) should include("meta")
      compact(render(json \ "properties" \\ "value")) should include("data")
      compact(render(json)) should not include ("objects")
    }
  }

  test("get package is paginated and returns default number of children") {
    // create a dataset
    val ds = createDataSet(
      name = "test-dataset-for-testing-package-pagination",
      description = Some("test-dataset-for-testing-package-pagination")
    )
    // create a collection package (folder)
    val collection = createPackage(ds, "Folder")
    // create packages in the collection
    (1 to PackagesController.PackageChildrenDefaultLimit + 1)
      .map(n => createPackage(ds, s"Package-${n}", parent = Some(collection)))

    get(
      s"/${collection.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val `package` = parsedBody.extract[PackageDTO]
      `package`.children.length shouldBe PackagesController.PackageChildrenDefaultLimit
    }
  }

  test("get package is paginated and returns requested number of children") {
    // create a dataset
    val ds = createDataSet(
      name = "test-dataset-for-testing-package-pagination",
      description = Some("test-dataset-for-testing-package-pagination")
    )
    // create a collection package (folder)
    val collection = createPackage(ds, "Folder")
    // create packages in the collection
    (1 to 26).map(
      n => createPackage(ds, s"Package-${n}", parent = Some(collection))
    )

    get(
      s"/${collection.nodeId}?offset=0&limit=5",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val `package` = parsedBody.extract[PackageDTO]
      `package`.children.length shouldBe 5
    }
  }

  test(
    "get package is paginated and last page may provide fewer children than requested"
  ) {
    // create a dataset
    val ds = createDataSet(
      name = "test-dataset-for-testing-package-pagination",
      description = Some("test-dataset-for-testing-package-pagination")
    )
    // create a collection package (folder)
    val collection = createPackage(ds, "Folder")
    // create packages in the collection
    (1 to 26).map(
      n => createPackage(ds, s"Package-${n}", parent = Some(collection))
    )

    get(
      s"/${collection.nodeId}?offset=25&limit=5",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val `package` = parsedBody.extract[PackageDTO]
      `package`.children.length shouldBe 1
    }
  }

  test("paginated max limit on get package requests") {
    // create a dataset
    val ds = createDataSet("test-dataset-ppackage-pagination-limit")
    // create a collection package (folder)
    val collection = createPackage(ds, "Folder")
    (1 to PackagesController.PackageChildrenMaxLimit + 2)
      .map(n => createPackage(ds, s"Package-${n}", parent = Some(collection)))

    get(
      s"/${collection.nodeId}?offset=0&limit=${PackagesController.PackageChildrenMaxLimit + 1}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val `package` = parsedBody.extract[PackageDTO]
      `package`.children.length shouldBe PackagesController.PackageChildrenMaxLimit
    }
  }

  test("packages with a single file should contain a file extension") {
    val props = List(
      ModelProperty("meta", "data", "string", "user-defined"),
      ModelProperty("other", "unchanged", "string", "user-defined")
    )

    val dataset = secureDataSetManager
      .create("Test", Some("Test Description"))
      .await
      .value

    val pdfPackage = packageManager
      .create(
        "Foo10",
        PackageType.PDF,
        UNAVAILABLE,
        dataset,
        Some(loggedInUser.id),
        None,
        attributes = props
      )
      .await
      .value

    fileManager
      .create(
        "index.pdf",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "1/2/3/index.pdf",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await

    get(
      s"/${pdfPackage.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val json = parse(response.body)
      compact(render(json)) should include("extension")
      compact(render(json \\ "extension")) should include("pdf")
    }
  }

  test("packages with multiple files should not contain a file extension") {
    val props = List(
      ModelProperty("meta", "data", "string", "user-defined"),
      ModelProperty("other", "unchanged", "string", "user-defined")
    )

    val dataset = secureDataSetManager
      .create("Test", Some("Test Description"))
      .await
      .value

    val pdfPackage = packageManager
      .create(
        "Foo10",
        PackageType.PDF,
        UNAVAILABLE,
        dataset,
        Some(loggedInUser.id),
        None,
        attributes = props
      )
      .await
      .value

    fileManager
      .create(
        "index.pdf",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "1/2/3/index.pdf",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await

    fileManager
      .create(
        "another.pdf",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "1/2/3/another.pdf",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await

    get(
      s"/${pdfPackage.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val json = parse(response.body)
      compact(render(json)) should not include ("extension")
    }
  }

  test(
    "updates a package state when the package is unavailable with state in Job Scheduling Service"
  ) {
    val props = List(
      ModelProperty("meta", "data", "string", "user-defined"),
      ModelProperty("other", "unchanged", "string", "user-defined")
    )

    val pdfPackage = packageManager
      .create(
        "Foo10",
        PackageType.PDF,
        UNAVAILABLE,
        dataset,
        Some(loggedInUser.id),
        None,
        attributes = props
      )
      .await
      .value

    get(
      s"/${pdfPackage.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val json = parse(response.body)
      compact(render(json \ "content" \\ "state")) should include("PROCESSING")
      compact(render(json \ "content" \ "id")) should include("N:package:")
      compact(render(json \ "content" \\ "name")) should include("Foo10")
      compact(render(json \ "content" \\ "packageType")) should include("PDF")
      compact(render(json \ "properties" \\ "key")) should include("meta")
      compact(render(json \ "properties" \\ "value")) should include("data")
      compact(render(json)) should not include ("objects")
    }
  }

  test(
    "does not update a package state when the package is unavailable but state is not returned by the Job Scheduling Service"
  ) {
    val props = List(
      ModelProperty("meta", "data", "string", "user-defined"),
      ModelProperty("other", "unchanged", "string", "user-defined")
    )

    val dataset2 = secureDataSetManager
      .create("Home Again", Some("Home Again Dataset"))
      .await
      .value
    //the mock JSS client will returned a not found answer to a getPackageState call
    // on a package belonging to a dataset with id = 2
    //the state of the package should remain UNAVAILABLE

    val pdfPackage = packageManager
      .create(
        "Foo10",
        PackageType.PDF,
        UNAVAILABLE,
        dataset2,
        Some(loggedInUser.id),
        None,
        attributes = props
      )
      .await
      .value

    get(
      s"/${pdfPackage.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val json = parse(response.body)
      compact(render(json \ "content" \\ "state")) should include("UNAVAILABLE")
      compact(render(json \ "content" \ "id")) should include("N:package:")
      compact(render(json \ "content" \\ "name")) should include("Foo10")
      compact(render(json \ "content" \\ "packageType")) should include("PDF")
      compact(render(json \ "properties" \\ "key")) should include("meta")
      compact(render(json \ "properties" \\ "value")) should include("data")
      compact(render(json)) should not include ("objects")
    }
  }

  test(
    "gets a package and resets channel start times with the startAtEpoch flag"
  ) {
    val timeseriesPackage = packageManager
      .create(
        "SensitiveTimeSeries",
        PackageType.TimeSeries,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    val channel1 = timeSeriesManager
      .createChannel(
        timeseriesPackage,
        "Sensisitve Channel1",
        300000,
        600000,
        "unit",
        10.0,
        "type",
        None,
        0
      )
      .await
      .value
    val channel2 = timeSeriesManager
      .createChannel(
        timeseriesPackage,
        "Sensisitve Channel2",
        400000,
        700000,
        "unit",
        10.0,
        "type",
        None,
        0
      )
      .await
      .value

    get(
      s"/${timeseriesPackage.nodeId}?startAtEpoch=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val packageDTO = parse(response.body).extract[PackageDTO]
      val startTimes = packageDTO.channels.get.map(_.content.start)
      val endTimes = packageDTO.channels.get.map(_.content.end)

      startTimes should contain theSameElementsAs List(0, 100000)
      endTimes should contain theSameElementsAs List(300000, 400000)
    }
  }

  test("NotFound") {
    get(
      s"/N:collection:invalid-ffe2-4213-ae1a-4fed890bcba6",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  test("gets a package plus its data nodes") {
    val pdfPackage = packageManager
      .create(
        "Foo11",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    fileManager
      .create(
        "Source File",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "s3Path",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await

    fileManager
      .create(
        "Converted File",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "s3Path",
        objectType = FileObjectType.File,
        processingState = FileProcessingState.NotProcessable,
        0
      )
      .await

    get(
      s"/${pdfPackage.nodeId}?include=view,files,sources",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val json = parse(response.body)

      compact(render(json \ "content" \ "id")) should include("N:package:")
      compact(render(json \ "content" \\ "name")) should include("Foo11")
      compact(render(json \ "objects" \ "source" \\ "content" \\ "name")) should include(
        "Source File"
      )
      compact(render(json \ "objects" \ "source" \\ "content" \\ "name")) should not include ("Converted File")
      compact(render(json \ "objects" \ "source" \\ "content" \\ "fileType")) should include(
        "PDF"
      )
      compact(render(json \ "objects" \ "file" \\ "content" \\ "name")) should include(
        "Converted File"
      )
      compact(render(json \ "objects" \ "file" \\ "content" \\ "name")) should not include ("Source File")
      compact(render(json \ "objects" \ "file" \\ "content" \\ "fileType")) should include(
        "PDF"
      )
      compact(render(json \ "objects" \ "view" \\ "content" \\ "name")) should not include ("Converted File")
      compact(render(json \ "objects" \ "view" \\ "content" \\ "name")) should not include ("Source File")
      compact(render(json \ "objects" \ "view")) should equal("[]")
    }
  }

  test("gets a package but selectively filters its data nodes") {
    val pdfPackage = packageManager
      .create(
        "Foo11.A",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    fileManager
      .create(
        "Source File",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "s3Path",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await

    fileManager
      .create(
        "Converted File",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "s3Path",
        objectType = FileObjectType.File,
        processingState = FileProcessingState.NotProcessable,
        0
      )
      .await

    fileManager
      .create(
        "View File",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "s3Path",
        objectType = FileObjectType.View,
        processingState = FileProcessingState.NotProcessable,
        0
      )
      .await

    get(
      s"/${pdfPackage.nodeId}?include=view,sources",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val json = parse(response.body)

      compact(render(json \ "content" \ "id")) should include("N:package:")
      compact(render(json \ "content" \\ "name")) should include("Foo11")
      compact(render(json \ "objects" \ "source" \\ "content" \\ "name")) should include(
        "Source File"
      )
      compact(render(json \ "objects" \ "source" \\ "content" \\ "name")) should not include ("Converted File")
      compact(render(json \ "objects" \ "source" \\ "content" \\ "fileType")) should include(
        "PDF"
      )
      compact(render(json \ "objects" \ "file" \\ "content" \\ "name")) should not include ("Converted File")
      compact(render(json \ "objects" \ "file" \\ "content" \\ "name")) should not include ("Source File")
      compact(render(json \ "objects" \ "file")) should equal("[]")
      compact(render(json \ "objects" \ "view" \\ "content" \\ "name")) should include(
        "View File"
      )
      compact(render(json \ "objects" \ "view" \\ "content" \\ "name")) should not include ("Converted File")
      compact(render(json \ "objects" \ "view" \\ "content" \\ "name")) should not include ("Source File")
      compact(render(json \ "objects" \ "view" \\ "content" \\ "fileType")) should include(
        "PDF"
      )
    }
  }

  test("rejects non-alphanumeric includes") {
    val pdfPackage = packageManager
      .create(
        "Foo12",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    get(
      s"/${pdfPackage.nodeId}?include=v+iew,fil%20es,sources",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)

      response.body should include("include parameters must be alpha-numeric")
    }

    get(
      s"/${pdfPackage.nodeId}?include=view,files:.;,sources",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)

      response.body should include("include parameters must be alpha-numeric")
    }
  }

  test("includes a timeseries package's channels") {
    val timeseriesPackage = packageManager
      .create(
        "Foo13",
        PackageType.TimeSeries,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    val channel = timeSeriesManager
      .createChannel(
        timeseriesPackage,
        "Channel Faker",
        0,
        1000,
        "unit",
        10.0,
        "type",
        None,
        0
      )
      .await
      .value

    get(
      s"/${timeseriesPackage.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val json = parse(response.body)
      compact(render(json \ "channels" \\ "content" \\ "name")) should include(
        "Channel Faker"
      )
    }
  }

  test("includes a collection package's channels") {
    val collectionPackage = packageManager
      .create(
        "Foo13b",
        PackageType.Collection,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    val channel = timeSeriesManager
      .createChannel(
        collectionPackage,
        "Collection Channel",
        0,
        1000,
        "unit",
        10.0,
        "type",
        None,
        0
      )
      .await
      .value

    get(
      s"/${collectionPackage.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val json = parse(response.body)
      compact(render(json \ "channels" \\ "content" \\ "name")) should include(
        "Collection Channel"
      )
    }
  }

  // Get Package Objects
  //////////////////////////////////////////////////////////////////////////////

  test("gets a package's ancestors") {
    val pdfPackage = packageManager
      .create(
        "Foo14",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        Some(dataPackage)
      )
      .await
      .value

    get(
      s"/${pdfPackage.nodeId}?includeAncestors=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val json = parse(response.body)
      compact(render(json \ "ancestors")) should include(dataPackage.nodeId)
    }
  }

  test("gets a package's parent as a DTO") {
    val childPackage = packageManager
      .create(
        "Foo100",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        Some(dataPackage)
      )
      .await
      .value

    get(
      s"/${childPackage.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val dto = parsedBody.extract[PackageDTO]
      val parent = dto.parent.get
      parent.content.id should equal(dataPackage.nodeId)
      parent.content.packageType should equal(PackageType.Collection)
    }
  }

  test("getting a collection should include its children") {
    val childPackage = packageManager
      .create(
        "Foo101",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        Some(dataPackage)
      )
      .await
      .value

    get(
      s"/${dataPackage.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      parsedBody.extract[PackageDTO].children.map(_.content.id) should contain(
        childPackage.nodeId
      )
    }
  }

  test("unshared users cannot get packages") {

    val pdfPackage = packageManager
      .create(
        "Foo14",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        Some(dataPackage)
      )
      .await
      .value

    get(
      s"/${pdfPackage.nodeId}",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }
    get(
      s"/${pdfPackage.nodeId}",
      headers = authorizationHeader(externalJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  test("sources-paged appends the correct extension for single files") {
    val pdfPackage = packageManager
      .create(
        "foo",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    fileManager
      .create(
        "foo.txt", // mismatched extension
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "/path/to/foo.pdf",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await

    get(
      s"/${pdfPackage.nodeId}/sources-paged",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val sources = parsedBody.extract[PagedResponse[FileDTO]]
      sources.results.length should equal(1)
      sources.results.head.content.filename should equal("foo.txt.pdf")
    }
  }

  test("sources-paged ensures source files only have a single extension") {
    val pdfPackage = packageManager
      .create(
        "foo",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    fileManager
      .create(
        "foo.pdf",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "/path/to/foo.pdf",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await

    get(
      s"/${pdfPackage.nodeId}/sources-paged",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val sources = parsedBody.extract[PagedResponse[FileDTO]]
      sources.results.length should equal(1)
      sources.results.head.content.filename should equal("foo.pdf")
    }
  }

  test(
    "gets package sources with limit and offset for legacy and paged responses"
  ) {

    val pdfPackage = packageManager
      .create(
        "Foo15",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    (1 to 200).map(
      _ =>
        fileManager
          .create(
            "Source File",
            FileType.PDF,
            pdfPackage,
            "s3bucketName",
            "/path/to/test.pdf",
            objectType = FileObjectType.Source,
            processingState = FileProcessingState.Unprocessed,
            0
          )
          .await
    )

    get(
      s"/${pdfPackage.nodeId}/sources",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val sources = parsedBody.extract[List[FileDTO]]
      sources.length should equal(100)

    }

    get(
      s"/${pdfPackage.nodeId}/sources-paged",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val sources = parsedBody.extract[PagedResponse[FileDTO]]
      sources.results.length should equal(100)

    }

    get(
      s"/${pdfPackage.nodeId}/sources?limit=10",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val sources = parsedBody.extract[List[FileDTO]]
      sources.length should equal(10)

    }

    get(
      s"/${pdfPackage.nodeId}/sources-paged?limit=10",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val sources = parsedBody.extract[PagedResponse[FileDTO]]
      sources.results.length should equal(10)

    }

    get(
      s"/${pdfPackage.nodeId}/sources?offset=101",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val sources = parsedBody.extract[List[FileDTO]]
      sources.length should equal(99)

    }

    get(
      s"/${pdfPackage.nodeId}/sources-paged?offset=101",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val sources = parsedBody.extract[PagedResponse[FileDTO]]
      sources.results.length should equal(99)

    }
  }

  test("paginated max limit on sources-paged") {
    val pdfPackage = packageManager
      .create(
        "Foo15",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    (1 to PackagesController.FILES_LIMIT_MAX + 2).map(
      _ =>
        fileManager
          .create(
            "Source File",
            FileType.PDF,
            pdfPackage,
            "s3bucketName",
            "/path/to/test.pdf",
            objectType = FileObjectType.Source,
            processingState = FileProcessingState.Unprocessed,
            0
          )
          .await
    )

    get(
      s"/${pdfPackage.nodeId}/sources-paged?limit=${PackagesController.FILES_LIMIT_MAX + 1}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val sources = parsedBody.extract[PagedResponse[FileDTO]]
      sources.results.length should equal(PackagesController.FILES_LIMIT_MAX)
    }
  }

  test(
    "sources-paged returns new columns properties, assetType, and provenanceId"
  ) {
    val testProvenanceId = UUID.randomUUID()
    val testProperties = Json.obj("key" -> "value".asJson)
    val testAssetType = "image"

    val pdfPackage = packageManager
      .create(
        "foo",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    fileManager
      .create(
        "test.pdf",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "/path/to/test.pdf",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        size = 100,
        properties = Some(testProperties),
        assetType = Some(testAssetType),
        provenanceId = Some(testProvenanceId)
      )
      .await

    get(
      s"/${pdfPackage.nodeId}/sources-paged",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      // Parse the response using json4s instead of trying to extract to DTO
      val json = parse(response.body)
      val results = (json \ "results").extract[List[JValue]]
      results.length should equal(1)

      val fileContent = results.head \ "content"

      // Check assetType
      (fileContent \ "assetType").extract[Option[String]] should equal(
        Some(testAssetType)
      )

      // Check provenanceId
      (fileContent \ "provenanceId").extract[Option[String]] should equal(
        Some(testProvenanceId.toString)
      )

      // Check properties exists and has the expected structure
      val properties = fileContent \ "properties"
      properties should not equal JNothing
      (properties \ "key").extract[String] should equal("value")
    }
  }

  test("sources-paged order-by and order-by direction works correctly TESTME") {

    val pdfPackage = packageManager
      .create(
        "Foo15",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    val createdFiles = (1 to 10).toList.map { idx =>
      fileManager
        .create(
          s"Source File $idx",
          FileType.PDF,
          pdfPackage,
          "s3bucketName",
          "/path/to/test.pdf",
          objectType = FileObjectType.Source,
          processingState = FileProcessingState.Unprocessed,
          idx
        )
        .await
        .value
    }

    get(
      s"/${pdfPackage.nodeId}/sources-paged?order-by=name",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val sources = parsedBody.extract[PagedResponse[FileDTO]]
      sources.results.length should equal(10)

      sources.results.map(_.content.name) shouldBe createdFiles
        .map(_.name)
        .sorted
    }

    get(
      s"/${pdfPackage.nodeId}/sources-paged?order-by=name&order-by-direction=desc",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val sources = parsedBody.extract[PagedResponse[FileDTO]]
      sources.results.length should equal(10)

      sources.results.map(_.content.name) shouldBe createdFiles
        .map(_.name)
        .sorted
        .reverse
    }

    get(
      s"/${pdfPackage.nodeId}/sources-paged?order-by=size",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val sources = parsedBody.extract[PagedResponse[FileDTO]]
      sources.results.length should equal(10)

      sources.results.map(_.content.size) shouldBe createdFiles
        .map(_.size)
        .sorted
    }

    get(
      s"/${pdfPackage.nodeId}/sources-paged?order-by=createdAt",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val sources = parsedBody.extract[PagedResponse[FileDTO]]
      sources.results.length should equal(10)

      // https://stackoverflow.com/a/50395814
      implicit val localDateOrdering: Ordering[ZonedDateTime] = _ compareTo _

      sources.results.map(_.content.createdAt) shouldBe createdFiles
        .map(_.createdAt)
        .sorted
    }
  }

  test("gets a package's sources") {
    val pdfcollection = packageManager
      .create(
        "PDFCollection",
        PackageType.Collection,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    val pdfPackage = packageManager
      .create(
        "Foo15",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        Some(pdfcollection)
      )
      .await
      .value
    // File #1
    fileManager
      .create(
        "test",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "/path/to/test.txt",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0,
        fileChecksum = Some(FileChecksum(100000000L, "abcdefghijk1234567"))
      )
      .await
    // File #2
    fileManager
      .create(
        "test2",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "/path/to/test2.ome.tiff",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0,
        fileChecksum = Some(FileChecksum(5555555L, "lmnopqrstuvwxyz456789"))
      )
      .await
    // File #3
    fileManager
      .create(
        "test3",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "path/to/test3.tar.gz",
        objectType = FileObjectType.File,
        processingState = FileProcessingState.NotProcessable,
        0,
        fileChecksum = None
      )
      .await

    get(
      s"/${pdfPackage.nodeId}/sources",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val files = parsedBody.extract[List[FileDTO]]

      val ids = files.map(_.content.id)
      ids should have length 2
      ids should contain(1)
      ids should contain(2)
      val filenames = files.map(_.content.filename)
      filenames should have length 2
      filenames should contain("test.txt")
      filenames should contain("test2.ome.tiff")
      val names = files.map(_.content.name)
      names should contain("test")
      names should contain("test2")
      names should not contain ("test3")
      val checksums = files.map(_.content.checksum)
      checksums should have length 2
      checksums should contain(
        Some(FileChecksum(100000000L, "abcdefghijk1234567"))
      )
      checksums should contain(
        Some(FileChecksum(5555555L, "lmnopqrstuvwxyz456789"))
      )
    }
  }

  test("unshared users cannot get package sources") {

    val pdfPackage = packageManager
      .create(
        "Foo15",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    // File #1
    fileManager
      .create(
        "Source File",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "s3Path",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await
    // File #2
    fileManager
      .create(
        "Converted File",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "s3Path",
        objectType = FileObjectType.File,
        processingState = FileProcessingState.NotProcessable,
        0
      )
      .await

    get(
      s"/${pdfPackage.nodeId}/sources",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }
    get(
      s"/${pdfPackage.nodeId}/sources",
      headers = authorizationHeader(externalJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  test("gets a package's files with limit and offset") {
    val pdfPackage = packageManager
      .create(
        "Foo16",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    (1 to 200).map(
      _ =>
        fileManager
          .create(
            "Source File",
            FileType.PDF,
            pdfPackage,
            "s3bucketName",
            "/path/to/test.pdf",
            objectType = FileObjectType.Source,
            processingState = FileProcessingState.Unprocessed,
            0
          )
          .await
    )

    get(
      s"/${pdfPackage.nodeId}/files",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {

      status should equal(200)

      val files: List[FileDTO] = parsedBody.extract[List[FileDTO]]
      files.length should equal(100)
    }

    get(
      s"/${pdfPackage.nodeId}/files?limit=30",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val files: List[FileDTO] = parsedBody.extract[List[FileDTO]]
      files.length should equal(30)

    }

    get(
      s"/${pdfPackage.nodeId}/files?offset=101",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val files: List[FileDTO] = parsedBody.extract[List[FileDTO]]
      files.length should equal(99)
    }
  }

  test("gets a package's files") {
    val pdfPackage = packageManager
      .create(
        "Foo16",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    // File #1
    fileManager
      .create(
        "Source File",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "s3Path",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await
    // File #2
    fileManager
      .create(
        "Source File Two",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "s3Path",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await
    // File #3
    fileManager
      .create(
        "Converted File",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "s3Path",
        objectType = FileObjectType.File,
        processingState = FileProcessingState.NotProcessable,
        0
      )
      .await

    get(
      s"/${pdfPackage.nodeId}/files",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val json = parse(response.body)

      (json \ "content" \ "id").values shouldBe a[List[_]]
      val ids = (json \ "content" \ "id").values.asInstanceOf[List[BigInt]]
      ids should have length 1
      ids should contain(3)
      compact(render(json \ "content" \\ "name")) should include(
        "Converted File"
      )
      compact(render(json \ "content" \\ "name")) should not include ("Source File")
      compact(render(json \ "content" \\ "name")) should not include ("Source File Two")
    }
  }

  test("download-manifest produces a manifest for a simple package") {
    val rootPackage =
      createTestDownloadPackage("root", packageType = PackageType.Image)
    createTestDownloadFile("file1", rootPackage)
    createTestDownloadFile("file2.pdf", rootPackage)

    val request = s"""{"nodeIds": ["${rootPackage.nodeId}"]}"""

    postJson(
      s"/download-manifest",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {

      status should equal(200)

      val json = parse(response.body)

      // it is actually important that the "header" be the first key in the json, since the service
      // consuming this endpoint is going to stream the json and want to  check the size and count up front
      json.extract[Map[String, Any]].keySet.head shouldBe "header"

      val payload = json.extract[DownloadManifestDTO]
      payload.header.size shouldBe 20
      payload.header.count shouldBe 2
      payload.data.length shouldBe 2

      val fileNames = payload.data.map(_.fileName)
      fileNames.contains("file1") shouldBe true
      fileNames.contains("file2.pdf") shouldBe true

      val packageNames = payload.data.map(_.packageName).toSet
      packageNames shouldBe Set("root")
    }
  }

  test(
    "download-manifest produces a manifest for a simple package with one file"
  ) {
    val rootPackage =
      createTestDownloadPackage(
        "file1.ome.tiff",
        packageType = PackageType.Image
      )
    createTestDownloadFile("file1.ome.tiff", rootPackage)

    val request = s"""{"nodeIds": ["${rootPackage.nodeId}"]}"""

    postJson(
      s"/download-manifest",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {

      status should equal(200)

      val json = parse(response.body)

      // it is actually important that the "header" be the first key in the json, since the service
      // consuming this endpoint is going to stream the json and want to  check the size and count up front
      json.extract[Map[String, Any]].keySet.head shouldBe "header"

      val payload = json.extract[DownloadManifestDTO]
      payload.header.size shouldBe 10
      payload.header.count shouldBe 1
      payload.data.length shouldBe 1

      payload.data.head.fileExtension shouldBe Some("ome.tiff")

      payload.data.head.fileName shouldBe ("file1.ome.tiff")

      payload.data.head.packageName shouldBe ("file1.ome.tiff")

    }
  }

  test("download-manifest produces a manifest for a root empty collection") {
    val emptyPackage =
      createTestDownloadPackage("root", packageType = PackageType.Collection)

    val request = s"""{"nodeIds": ["${emptyPackage.nodeId}"]}"""

    postJson(
      s"/download-manifest",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val json = parse(response.body)

      // it is actually important that the "header" be the first key in the json, since the service
      // consuming this endpoint is going to stream the json and want to  check the size and count up front
      json.extract[Map[String, Any]].keySet.head shouldBe "header"

      val payload = json.extract[DownloadManifestDTO]
      payload.header.size shouldBe 0
      payload.header.count shouldBe 0
      payload.data.length shouldBe 0

      val packageNames = payload.data.map(_.packageName).toSet
      packageNames shouldBe Set() // empty collections are omitted
    }
  }

  test("download-manifest produces a manifest with a nested empty collection") {
    val root = createTestDownloadPackage("root")
    val child1 =
      createTestDownloadPackage(
        "child1.pdf",
        Some(root),
        packageType = PackageType.Image
      )
    createTestDownloadFile("child1.pdf", child1)
    val child2 =
      createTestDownloadPackage("child2", Some(root))

    val request = s"""{"nodeIds": ["${root.nodeId}"]}"""

    postJson(
      s"/download-manifest",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val json = parse(response.body)

      // it is actually important that the "header" be the first key in the json, since the service
      // consuming this endpoint is going to stream the json and want to  check the size and count up front
      json.extract[Map[String, Any]].keySet.head shouldBe "header"

      val payload = json.extract[DownloadManifestDTO]
      payload.header.size shouldBe 10
      payload.header.count shouldBe 1
      payload.data.length shouldBe 1

      val fileNames = payload.data.map(_.fileName)
      fileNames.contains("child1.pdf") shouldBe true

      val packageNames = payload.data.map(_.packageName).toSet
      packageNames shouldBe Set("child1.pdf")
    }
  }

  test(
    "download-manifest produces a manifest for a simple package with file filter"
  ) {
    val rootPackage =
      createTestDownloadPackage("root", packageType = PackageType.Image)
    createTestDownloadFile("file1.pdf", rootPackage)
    val file2 = createTestDownloadFile("file2", rootPackage)

    val request =
      s"""{"nodeIds": ["${rootPackage.nodeId}"], "fileIds": [${file2.id}]}"""

    postJson(
      s"/download-manifest",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {

      status should equal(200)

      val json = parse(response.body)

      val payload = json.extract[DownloadManifestDTO]
      payload.header.size shouldBe 10
      payload.header.count shouldBe 1
      payload.data.length shouldBe 1

      val fileNames = payload.data.map(_.fileName)
      fileNames.contains("file1.pdf") shouldBe false
      fileNames.contains("file2") shouldBe true
    }
  }

  test("download-manifest only returns source files") {
    val rootPackage =
      createTestDownloadPackage("root", packageType = PackageType.Image)
    createTestDownloadFile(
      "source.jpg",
      rootPackage,
      objectType = FileObjectType.Source,
      processingState = FileProcessingState.Processed
    )
    createTestDownloadFile(
      "view.jpg",
      rootPackage,
      objectType = FileObjectType.View,
      processingState = FileProcessingState.NotProcessable
    )
    createTestDownloadFile(
      "file.jpg",
      rootPackage,
      objectType = FileObjectType.File,
      processingState = FileProcessingState.NotProcessable
    )

    val request = s"""{"nodeIds": ["${rootPackage.nodeId}"]}"""

    postJson(
      s"/download-manifest",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {

      status should equal(200)

      val payload = parsedBody.extract[DownloadManifestDTO]
      payload.header.size shouldBe 10
      payload.header.count shouldBe 1
      payload.data.map(_.fileName) should contain theSameElementsAs List(
        "source.jpg"
      )
      payload.data.map(_.packageName) should contain theSameElementsAs List(
        "root"
      )
    }
  }

  test(
    "download-manifest given collections at multiple levels, produces a download manifest that represents the correct hierarchy"
  ) {

    /**
      * root.1 (SELECTED, Collection)
      *    child.1 (Image)
      *        childFile
      *    child.C1 (Collection)
      *        grandChild.1 (2 files, Image)
      *            grandChildFile1
      *            grandChildFile2
      *        grandChild.2 (1 file, Image)
      *            grandChildFileFlat
      */
    val rootCollection1 = createTestDownloadPackage("root.1")

    val childCollection1 =
      createTestDownloadPackage(
        "childFile.pdf",
        Some(rootCollection1),
        packageType = PackageType.Image
      )
    createTestDownloadFile("childFile.pdf", childCollection1)

    val childCollectionC1 =
      createTestDownloadPackage("child.C1", Some(rootCollection1))

    val grandChildCollection1 =
      createTestDownloadPackage(
        "grandChild.1",
        Some(childCollectionC1),
        packageType = PackageType.Image
      )
    createTestDownloadFile("grandChildFile1.pdf", grandChildCollection1)
    createTestDownloadFile("grandChildFile2", grandChildCollection1)

    val grandChildCollection2 = createTestDownloadPackage(
      "grandChildFileFlat.pdf",
      Some(childCollectionC1),
      PackageType.Image
    )
    createTestDownloadFile("grandChildFileFlat.pdf", grandChildCollection2)

    /**
      * root.2 (Collection)
      *    child.2 (SELECTED, Collection)
      *        grandChild.C2 (Image)
      *           grandChildFileNoRoot
      */
    val rootCollection2 = createTestDownloadPackage("root.2")
    val childCollection2 =
      createTestDownloadPackage("child.2", Some(rootCollection2))
    val grandChildCollectionC2 = createTestDownloadPackage(
      "childFileNoRoot.pdf",
      Some(childCollection2),
      PackageType.Image
    )
    createTestDownloadFile("childFileNoRoot.pdf", grandChildCollectionC2)

    /**
      * root.3 (SELECTED, Image)
      *    rootFile
      */
    val rootCollection3 =
      createTestDownloadPackage("rootFile.pdf", packageType = PackageType.Image)
    createTestDownloadFile("rootFile.pdf", rootCollection3)

    val request =
      s"""{"nodeIds": ["${rootCollection1.nodeId}", "${childCollection2.nodeId}", "${rootCollection3.nodeId}"]}"""

    postJson(
      s"/download-manifest",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val json = parse(response.body)

      val payload = json.extract[DownloadManifestDTO]
      payload.data.length shouldBe 6
      payload.header.count shouldBe 6

      payload.data
        .find(e => e.fileName == "childFile.pdf")
        .get
        .path should equal(List("root.1"))
      payload.data
        .find(e => e.fileName == "grandChildFile1.pdf")
        .get
        .path should equal(List("root.1", "child.C1", "grandChild.1"))
      payload.data
        .find(e => e.fileName == "grandChildFile2")
        .get
        .path should equal(List("root.1", "child.C1", "grandChild.1"))
      payload.data
        .find(e => e.fileName == "grandChildFileFlat.pdf")
        .get
        .path should equal(List("root.1", "child.C1"))
      payload.data
        .find(e => e.fileName == "childFileNoRoot.pdf")
        .get
        .path should equal(List("child.2"))
      payload.data
        .find(e => e.fileName == "rootFile.pdf")
        .get
        .path should equal(List.empty)

    }
  }

  test("download-manifest can generate archives that cross datasets") {
    val rootPackage =
      createTestDownloadPackage("file1.pdf", packageType = PackageType.Image)
    createTestDownloadFile("file1.pdf", rootPackage)
    val dataset2 = secureDataSetManager
      .create("Another", Some("Another Dataset"))
      .await
      .value
    val rootPackage2 = createTestDownloadPackage(
      "file2.pdf",
      dataset = dataset2,
      packageType = PackageType.Image
    )
    createTestDownloadFile("file2.pdf", rootPackage2)

    val request =
      s"""{"nodeIds": ["${rootPackage.nodeId}", "${rootPackage2.nodeId}"]}"""

    postJson(
      s"/download-manifest",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val json = parse(response.body)

      val payload = json.extract[DownloadManifestDTO]
      payload.header.size shouldBe 20
      payload.header.count shouldBe 2
      payload.data.length shouldBe 2

      val fileNames = payload.data.map(_.fileName)
      fileNames.contains("file2.pdf") shouldBe true
      fileNames.contains("file1.pdf") shouldBe true
    }
  }

  test(
    "download-manifest returns bad request if one of the passed nodeIds does not exist"
  ) {
    val rootPackage = createTestDownloadPackage("root")
    createTestDownloadFile("file1.pdf", rootPackage)

    val request =
      s"""{"nodeIds": ["${rootPackage.nodeId}", "foobar"]}"""

    postJson(
      s"/download-manifest",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
    }
  }

  test(
    "download-manifest returns bad request if access to the dataset of an external org is requested"
  ) {
    val rootPackage =
      createTestDownloadPackage("root", packageType = PackageType.Image)
    createTestDownloadFile("file1.pdf", rootPackage)

    val requestWithAuthorizedPackage =
      s"""{"nodeIds": ["${rootPackage.nodeId}"]}"""

    postJson(
      s"/download-manifest",
      requestWithAuthorizedPackage,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    val externalOrgSecureContainer =
      secureContainerBuilder(externalUser, externalOrganization)

    externalOrgSecureContainer.datasetStatusManager.resetDefaultStatusOptions.await.value

    val externalDataset = externalOrgSecureContainer.datasetManager
      .create("Other", Some("Other Dataset"))
      .await
      .value

    val externalPackage =
      createTestDownloadPackage(
        "other",
        dataset = externalDataset,
        user = externalUser,
        packageManager = externalOrgSecureContainer.packageManager
      )
    createTestDownloadFile(
      "fileOther.pdf",
      externalPackage,
      fileManager = externalOrgSecureContainer.fileManager
    )

    val requestWithExternalPackage =
      s"""{"nodeIds": ["${rootPackage.nodeId}", "${externalPackage.nodeId}"]}"""

    // the package in another org simply won't be found, so we end up sending in 2 node id's but only finding 1
    postJson(
      s"/download-manifest",
      requestWithExternalPackage,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
    }
  }

  test(
    "download-manifest returns unauthorized if access to an unauthorized package is requested"
  ) {
    val colleagueDataset = secureContainer.datasetManager
      .create("colleague")
      .await
      .value

    secureContainer.datasetManager
      .switchOwner(colleagueDataset, loggedInUser, colleagueUser)
      .await
      .value

    val colleaguePackage =
      createTestDownloadPackage(
        "colleague",
        user = colleagueUser,
        dataset = colleagueDataset,
        packageType = PackageType.Image
      )
    createTestDownloadFile("fileColleague.pdf", colleaguePackage)

    val requestWithColleaguePackage =
      s"""{"nodeIds": ["${colleaguePackage.nodeId}"]}"""

    postJson(
      s"/download-manifest",
      requestWithColleaguePackage,
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    val otherPackage =
      createTestDownloadPackage("other", packageType = PackageType.Image)
    createTestDownloadFile("fileOther.pdf", otherPackage)

    val requestWithUnauthorizedPackage =
      s"""{"nodeIds": ["${colleaguePackage.nodeId}", "${otherPackage.nodeId}"]}"""

    postJson(
      s"/download-manifest",
      requestWithUnauthorizedPackage,
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }
  }

  test("gets a package's sources if it has no files") {
    val pdfPackage = packageManager
      .create(
        "Foo17",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    // File #1
    fileManager
      .create(
        "Source File",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "s3Path",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await
    // File #2
    fileManager
      .create(
        "Source File Two",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "s3Path",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await

    get(
      s"/${pdfPackage.nodeId}/files",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val json = parse(response.body)

      (json \ "content" \ "id").values shouldBe a[List[_]]
      val ids = (json \ "content" \ "id").values.asInstanceOf[List[BigInt]]
      ids should have length 2
      ids should contain(1)
      ids should contain(2)
      compact(render(json \ "content" \\ "name")) should include("Source File")
      compact(render(json \ "content" \\ "name")) should include(
        "Source File Two"
      )
      compact(render(json \ "ancestors")) should not include (dataPackage.nodeId)
    }
  }

  test("unshared users cannot get package's files") {
    val pdfPackage = packageManager
      .create(
        "Foo16",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    // File #1
    fileManager
      .create(
        "Source File",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "s3Path",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await
    // File #2
    fileManager
      .create(
        "Converted File",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "s3Path",
        objectType = FileObjectType.File,
        processingState = FileProcessingState.NotProcessable,
        0
      )
      .await

    get(
      s"/${pdfPackage.nodeId}/files",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }

    get(
      s"/${pdfPackage.nodeId}/files",
      headers = authorizationHeader(externalJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  test("gets a package's view with limit and offset") {
    val pdfPackage = packageManager
      .create(
        "Foo18",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    (1 to 200).map(
      _ =>
        fileManager
          .create(
            "Source File",
            FileType.PDF,
            pdfPackage,
            "s3bucketName",
            "/path/to/test.pdf",
            objectType = FileObjectType.Source,
            processingState = FileProcessingState.Unprocessed,
            0
          )
          .await
    )

    get(
      s"/${pdfPackage.nodeId}/view",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val views: List[FileDTO] = parsedBody.extract[List[FileDTO]]
      views.length should equal(100)
    }

    get(
      s"/${pdfPackage.nodeId}/view?limit=20",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val views: List[FileDTO] = parsedBody.extract[List[FileDTO]]
      views.length should equal(20)

    }

    get(
      s"/${pdfPackage.nodeId}/view?offset=101",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val views: List[FileDTO] = parsedBody.extract[List[FileDTO]]
      views.length should equal(99)
    }
  }

  test("gets a package's view") {
    val pdfPackage = packageManager
      .create(
        "Foo18",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    // File #1
    fileManager
      .create(
        "Source File",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "s3Path",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await
    // File #2
    fileManager
      .create(
        "Converted File",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "s3Path",
        objectType = FileObjectType.File,
        processingState = FileProcessingState.NotProcessable,
        0
      )
      .await
    // File #3
    fileManager
      .create(
        "View File",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "s3Path",
        objectType = FileObjectType.View,
        processingState = FileProcessingState.NotProcessable,
        0
      )
      .await

    get(
      s"/${pdfPackage.nodeId}/view",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val json = parse(response.body)

      (json \ "content" \ "id").values shouldBe a[List[_]]
      val ids = (json \ "content" \ "id").values.asInstanceOf[List[BigInt]]
      ids should have length 1
      ids should contain(3)
      compact(render(json \ "content" \\ "name")) should include("View File")
      compact(render(json \ "content" \\ "name")) should not include ("Converted File")
      compact(render(json \ "content" \\ "name")) should not include ("Source File")
      compact(render(json \ "ancestors")) should not include (dataPackage.nodeId)
    }
  }

  test("unshared users cannot get a package's views") {
    val pdfPackage = packageManager
      .create(
        "Foo18",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    // File #1
    fileManager
      .create(
        "Source File",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "s3Path",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await
    // File #2
    fileManager
      .create(
        "Converted File",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "s3Path",
        objectType = FileObjectType.File,
        processingState = FileProcessingState.NotProcessable,
        0
      )
      .await
    // File #3
    fileManager
      .create(
        "View File",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "s3Path",
        objectType = FileObjectType.View,
        processingState = FileProcessingState.NotProcessable,
        0
      )
      .await

    get(
      s"/${pdfPackage.nodeId}/view",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }

    get(
      s"/${pdfPackage.nodeId}/view",
      headers = authorizationHeader(externalJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  test("gets a package's files if it has no view") {
    val pdfPackage = packageManager
      .create(
        "Foo19",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    // File #1
    fileManager
      .create(
        "Source File",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "s3Path",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await
    // File #2
    fileManager
      .create(
        "Converted File",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "s3Path",
        objectType = FileObjectType.File,
        processingState = FileProcessingState.NotProcessable,
        0
      )
      .await

    get(
      s"/${pdfPackage.nodeId}/view",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val json = parse(response.body)

      (json \ "content" \ "id").values shouldBe a[List[_]]
      val ids = (json \ "content" \ "id").values.asInstanceOf[List[BigInt]]
      ids should have length 1
      ids should contain(2)
      compact(render(json \ "content" \\ "name")) should include(
        "Converted File"
      )
      compact(render(json \ "content" \\ "name")) should not include ("Source File")
      compact(render(json \ "ancestors")) should not include (dataPackage.nodeId)
    }
  }

  test("gets a package's sources if it has no view or files") {
    val pdfPackage = packageManager
      .create(
        "Foo20",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    // File #1
    fileManager
      .create(
        "Source File",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "s3Path",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await

    get(
      s"/${pdfPackage.nodeId}/view",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val json = parse(response.body)

      (json \ "content" \ "id").values shouldBe a[List[_]]
      val ids = (json \ "content" \ "id").values.asInstanceOf[List[BigInt]]
      ids should have length 1
      ids should contain(1)
      compact(render(json \ "content" \\ "name")) should include("Source File")
      compact(render(json \ "ancestors")) should not include (dataPackage.nodeId)
    }
  }

  test("get presigned url") {
    val file = fileManager
      .create(
        "Source File",
        FileType.PDF,
        dataPackage,
        "s3bucketName",
        "s3Path",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await
      .value

    get(
      s"/${dataPackage.nodeId}/files/${file.id}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody
        .extract[DownloadItemResponse]
        .url shouldBe "file://s3bucketName/s3Path"
    }
  }

  test("get shortened presigned url") {
    val file = fileManager
      .create(
        "Source File",
        FileType.PDF,
        dataPackage,
        "s3bucketName",
        "s3Path",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await
      .value

    get(
      s"/${dataPackage.nodeId}/files/${file.id}?short=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody
        .extract[DownloadItemResponse]
        .url shouldBe "https://bit.ly/short"

      mockUrlShortenerClient.shortenedUrls.head shouldBe
        new URL("file://s3bucketName/s3Path")
    }
  }

  test("unshared users cannot get presigned urls") {
    val pdfPackage = packageManager
      .create(
        "Foo18",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    // File #1
    val file1 = fileManager
      .create(
        "Source File",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "s3Path",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await
      .value
    // File #2
    fileManager
      .create(
        "View File",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "s3Path",
        objectType = FileObjectType.View,
        processingState = FileProcessingState.NotProcessable,
        0
      )
      .await

    get(
      s"/${pdfPackage.nodeId}/files/${file1.id}",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }

    get(
      s"/${pdfPackage.nodeId}/files/${file1.id}",
      headers = authorizationHeader(externalJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  test("get a collection with its ancestors") {
    val ds = createDataSet("Foo28")
    val collection = packageManager
      .create(
        "Foo26",
        PackageType.Collection,
        READY,
        ds,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    val subdataset = packageManager
      .create(
        "Foo27",
        PackageType.Collection,
        READY,
        ds,
        Some(loggedInUser.id),
        Some(collection)
      )
      .await
      .value
    val subdataset2 = packageManager
      .create(
        "Foo27",
        PackageType.Collection,
        READY,
        ds,
        Some(loggedInUser.id),
        Some(subdataset)
      )
      .await
      .value

    get(
      s"/${subdataset2.nodeId}?includeAncestors=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      body should include("ancestors")
      compactRender(parsedBody \ "ancestors") should include("Foo26")
      compactRender(parsedBody \ "ancestors") should include("Foo27")
    }
  }

  test("return unauthorized when setting package storage without a token") {
    val ds = createDataSet("Foo28")
    val collection = packageManager
      .create(
        "Foo26",
        PackageType.Collection,
        READY,
        ds,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    val request = """{ "size": 1000 }"""

    putJson(
      s"/${collection.id}/storage",
      request,
      headers = Map(
        AuthenticatedController.organizationHeaderKey -> loggedInOrganization.nodeId
      )
    ) {
      status should equal(401)
    }
  }

  test("return forbidden when setting package storage with a non-service claim") {
    val ds = createDataSet("Foo28")
    val collection = packageManager
      .create(
        "Foo26",
        PackageType.Collection,
        READY,
        ds,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    val request = """{ "size": 1000 }"""

    putJson(
      s"/${collection.id}/storage",
      request,
      headers = authorizationHeader(loggedInJwt) + (AuthenticatedController.organizationHeaderKey -> loggedInOrganization.nodeId) ++ traceIdHeader()
    ) {
      status should equal(403)
    }
  }

  test("successfully set package storage with a jwt") {
    val ds = createDataSet("Foo28")
    val collection = packageManager
      .create(
        "Foo26",
        PackageType.Collection,
        READY,
        ds,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    val request = """{ "size": 1000 }"""

    putJson(
      s"/${collection.id}/storage",
      request,
      headers = jwtServiceAuthorizationHeader(loggedInOrganization)
    ) {
      status should equal(200)
      parsedBody
        .extract[SetStorageResponse]
        .storageUse
        .values
        .toSet should equal(Set(1000))
    }
  }

  // upload-complete

  test("set package state to UPLOADED") {
    val pkg = packageManager
      .create(
        "Foo53394",
        PackageType.TimeSeries,
        UNAVAILABLE,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    fileManager
      .create(
        "file",
        FileType.BFTS,
        pkg,
        "bucket",
        "path/to/file.bfts",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await

    put(
      s"/${pkg.nodeId}/upload-complete?user_id=${loggedInUser.id}",
      headers = jwtServiceAuthorizationHeader(loggedInOrganization)
    ) {
      status should equal(200)
    }

    val updatedPackage: Package =
      packageManager.get(pkg.id).await.value

    updatedPackage.state shouldBe UPLOADED
  }

  test("set package state to UPLOADED and be idempotent") {
    val pkg = packageManager
      .create(
        "Foo53394",
        PackageType.TimeSeries,
        UNAVAILABLE,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    fileManager
      .create(
        "file",
        FileType.BFTS,
        pkg,
        "bucket",
        "path/to/file.bfts",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await

    put(
      s"/${pkg.nodeId}/upload-complete?user_id=${loggedInUser.id}",
      headers = jwtServiceAuthorizationHeader(loggedInOrganization)
    ) {
      status should equal(200)
    }

    put(
      s"/${pkg.nodeId}/upload-complete?user_id=${loggedInUser.id}",
      headers = jwtServiceAuthorizationHeader(loggedInOrganization)
    ) {
      status should equal(200)
    }

    val updatedPackage: Package =
      packageManager.get(pkg.id).await.value

    updatedPackage.state shouldBe UPLOADED
  }

  test("set package with no workflow to READY and be idempotent") {
    val pkg = packageManager
      .create(
        "Foo53394",
        PackageType.MSWord,
        UNAVAILABLE,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    fileManager
      .create(
        "file",
        FileType.MSWord,
        pkg,
        "bucket",
        "path/to/file.word",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await

    put(
      s"/${pkg.nodeId}/upload-complete?user_id=${loggedInUser.id}",
      headers = jwtServiceAuthorizationHeader(loggedInOrganization)
    ) {
      status should equal(200)
    }

    put(
      s"/${pkg.nodeId}/upload-complete?user_id=${loggedInUser.id}",
      headers = jwtServiceAuthorizationHeader(loggedInOrganization)
    ) {
      status should equal(200)
    }

    val updatedPackage: Package =
      packageManager.get(pkg.id).await.value

    updatedPackage.state shouldBe READY
  }

  test("set package state to PROCESSING") {
    val datasetUpdated = secureContainer.datasetManager
      .create("automaticallyprocessing1", automaticallyProcessPackages = true)
      .await
      .value

    val pkg = packageManager
      .create(
        "Foo53394",
        PackageType.TimeSeries,
        UNAVAILABLE,
        datasetUpdated,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    fileManager
      .create(
        "file",
        FileType.BFTS,
        pkg,
        "bucket",
        "path/to/file.bfts",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await

    put(
      s"/${pkg.nodeId}/upload-complete?user_id=${loggedInUser.id}",
      headers = jwtServiceAuthorizationHeader(loggedInOrganization)
    ) {
      status should equal(200)
    }

    val updatedPackage: Package =
      packageManager.get(pkg.id).await.value

    updatedPackage.state shouldBe PROCESSING
  }

  test(
    "fail when PROCESSING package has unprocessed source files and leave state as PROCESSING"
  ) {
    val datasetUpdated = secureContainer.datasetManager
      .create("automaticallyprocessing1", automaticallyProcessPackages = true)
      .await
      .value

    val pkg = packageManager
      .create(
        "Foo53394",
        PackageType.TimeSeries,
        PROCESSING,
        datasetUpdated,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    fileManager
      .create(
        "file",
        FileType.BFTS,
        pkg,
        "bucket",
        "path/to/file.bfts",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await

    put(
      s"/${pkg.nodeId}/upload-complete?user_id=${loggedInUser.id}",
      headers = jwtServiceAuthorizationHeader(loggedInOrganization)
    ) {
      status should equal(500)
    }

    val updatedPackage: Package =
      packageManager.get(pkg.id).await.value

    updatedPackage.state shouldBe PROCESSING
  }

  test("do nothing to PROCESSING package with processed source files") {
    val datasetUpdated = secureContainer.datasetManager
      .create("automaticallyprocessing1", automaticallyProcessPackages = true)
      .await
      .value

    val pkg = packageManager
      .create(
        "Foo53394",
        PackageType.TimeSeries,
        PROCESSING,
        datasetUpdated,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    fileManager
      .create(
        "file",
        FileType.BFTS,
        pkg,
        "bucket",
        "path/to/file.bfts",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Processed,
        0
      )
      .await

    put(
      s"/${pkg.nodeId}/upload-complete?user_id=${loggedInUser.id}",
      headers = jwtServiceAuthorizationHeader(loggedInOrganization)
    ) {
      status should equal(200)
    }

    val updatedPackage: Package =
      packageManager.get(pkg.id).await.value

    updatedPackage.state shouldBe PROCESSING
  }

  test("keep timeseries package state as READY") {
    val pkg = packageManager
      .create(
        "Foo53394",
        PackageType.TimeSeries,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    fileManager
      .create(
        "file",
        FileType.BFTS,
        pkg,
        "bucket",
        "path/to/file.bfts",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await

    put(
      s"/${pkg.nodeId}/upload-complete?user_id=${loggedInUser.id}",
      headers = jwtServiceAuthorizationHeader(loggedInOrganization)
    ) {
      status should equal(200)
    }

    val updatedPackage: Package =
      packageManager.get(pkg.id).await.value

    updatedPackage.state shouldBe READY
  }

  test("only use unprocessed source files in timeseries append") {
    val pkg = packageManager
      .create(
        "Foo53394",
        PackageType.TimeSeries,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    val unprocessedSource = fileManager
      .create(
        "file",
        FileType.BFTS,
        pkg,
        "bucket",
        "path/to/file.bfts",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await
      .value

    val processedSource = fileManager
      .create(
        "file",
        FileType.BFTS,
        pkg,
        "bucket",
        "path/to/file.bfts",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Processed,
        0
      )
      .await
      .value

    put(
      s"/${pkg.nodeId}/upload-complete?user_id=${loggedInUser.id}",
      headers = jwtServiceAuthorizationHeader(loggedInOrganization)
    ) {
      status should equal(200)
    }

    val updatedSource =
      fileManager.get(unprocessedSource.id, pkg).await.value

    updatedSource.processingState should equal(FileProcessingState.Processed)

    val notUpdatedSource =
      fileManager.get(processedSource.id, pkg).await.value

    notUpdatedSource.updatedAt.toInstant should be < (updatedSource.updatedAt.toInstant)

    val updatedPackage: Package =
      packageManager.get(pkg.id).await.value

    updatedPackage.state shouldBe READY
  }

  test("set package with no source files to UPLOADED") {
    val pkg = packageManager
      .create(
        "Foo53394",
        PackageType.TimeSeries,
        UNAVAILABLE,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    put(
      s"/${pkg.nodeId}/upload-complete?user_id=${loggedInUser.id}",
      headers = jwtServiceAuthorizationHeader(loggedInOrganization)
    ) {
      status should equal(200)
    }

    val updatedPackage: Package =
      packageManager.get(pkg.id).await.value

    updatedPackage.state shouldBe UPLOADED
  }

  test("do nothing to a non-timeseries READY package with no unprocessed files") {
    val pkg = packageManager
      .create(
        "Foo557",
        PackageType.PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    put(
      s"/${pkg.nodeId}/upload-complete?user_id=${loggedInUser.id}",
      headers = jwtServiceAuthorizationHeader(loggedInOrganization)
    ) {
      status should equal(200)
    }

    val updatedPackage: Package =
      packageManager.get(pkg.id).await.value

    updatedPackage.state shouldBe READY
  }

  // Process Packages

  test("kick off ETL job to process package in UPLOADED state") {
    val pkg = packageManager
      .create(
        "Foo1",
        PackageType.TimeSeries,
        UPLOADED,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    fileManager
      .create(
        "file.bfts",
        FileType.BFTS,
        pkg,
        "bucket",
        "path/to/file.bfts",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await

    putJson(
      s"/${pkg.nodeId}/process",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val updatedPackage = packageManager.get(pkg.id).await.value
      updatedPackage.state should equal(PackageState.PROCESSING)

    }
  }

  test("only use unprocessed source files") {
    val pkg = packageManager
      .create(
        "Foo1",
        PackageType.TimeSeries,
        UPLOADED,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    val unprocessedSource = fileManager
      .create(
        "file.bfts",
        FileType.BFTS,
        pkg,
        "bucket",
        "path/to/file.bfts",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await
      .value

    val processedSource = fileManager
      .create(
        "file.bfts",
        FileType.BFTS,
        pkg,
        "bucket",
        "path/to/file.bfts",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Processed,
        0
      )
      .await
      .value

    putJson(
      s"/${pkg.nodeId}/process",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    val updatedPackage = packageManager.get(pkg.id).await.value

    updatedPackage.state should equal(PackageState.PROCESSING)

    val updatedSource =
      fileManager.get(unprocessedSource.id, pkg).await.value

    updatedSource.processingState should equal(FileProcessingState.Processed)

    val notUpdatedSource =
      fileManager.get(processedSource.id, pkg).await.value

    notUpdatedSource.updatedAt.toInstant should be < (updatedSource.updatedAt.toInstant)
  }

  test("fail to kick off ETL job to process package in a non-UPLOADED state") {
    val pkg = packageManager
      .create(
        "Foo1",
        PackageType.TimeSeries,
        UNAVAILABLE,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    fileManager
      .create(
        "file",
        FileType.BFTS,
        pkg,
        "bucket",
        "path/to/file.bfts",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await

    putJson(
      s"/${pkg.nodeId}/process",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
    }
  }

  test("fail to kick off ETL job to process package with no source files") {
    val pkg = packageManager
      .create(
        "Foo1",
        PackageType.TimeSeries,
        UNAVAILABLE,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    putJson(
      s"/${pkg.nodeId}/process",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
    }
  }

  test("exports between unsupported file types should fail") {
    val imagePackage = packageManager
      .create(
        "Image Package",
        PackageType.Image,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    val payload: String = """{ "fileType": "NeuroDataWithoutBorders" }"""

    putJson(
      s"/${imagePackage.nodeId}/export",
      payload,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
      response.body.toLowerCase should be(
        "package cannot be exported to neurodatawithoutborders"
      )
    }
  }

  test("exports against a processing package should fail") {
    val timeseriesPackage = packageManager
      .create(
        "Timeseries Package",
        PackageType.TimeSeries,
        PROCESSING,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    // Timeseries -> NeuroDataWithoutBorders is OK
    val payload: String = """{ "fileType": "NeuroDataWithoutBorders" }"""

    putJson(
      s"/${timeseriesPackage.nodeId}/export",
      payload,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
      response.body.toLowerCase should be(
        "only successfully processed packages can be exported"
      )
    }
  }

  test("exports for supported file types should succeed") {
    val timeseriesPackage = packageManager
      .create(
        "Timeseries Package",
        PackageType.TimeSeries,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    fileManager
      .create(
        "MEF Timeseries File",
        FileType.MEF,
        timeseriesPackage,
        "s3bucketName",
        "s3Path",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Processed,
        0
      )
      .await

    // Timeseries -> NeuroDataWithoutBorders is OK
    val payload: String = """{ "fileType": "NeuroDataWithoutBorders" }"""

    putJson(
      s"/${timeseriesPackage.nodeId}/export",
      payload,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val dto = parsedBody.extract[ExtendedPackageDTO]

      val createdPackage = packageManager.get(dto.content.id).await.value
      createdPackage.attributes shouldBe List(
        ModelProperty(
          "subtype",
          "Data Container",
          "string",
          "Pennsieve",
          false,
          true
        ),
        ModelProperty("icon", "NWB", "string", "Pennsieve", false, true)
      )
    }
  }

  test("package can be exported multiple times with unique names") {
    val timeseriesPackage = packageManager
      .create(
        "Timeseries Package",
        PackageType.TimeSeries,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    fileManager
      .create(
        "MEF Timeseries File",
        FileType.MEF,
        timeseriesPackage,
        "s3bucketName",
        "s3Path",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Processed,
        0
      )
      .await

    val payload: String = """{ "fileType": "NeuroDataWithoutBorders" }"""

    putJson(
      s"/${timeseriesPackage.nodeId}/export",
      payload,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val dto = parsedBody.extract[ExtendedPackageDTO]
      dto.content.name shouldBe "Timeseries Package (NeuroDataWithoutBorders)"
    }

    putJson(
      s"/${timeseriesPackage.nodeId}/export",
      payload,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val dto = parsedBody.extract[ExtendedPackageDTO]
      dto.content.name shouldBe "Timeseries Package (NeuroDataWithoutBorders) (1)"
    }
  }
}
