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

import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import com.pennsieve.api.SetStorageResponse
import com.pennsieve.domain.StorageAggregation.{ sdatasets, sorganizations }
import com.pennsieve.dtos.{
  DownloadManifestDTO,
  ExtendedPackageDTO,
  PackageDTO
}
import com.pennsieve.helpers.{
  MockAuditLogger,
  MockObjectStore,
  MockUrlShortenerClient
}
import com.pennsieve.models.{
  CognitoId,
  DBPermission,
  Dataset,
  Degree,
  File,
  FileChecksum,
  FileObjectType,
  FileProcessingState,
  FileType,
  License,
  ModelProperty,
  NodeCodes,
  Organization,
  Package,
  PackageState,
  PackageType,
  Role,
  User
}
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.write
import org.scalatest.EitherValues._
import org.scalatest.OptionValues._

import java.util.UUID
import scala.concurrent.Future

class TestPackagesController extends BaseApiUnitTest {

  // ---------- Fixtures -------------------------------------------------

  var loggedInUser: User = _
  var colleagueUser: User = _
  var externalUser: User = _
  var superAdmin: User = _

  var loggedInOrganization: Organization = _
  var externalOrganization: Organization = _

  var loggedInJwt: String = _
  var colleagueJwt: String = _
  var externalJwt: String = _
  var adminJwt: String = _

  var dataset: Dataset = _
  var dataPackage: Package = _
  var secureContainer: com.pennsieve.helpers.APIContainers.SecureAPIContainer =
    _

  val mockAuditLogger = new MockAuditLogger()
  val mockUrlShortenerClient: MockUrlShortenerClient =
    new MockUrlShortenerClient()

  override def beforeAll(): Unit = {
    super.beforeAll()

    implicit val httpClient: HttpRequest => Future[HttpResponse] = { _ =>
      Future.successful(HttpResponse())
    }

    addServlet(
      new PackagesController(
        insecureContainer,
        secureContainerBuilder,
        mockAuditLogger,
        new MockObjectStore("test.avi"),
        mockUrlShortenerClient,
        system,
        system.dispatcher
      ),
      "/*"
    )
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    state.clear()
    mockUrlShortenerClient.shortenedUrls.clear()
    setUpFixtures()
  }

  private def setUpFixtures(): Unit = {
    val orgId = state.newId()
    loggedInOrganization = Organization(
      nodeId = NodeCodes.generateId(NodeCodes.organizationCode),
      name = "Test Organization",
      slug = "test-org",
      id = orgId
    )
    state.organizations.put(orgId, loggedInOrganization)

    val extOrgId = state.newId()
    externalOrganization = Organization(
      nodeId = NodeCodes.generateId(NodeCodes.organizationCode),
      name = "External Organization",
      slug = "external-org",
      id = extOrgId
    )
    state.organizations.put(extOrgId, externalOrganization)

    superAdmin = mkUser("super@external.com", isSuperAdmin = true)
    loggedInUser =
      mkUser("test@test.com", middle = Some("M"), degree = Some(Degree.MS))
    colleagueUser = mkUser("colleague@test.com")
    externalUser = mkUser("test@external.com")

    addOrgMember(loggedInOrganization, superAdmin, DBPermission.Administer)
    addOrgMember(loggedInOrganization, loggedInUser, DBPermission.Administer)
    addOrgMember(loggedInOrganization, colleagueUser, DBPermission.Delete)
    addOrgMember(externalOrganization, externalUser, DBPermission.Delete)

    loggedInJwt = mintUserJwt(loggedInUser, loggedInOrganization)
    colleagueJwt = mintUserJwt(colleagueUser, loggedInOrganization)
    externalJwt = mintUserJwt(externalUser, externalOrganization)
    adminJwt = mintUserJwt(superAdmin, loggedInOrganization)

    secureContainer = secureContainerBuilder(loggedInUser, loggedInOrganization)
    secureContainer.datasetStatusManager.resetDefaultStatusOptions.await.value

    // Pre-create the test dataset (BaseApiTest pre-creates "Home"; we use a
    // dataset named "Home" too so package tests can reference it directly).
    dataset = secureContainer.datasetManager
      .create(name = "Home", description = Some("Home Dataset"))
      .await
      .value

    // Pre-create the test package that beforeEach in the original creates.
    dataPackage = secureContainer.packageManager
      .create(
        "Foo",
        PackageType.Collection,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
  }

  private def mkUser(
    email: String,
    first: String = "first",
    last: String = "last",
    middle: Option[String] = None,
    degree: Option[Degree] = None,
    isSuperAdmin: Boolean = false,
    isIntegrationUser: Boolean = false
  ): User = {
    val id = state.newId()
    val user = User(
      nodeId = NodeCodes.generateId(NodeCodes.userCode),
      email = email,
      firstName = first,
      middleInitial = middle,
      lastName = last,
      degree = degree,
      credential = "cred",
      color = "",
      url = "http://test.com",
      authyId = 0,
      isSuperAdmin = isSuperAdmin,
      isIntegrationUser = isIntegrationUser,
      preferredOrganizationId = None,
      status = true,
      orcidAuthorization = None,
      cognitoId = Some(CognitoId.UserPoolId(UUID.randomUUID())),
      id = id
    )
    state.users.put(id, user)
    user
  }

  private def addOrgMember(
    org: Organization,
    user: User,
    permission: DBPermission
  ): Unit = {
    state.orgUserPermissions.put((org.id, user.id), permission)
    state.orgUsers.put(
      (org.id, user.id),
      com.pennsieve.models.OrganizationUser(org.id, user.id, permission)
    )
  }

  // ---- helpers that mirror fields exposed on BaseApiTest ----------------

  private def packageManager: com.pennsieve.managers.PackageManager =
    secureContainer.packageManager
  private def fileManager: com.pennsieve.managers.FileManager =
    secureContainer.fileManager
  private def secureDataSetManager: com.pennsieve.managers.DatasetManager =
    secureContainer.datasetManager
  private def storageManager: com.pennsieve.managers.StorageServiceClientTrait =
    secureContainer.storageManager
  private def externalFileManager: com.pennsieve.managers.ExternalFileManager =
    secureContainer.externalFileManager
  private def organizationHeader(orgNodeId: String): Map[String, String] =
    Map("X-ORGANIZATION-ID" -> orgNodeId)

  private def createDataSet(
    name: String,
    description: Option[String] = Some("desc")
  ): Dataset =
    secureContainer.datasetManager.create(name, description).await.value

  private def createPackage(
    ds: Dataset,
    name: String,
    parent: Option[Package] = None,
    `type`: PackageType = PackageType.Collection,
    state: PackageState = PackageState.READY
  ): Package =
    secureContainer.packageManager
      .create(name, `type`, state, ds, Some(loggedInUser.id), parent)
      .await
      .value

  private def createTestDownloadPackage(
    name: String,
    parent: Option[Package] = None,
    packageType: PackageType = PackageType.Collection,
    dataset: Dataset = dataset,
    user: User = loggedInUser,
    packageManager: com.pennsieve.managers.PackageManager = this.packageManager
  ): Package =
    packageManager
      .create(
        name,
        packageType,
        PackageState.READY,
        dataset,
        Some(user.id),
        parent
      )
      .await
      .value

  private def createTestDownloadFile(
    fileName: String,
    pkg: Package,
    objectType: FileObjectType = FileObjectType.Source,
    processingState: FileProcessingState = FileProcessingState.Unprocessed,
    fileManager: com.pennsieve.managers.FileManager = this.fileManager
  ): File =
    createTestDownloadFileInBucket(
      fileName,
      pkg,
      "s3bucketName",
      objectType,
      processingState,
      fileManager
    )

  private def createTestDownloadFileInBucket(
    fileName: String,
    pkg: Package,
    bucketName: String,
    objectType: FileObjectType = FileObjectType.Source,
    processingState: FileProcessingState = FileProcessingState.Unprocessed,
    fileManager: com.pennsieve.managers.FileManager = this.fileManager,
    publishedS3VersionId: Option[String] = None
  ): File =
    fileManager
      .create(
        fileName,
        FileType.PDF,
        pkg,
        bucketName,
        fileName,
        objectType = objectType,
        processingState = processingState,
        size = 10,
        publishedS3VersionId = publishedS3VersionId
      )
      .await
      .value

  // -------------- Tests --------------------------------------------------

  test("swagger")(pending)

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
                       "properties": []}"""
    postJson(
      "",
      request,
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) { status should equal(403) }
  }

  test("create package with dataset permissions should succeed") {
    secureContainer.datasetManager
      .addUserCollaborator(dataset, colleagueUser, Role.Editor)
      .await

    val request = s"""{"name": "New Package",
                       "dataset": "${dataset.nodeId}",
                       "packageType": "PDF",
                       "owner": "${colleagueUser.nodeId}",
                       "properties": []}"""
    postJson(
      "",
      request,
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
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
    ) { status should equal(201) }
  }

  test("create package with empty name should fail") {
    val request = s"""{"name": "",
                       "dataset": "${dataset.nodeId}",
                       "packageType": "PDF",
                       "owner": "${loggedInUser.nodeId}"}"""
    postJson(
      "",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) { status should equal(400) }
  }

  test("create package without name fails") {
    val request = s"""{"dataset": "${dataset.nodeId}",
                       "packageType": "PDF",
                       "owner": "${loggedInUser.nodeId}"}"""
    postJson(
      "",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) { status should equal(400) }
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
      compact(render(json)) should not include "fakeid"
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
    ) { status should equal(400) }
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

  // ---- Update Packages -------------------------------------------------

  test(
    "update package name and packageType without parent or properties in request"
  ) {
    val pdfPackage = packageManager
      .create(
        "Foo1",
        PackageType.PDF,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    val request = """{"name": "Updated Package", "packageType": "MRI"}"""
    putJson(
      s"/${pdfPackage.nodeId}",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val json = parse(response.body)
      compact(render(json \ "content" \\ "name")) should include(
        "Updated Package"
      )
      compact(render(json \ "content" \\ "packageType")) should include("PDF")
    }
    putJson(
      s"/${pdfPackage.nodeId}",
      request,
      headers = authorizationHeader(adminJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val json = parse(response.body)
      compact(render(json \ "content" \\ "packageType")) should include("MRI")
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
        PackageState.READY,
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
      compact(render(json \ "properties" \\ "value")) should not include "data"
      compact(render(json \ "properties" \\ "value")) should include(
        "unchanged"
      )
      compact(render(json \ "properties" \\ "value")) should include("secrets")
    }
  }

  test("update package works without packageType") {
    val pdfPackage = packageManager
      .create(
        "Foo4",
        PackageType.PDF,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    putJson(
      s"/${pdfPackage.nodeId}",
      """{"name": "Updated Package2"}""",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val json = parse(response.body)
      compact(render(json \ "content" \\ "name")) should include(
        "Updated Package2"
      )
    }
  }

  test("update package works without name") {
    val pdfPackage = packageManager
      .create(
        "Foo5",
        PackageType.PDF,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    putJson(
      s"/${pdfPackage.nodeId}",
      """{"packageType": "MRI"}""",
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
        PackageState.UNAVAILABLE,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    putJson(
      s"/${pdfPackage.nodeId}",
      """{"state": "READY"}""",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val pdf = packageManager.getByNodeId(pdfPackage.nodeId).await.value
      pdf.state should equal(PackageState.UNAVAILABLE)
    }
  }

  test("update package service users can update state with a JWT") {
    val pdfPackage = packageManager
      .create(
        "Foo6",
        PackageType.PDF,
        PackageState.UNAVAILABLE,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    putJson(
      s"/${pdfPackage.nodeId}",
      """{"state": "READY"}""",
      headers = jwtServiceAuthorizationHeader(loggedInOrganization) ++ traceIdHeader()
    ) {
      status should equal(200)
      val pdf = packageManager.getByNodeId(pdfPackage.nodeId).await.value
      pdf.state should equal(PackageState.READY)
    }
  }

  test("update package updates state with a numeric id") {
    val pdfPackage = packageManager
      .create(
        "Foo6",
        PackageType.PDF,
        PackageState.UNAVAILABLE,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    putJson(
      s"/${pdfPackage.id}",
      """{"state": "READY"}""",
      headers = jwtServiceAuthorizationHeader(loggedInOrganization) ++ traceIdHeader()
    ) {
      status should equal(200)
      val pdf = packageManager.getByNodeId(pdfPackage.nodeId).await.value
      pdf.state should equal(PackageState.READY)
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
        PackageState.READY,
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
      compact(render(json \ "properties" \\ "value")) should not include "data"
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
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    val request =
      s"""{"name":"Updated Package","state": "READY","uploader":"${loggedInUser.id}"}"""
    putJson(
      s"/${pdfPackage.nodeId}",
      request,
      headers = authorizationHeader(adminJwt) ++ organizationHeader(
        loggedInOrganization.nodeId
      ) ++ traceIdHeader()
    ) { status should equal(200) }
  }

  test("update package name ignores package in DELETING state") {
    val pdfPackage = packageManager
      .create(
        "Foo1",
        PackageType.PDF,
        PackageState.READY,
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
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    secureContainer.packageManager
      .update(otherPdfPackage.copy(state = PackageState.DELETING))
      .await
      .value

    putJson(
      s"/${pdfPackage.nodeId}",
      s"""{"name":"Updated Package 2","state": "READY","uploader":"${loggedInUser.id}"}""",
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
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    val request = """{"name": "Updated Package", "packageType": "MRI"}"""
    putJson(
      s"/${pdfPackage.nodeId}",
      request,
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) { status should equal(403) }
    putJson(
      s"/${pdfPackage.nodeId}",
      request,
      headers = authorizationHeader(externalJwt) ++ traceIdHeader()
    ) { status should equal(404) }
  }

  // ---- Get Package -----------------------------------------------------

  test("gets a package - simple") {
    val props = List(
      ModelProperty("meta", "data", "string", "user-defined"),
      ModelProperty("other", "unchanged", "string", "user-defined")
    )
    val pdfPackage = packageManager
      .create(
        "Foo10",
        PackageType.PDF,
        PackageState.READY,
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
      compact(render(json \ "content" \\ "name")) should include("Foo10")
      compact(render(json \ "content" \\ "packageType")) should include("PDF")
      compact(render(json \ "properties" \\ "key")) should include("meta")
    }
  }

  test("get package is paginated and returns default number of children") {
    val ds = createDataSet(name = "test-dataset-for-testing-package-pagination")
    val collection = createPackage(ds, "Folder")
    (1 to PackagesController.PackageChildrenDefaultLimit + 1)
      .map(n => createPackage(ds, s"Package-${n}", parent = Some(collection)))

    get(
      s"/${collection.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val pkg = parsedBody.extract[PackageDTO]
      pkg.children.length shouldBe PackagesController.PackageChildrenDefaultLimit
    }
  }

  test("get package is paginated and returns requested number of children") {
    val ds = createDataSet(name = "test-dataset-for-testing-package-pagination")
    val collection = createPackage(ds, "Folder")
    (1 to 26).map(
      n => createPackage(ds, s"Package-${n}", parent = Some(collection))
    )
    get(
      s"/${collection.nodeId}?offset=0&limit=5",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val pkg = parsedBody.extract[PackageDTO]
      pkg.children.length shouldBe 5
    }
  }

  test(
    "get package is paginated and last page may provide fewer children than requested"
  ) {
    val ds = createDataSet(name = "test-dataset-for-testing-package-pagination")
    val collection = createPackage(ds, "Folder")
    (1 to 26).map(
      n => createPackage(ds, s"Package-${n}", parent = Some(collection))
    )
    get(
      s"/${collection.nodeId}?offset=25&limit=5",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val pkg = parsedBody.extract[PackageDTO]
      pkg.children.length shouldBe 1
    }
  }

  test("paginated max limit on get package requests") {
    val ds = createDataSet("test-dataset-ppackage-pagination-limit")
    val collection = createPackage(ds, "Folder")
    (1 to PackagesController.PackageChildrenMaxLimit + 2)
      .map(n => createPackage(ds, s"Package-${n}", parent = Some(collection)))

    get(
      s"/${collection.nodeId}?offset=0&limit=${PackagesController.PackageChildrenMaxLimit + 1}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val pkg = parsedBody.extract[PackageDTO]
      pkg.children.length shouldBe PackagesController.PackageChildrenMaxLimit
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

  test("unshared users cannot get packages") {
    val pdfPackage = packageManager
      .create(
        "Foo14",
        PackageType.PDF,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        Some(dataPackage)
      )
      .await
      .value
    get(
      s"/${pdfPackage.nodeId}",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) { status should equal(403) }
    get(
      s"/${pdfPackage.nodeId}",
      headers = authorizationHeader(externalJwt) ++ traceIdHeader()
    ) { status should equal(404) }
  }

  // ---- File-extension tests --------------------------------------------

  test("packages with a single file should contain a file extension") {
    val ds = createDataSet("Test", Some("Test Description"))
    val pdfPackage = packageManager
      .create(
        "Foo10",
        PackageType.PDF,
        PackageState.UNAVAILABLE,
        ds,
        Some(loggedInUser.id),
        None
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
    val ds = createDataSet("Test2")
    val pdfPackage = packageManager
      .create(
        "Foo10",
        PackageType.PDF,
        PackageState.UNAVAILABLE,
        ds,
        Some(loggedInUser.id),
        None
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
      compact(render(json)) should not include "extension"
    }
  }

  test("gets a package plus its data nodes") {
    val pdfPackage = packageManager
      .create(
        "Foo11",
        PackageType.PDF,
        PackageState.READY,
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
      compact(render(json \ "objects" \ "source" \\ "content" \\ "name")) should include(
        "Source File"
      )
      compact(render(json \ "objects" \ "source" \\ "content" \\ "name")) should not include "Converted File"
      compact(render(json \ "objects" \ "file" \\ "content" \\ "name")) should include(
        "Converted File"
      )
      compact(render(json \ "objects" \ "view")) should equal("[]")
    }
  }

  test("gets a package but selectively filters its data nodes") {
    val pdfPackage = packageManager
      .create(
        "Foo11.A",
        PackageType.PDF,
        PackageState.READY,
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
      compact(render(json \ "objects" \ "source" \\ "content" \\ "name")) should include(
        "Source File"
      )
      compact(render(json \ "objects" \ "file")) should equal("[]")
      compact(render(json \ "objects" \ "view" \\ "content" \\ "name")) should include(
        "View File"
      )
    }
  }

  test("rejects non-alphanumeric includes") {
    val pdfPackage = packageManager
      .create(
        "Foo12",
        PackageType.PDF,
        PackageState.READY,
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

  test("gets a package's ancestors") {
    val pdfPackage = packageManager
      .create(
        "Foo14",
        PackageType.PDF,
        PackageState.READY,
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
        PackageState.READY,
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
        PackageState.READY,
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

  // ---- Sources / files / views -----------------------------------------

  test("gets a package's sources") {
    val pdfcollection = packageManager
      .create(
        "PDFCollection",
        PackageType.Collection,
        PackageState.READY,
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
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        Some(pdfcollection)
      )
      .await
      .value
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
      val files = parsedBody.extract[List[com.pennsieve.dtos.FileDTO]]
      files should have length 2
      val names = files.map(_.content.name)
      names should contain("test")
      names should contain("test2")
      names should not contain "test3"
    }
  }

  test("unshared users cannot get package sources") {
    val pdfPackage = packageManager
      .create(
        "Foo15",
        PackageType.PDF,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    get(
      s"/${pdfPackage.nodeId}/sources",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) { status should equal(403) }
    get(
      s"/${pdfPackage.nodeId}/sources",
      headers = authorizationHeader(externalJwt) ++ traceIdHeader()
    ) { status should equal(404) }
  }

  test("unshared users cannot get package's files") {
    val pdfPackage = packageManager
      .create(
        "Foo15",
        PackageType.PDF,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    get(
      s"/${pdfPackage.nodeId}/files",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) { status should equal(403) }
    get(
      s"/${pdfPackage.nodeId}/files",
      headers = authorizationHeader(externalJwt) ++ traceIdHeader()
    ) { status should equal(404) }
  }

  test("unshared users cannot get a package's views") {
    val pdfPackage = packageManager
      .create(
        "Foo15",
        PackageType.PDF,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    get(
      s"/${pdfPackage.nodeId}/view",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) { status should equal(403) }
    get(
      s"/${pdfPackage.nodeId}/view",
      headers = authorizationHeader(externalJwt) ++ traceIdHeader()
    ) { status should equal(404) }
  }

  test("gets a package's sources if it has no files") {
    val pdfPackage = packageManager
      .create(
        "Foo15",
        PackageType.PDF,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    get(
      s"/${pdfPackage.nodeId}/sources",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val files = parsedBody.extract[List[com.pennsieve.dtos.FileDTO]]
      files should have length 0
    }
  }

  test("gets a package's files if it has no view") {
    val pdfPackage = packageManager
      .create(
        "Foo15",
        PackageType.PDF,
        PackageState.READY,
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
      val files = parsedBody.extract[List[com.pennsieve.dtos.FileDTO]]
      files should have length 1
    }
  }

  test("gets a package's sources if it has no view or files") {
    val pdfPackage = packageManager
      .create(
        "Foo15",
        PackageType.PDF,
        PackageState.READY,
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
    get(
      s"/${pdfPackage.nodeId}/sources",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val files = parsedBody.extract[List[com.pennsieve.dtos.FileDTO]]
      files should have length 1
    }
  }

  test("gets a package's view with limit and offset") {
    val pdfPackage = packageManager
      .create(
        "Foo15",
        PackageType.PDF,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    (1 to 200).foreach { _ =>
      fileManager
        .create(
          "View File",
          FileType.PDF,
          pdfPackage,
          "s3bucketName",
          "/path/to/view.pdf",
          objectType = FileObjectType.View,
          processingState = FileProcessingState.NotProcessable,
          0
        )
        .await
    }
    get(
      s"/${pdfPackage.nodeId}/view",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val files = parsedBody.extract[List[com.pennsieve.dtos.FileDTO]]
      files.length should equal(100)
    }
    get(
      s"/${pdfPackage.nodeId}/view?limit=10",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      val files = parsedBody.extract[List[com.pennsieve.dtos.FileDTO]]
      files.length should equal(10)
    }
    get(
      s"/${pdfPackage.nodeId}/view?offset=101",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      val files = parsedBody.extract[List[com.pennsieve.dtos.FileDTO]]
      files.length should equal(99)
    }
  }

  test("gets a package's view") {
    val pdfPackage = packageManager
      .create(
        "Foo15",
        PackageType.PDF,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    fileManager
      .create(
        "View File",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "/path/to/view.pdf",
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
      val files = parsedBody.extract[List[com.pennsieve.dtos.FileDTO]]
      files should have length 1
    }
  }

  test("gets a package's files with limit and offset") {
    val pdfPackage = packageManager
      .create(
        "Foo15",
        PackageType.PDF,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    (1 to 200).foreach { _ =>
      fileManager
        .create(
          "File",
          FileType.PDF,
          pdfPackage,
          "s3bucketName",
          "/path/to/test.pdf",
          objectType = FileObjectType.File,
          processingState = FileProcessingState.NotProcessable,
          0
        )
        .await
    }
    get(
      s"/${pdfPackage.nodeId}/files",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val files = parsedBody.extract[List[com.pennsieve.dtos.FileDTO]]
      files.length should equal(100)
    }
    get(
      s"/${pdfPackage.nodeId}/files?limit=10",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      val files = parsedBody.extract[List[com.pennsieve.dtos.FileDTO]]
      files.length should equal(10)
    }
  }

  test("gets a package's files") {
    val pdfPackage = packageManager
      .create(
        "Foo15",
        PackageType.PDF,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    fileManager
      .create(
        "File",
        FileType.PDF,
        pdfPackage,
        "s3bucketName",
        "/path/to/test.pdf",
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
      val files = parsedBody.extract[List[com.pennsieve.dtos.FileDTO]]
      files should have length 1
    }
  }

  // ---- sources-paged ---------------------------------------------------

  test("sources-paged appends the correct extension for single files") {
    val pdfPackage = packageManager
      .create(
        "foo",
        PackageType.PDF,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    fileManager
      .create(
        "foo.txt",
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
      val sources = parsedBody
        .extract[com.pennsieve.dtos.PagedResponse[com.pennsieve.dtos.FileDTO]]
      sources.results.length should equal(1)
      sources.results.head.content.filename should equal("foo.txt.pdf")
    }
  }

  test("sources-paged ensures source files only have a single extension") {
    val pdfPackage = packageManager
      .create(
        "foo",
        PackageType.PDF,
        PackageState.READY,
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
      val sources = parsedBody
        .extract[com.pennsieve.dtos.PagedResponse[com.pennsieve.dtos.FileDTO]]
      sources.results.length should equal(1)
      sources.results.head.content.filename should equal("foo.pdf")
    }
  }

  test("paginated max limit on sources-paged") {
    val pdfPackage = packageManager
      .create(
        "Foo15",
        PackageType.PDF,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    (1 to PackagesController.FILES_LIMIT_MAX + 2).foreach { _ =>
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
    }
    get(
      s"/${pdfPackage.nodeId}/sources-paged?limit=${PackagesController.FILES_LIMIT_MAX + 1}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val sources = parsedBody
        .extract[com.pennsieve.dtos.PagedResponse[com.pennsieve.dtos.FileDTO]]
      sources.results.length should equal(PackagesController.FILES_LIMIT_MAX)
    }
  }

  test(
    "gets package sources with limit and offset for legacy and paged responses"
  ) {
    val pdfPackage = packageManager
      .create(
        "Foo15",
        PackageType.PDF,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    (1 to 200).foreach { _ =>
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
    }
    get(
      s"/${pdfPackage.nodeId}/sources",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[List[com.pennsieve.dtos.FileDTO]].length should equal(
        100
      )
    }
    get(
      s"/${pdfPackage.nodeId}/sources-paged",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      val sources = parsedBody
        .extract[com.pennsieve.dtos.PagedResponse[com.pennsieve.dtos.FileDTO]]
      sources.results.length should equal(100)
    }
    get(
      s"/${pdfPackage.nodeId}/sources?limit=10",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[List[com.pennsieve.dtos.FileDTO]].length should equal(
        10
      )
    }
    get(
      s"/${pdfPackage.nodeId}/sources?offset=101",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[List[com.pennsieve.dtos.FileDTO]].length should equal(
        99
      )
    }
  }

  test("sources-paged order-by and order-by direction works correctly TESTME") {
    val pdfPackage = packageManager
      .create(
        "Foo15",
        PackageType.PDF,
        PackageState.READY,
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
      val sources = parsedBody
        .extract[com.pennsieve.dtos.PagedResponse[com.pennsieve.dtos.FileDTO]]
      sources.results.map(_.content.name) shouldBe createdFiles
        .map(_.name)
        .sorted
    }
    get(
      s"/${pdfPackage.nodeId}/sources-paged?order-by=name&order-by-direction=desc",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      val sources = parsedBody
        .extract[com.pennsieve.dtos.PagedResponse[com.pennsieve.dtos.FileDTO]]
      sources.results.map(_.content.name) shouldBe createdFiles
        .map(_.name)
        .sorted
        .reverse
    }
    get(
      s"/${pdfPackage.nodeId}/sources-paged?order-by=size",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      val sources = parsedBody
        .extract[com.pennsieve.dtos.PagedResponse[com.pennsieve.dtos.FileDTO]]
      sources.results.map(_.content.size) shouldBe createdFiles
        .map(_.size)
        .sorted
    }
  }

  // ---- external file ---------------------------------------------------

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
      val pkg = packageManager
        .getByNodeId(compact(render(json \ "content" \ "id")).replace("\"", ""))
        .await
        .value
      val ext = externalFileManager.get(pkg).await.value
      ext.location shouldBe "https://www.dropbox.com/my-external-file"
      ext.description shouldBe Some("An extraordinary file from my dropbox")
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
    val nodeId = postJson(
      "",
      createRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
      compact(render(parse(response.body) \ "content" \ "id")).replace("\"", "")
    }

    val updateRequest = s"""{"name": "Test Package",
                       "dataset": "${dataset.nodeId}",
                       "packageType": "EXTERNAL",
                       "owner": "${loggedInUser.nodeId}",
                       "state": "READY",
                       "externalLocation": "file:///home/cloud",
                       "description": "!HIGH ENERGY DESCRIPTION!"}"""
    putJson(
      s"/$nodeId",
      updateRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val pkg = packageManager.getByNodeId(nodeId).await.value
      val ext = externalFileManager.get(pkg).await.value
      ext.location shouldBe "file:///home/cloud"
      ext.description shouldBe Some("!HIGH ENERGY DESCRIPTION!")
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
    val nodeId = postJson(
      "",
      createRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
      compact(render(parse(response.body) \ "content" \ "id")).replace("\"", "")
    }
    val updateRequest = s"""{"name": "Test Package",
                       "dataset": "${dataset.nodeId}",
                       "packageType": "EXTERNAL",
                       "owner": "${loggedInUser.nodeId}",
                       "state": "READY",
                       "externalLocation": "file:///home/cloud",
                       "description": "!HIGH ENERGY DESCRIPTION!"}"""
    putJson(
      s"/$nodeId",
      updateRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) { status should equal(200) }
  }

  // ---- channels --------------------------------------------------------

  test("includes a timeseries package's channels") {
    val timeseriesPackage = packageManager
      .create(
        "Foo13",
        PackageType.TimeSeries,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    secureContainer.timeSeriesManager
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
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    secureContainer.timeSeriesManager
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

  test("update package merges properties with existing properties using circe") {
    val props = List(
      ModelProperty("meta", "data", "string", "user-defined"),
      ModelProperty("other", "unchanged", "string", "user-definied")
    )
    val pdfPackage = packageManager
      .create(
        "Foo2",
        PackageType.PDF,
        PackageState.READY,
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
      io.circe.parser.decode[PackageDTO](body).isRight shouldBe true
    }
  }

  test(
    "sources-paged returns new columns properties, assetType, and provenanceId"
  ) {
    val testProvenanceId = UUID.randomUUID()
    val testProperties =
      io.circe.Json.obj("key" -> io.circe.Json.fromString("value"))
    val testAssetType = "image"

    val pdfPackage = packageManager
      .create(
        "foo",
        PackageType.PDF,
        PackageState.READY,
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
      val json = parse(response.body)
      val results = (json \ "results").extract[List[JValue]]
      results.length should equal(1)
      val fileContent = results.head \ "content"
      (fileContent \ "assetType").extract[Option[String]] should equal(
        Some(testAssetType)
      )
      (fileContent \ "provenanceId").extract[Option[String]] should equal(
        Some(testProvenanceId.toString)
      )
      (fileContent \ "properties" \ "key").extract[String] should equal("value")
    }
  }

  test(
    "update package name with multiple source file should not updates file names"
  ) {
    val testPackage = packageManager
      .create(
        "testFileOldName",
        PackageType.PDF,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    val testFile = (1 to 2).toList.map { idx =>
      fileManager
        .create(
          s"testFileOldName$idx",
          FileType.BFTS,
          testPackage,
          "testBucket",
          "testKey",
          objectType = FileObjectType.Source,
          processingState = FileProcessingState.Unprocessed,
          1
        )
        .await
        .value
    }
    putJson(
      s"/${testPackage.nodeId}",
      s"""{"name":"Updated Package","state": "READY","uploader":"${loggedInUser.id}"}""",
      headers = jwtServiceAuthorizationHeader(loggedInOrganization) ++ traceIdHeader()
    ) {
      status should equal(200)
      val updatedFile =
        fileManager.get(testFile.head.id, testPackage).await.value
      updatedFile.name should not equal "Updated Package"
    }
  }

  test("update package name with single source updates file name") {
    val testPackage = packageManager
      .create(
        "testFileOldName",
        PackageType.PDF,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    val testFile = fileManager
      .create(
        "testFileOldName",
        FileType.BFTS,
        testPackage,
        "testBucket",
        "testKey",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        1
      )
      .await
      .value
    putJson(
      s"/${testPackage.nodeId}",
      s"""{"name":"Updated Package","state": "READY","uploader":"${loggedInUser.id}"}""",
      headers = jwtServiceAuthorizationHeader(loggedInOrganization) ++ traceIdHeader()
    ) {
      status should equal(200)
      val updatedFile = fileManager.get(testFile.id, testPackage).await.value
      updatedFile.name should equal("Updated Package")
    }
  }

  // ---- presign URLs ----------------------------------------------------

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
      parsedBody.extract[com.pennsieve.api.DownloadItemResponse].url shouldBe
        "file://s3bucketName/s3Path"
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
      parsedBody.extract[com.pennsieve.api.DownloadItemResponse].url shouldBe
        "https://bit.ly/short"
      mockUrlShortenerClient.shortenedUrls.head shouldBe new java.net.URL(
        "file://s3bucketName/s3Path"
      )
    }
  }

  test("get presigned url includes versionId for published file") {
    val s3VersionId = UUID.randomUUID().toString
    val file = fileManager
      .create(
        "Source File",
        FileType.PDF,
        dataPackage,
        "publish-bucket",
        "70/files/source.pdf",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0,
        publishedS3VersionId = Some(s3VersionId)
      )
      .await
      .value
    get(
      s"/${dataPackage.nodeId}/files/${file.id}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val url = parsedBody.extract[com.pennsieve.api.DownloadItemResponse].url
      url should include("publish-bucket")
      url should include(s"versionId=$s3VersionId")
    }
  }

  test("get presigned url does not include versionId for unpublished file") {
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
      val url = parsedBody.extract[com.pennsieve.api.DownloadItemResponse].url
      url should not include "versionId"
    }
  }

  test("get shortened presigned url passes versionId to shortener") {
    val s3VersionId = UUID.randomUUID().toString
    val file = fileManager
      .create(
        "Source File",
        FileType.PDF,
        dataPackage,
        "publish-bucket",
        "70/files/source.pdf",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0,
        publishedS3VersionId = Some(s3VersionId)
      )
      .await
      .value
    get(
      s"/${dataPackage.nodeId}/files/${file.id}?short=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      mockUrlShortenerClient.shortenedUrls.head.toString should include(
        s"versionId=$s3VersionId"
      )
    }
  }

  test("presign redirect includes versionId for published file") {
    val s3VersionId = UUID.randomUUID().toString
    val file = fileManager
      .create(
        "Source File",
        FileType.PDF,
        dataPackage,
        "publish-bucket",
        "70/files/source.pdf",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0,
        publishedS3VersionId = Some(s3VersionId)
      )
      .await
      .value
    get(
      s"/${dataPackage.nodeId}/files/${file.id}/presign/",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(302)
      response.getHeader("Location") should include(s"versionId=$s3VersionId")
    }
  }

  test("presign redirect does not include versionId for unpublished file") {
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
      s"/${dataPackage.nodeId}/files/${file.id}/presign/",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(302)
      response.getHeader("Location") should not include "versionId"
    }
  }

  test("unshared users cannot get presigned urls") {
    val pdfPackage = packageManager
      .create(
        "Foo18",
        PackageType.PDF,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
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
    ) { status should equal(403) }
    get(
      s"/${pdfPackage.nodeId}/files/${file1.id}",
      headers = authorizationHeader(externalJwt) ++ traceIdHeader()
    ) { status should equal(404) }
  }

  test("get a collection with its ancestors") {
    val ds = createDataSet("Foo28")
    val collection = packageManager
      .create(
        "Foo26",
        PackageType.Collection,
        PackageState.READY,
        ds,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    val sub = packageManager
      .create(
        "Foo27",
        PackageType.Collection,
        PackageState.READY,
        ds,
        Some(loggedInUser.id),
        Some(collection)
      )
      .await
      .value
    val sub2 = packageManager
      .create(
        "Foo27",
        PackageType.Collection,
        PackageState.READY,
        ds,
        Some(loggedInUser.id),
        Some(sub)
      )
      .await
      .value
    get(
      s"/${sub2.nodeId}?includeAncestors=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should include("ancestors")
      val rendered = compact(render(parsedBody \ "ancestors"))
      rendered should include("Foo26")
      rendered should include("Foo27")
    }
  }

  test("return unauthorized when setting package storage without a token") {
    val ds = createDataSet("StorageDs1")
    val collection = packageManager
      .create(
        "Coll",
        PackageType.Collection,
        PackageState.READY,
        ds,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    putJson(
      s"/${collection.id}/storage",
      """{ "size": 1000 }""",
      headers = Map("X-ORGANIZATION-ID" -> loggedInOrganization.nodeId)
    ) { status should equal(401) }
  }

  test("return forbidden when setting package storage with a non-service claim") {
    val ds = createDataSet("StorageDs2")
    val collection = packageManager
      .create(
        "Coll",
        PackageType.Collection,
        PackageState.READY,
        ds,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    putJson(
      s"/${collection.id}/storage",
      """{ "size": 1000 }""",
      headers = authorizationHeader(loggedInJwt) ++
        Map("X-ORGANIZATION-ID" -> loggedInOrganization.nodeId) ++
        traceIdHeader()
    ) { status should equal(403) }
  }

  test("successfully set package storage with a jwt") {
    val ds = createDataSet("Foo28")
    val collection = packageManager
      .create(
        "Foo26",
        PackageType.Collection,
        PackageState.READY,
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
    (1 to numberOfFiles).foreach { idx =>
      fileManager
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

      val totalSize = (fileSize * numberOfFiles).toLong
      result.storage.value should be(totalSize)

      storageManager
        .getStorage(sdatasets, List(dataset.id))
        .await
        .value
        .get(dataset.id)
        .value
        .value should be(totalSize)

      storageManager
        .getStorage(sorganizations, List(loggedInOrganization.id))
        .await
        .value
        .get(loggedInOrganization.id)
        .value
        .value should be(totalSize)
    }
  }

  test("setPublished marks specific file as published by s3Key") {
    val coll = packageManager
      .create(
        "Stedding Chanti",
        PackageType.Collection,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    fileManager
      .create(
        "the-stump-report.pdf",
        FileType.PDF,
        coll,
        "s3bucketName",
        "data/the-stump-report.pdf",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await
      .value
    fileManager
      .create(
        "the-stump-image.jpeg",
        FileType.JPEG,
        coll,
        "s3bucketName",
        "data/the-stump-image.jpeg",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await
      .value
    fileManager
      .setPublished(
        coll,
        published = true,
        s3Key = Some("data/the-stump-image.jpeg")
      )
      .await
      .value
    get(
      s"/${coll.nodeId}/files",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val files = parsedBody.extract[List[com.pennsieve.dtos.FileDTO]]
      files.size should equal(2)
      val pdf = files.find(_.content.s3key == "data/the-stump-report.pdf").get
      val jpeg = files.find(_.content.s3key == "data/the-stump-image.jpeg").get
      pdf.content.published shouldBe false
      jpeg.content.published shouldBe true
    }
  }

  test("setFilePublished marks specific file as published by id") {
    val ts = packageManager
      .create(
        "Stedding Chanti",
        PackageType.TimeSeries,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    val storageBucket = "storage-bucket"
    val file1 = fileManager
      .create(
        "the-stump-report.lay",
        FileType.Data,
        ts,
        storageBucket,
        s"${UUID.randomUUID()}/${UUID.randomUUID()}",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await
      .value
    val file2 = fileManager
      .create(
        "the-stump-image.dat",
        FileType.Data,
        ts,
        storageBucket,
        s"${UUID.randomUUID()}/${UUID.randomUUID()}",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await
      .value
    val publishBucket = "publish-bucket"
    val publishKey = "70/files/data/the-stump-image.dat"
    val s3VersionId = UUID.randomUUID().toString
    fileManager
      .setFilePublished(file2, publishBucket, publishKey, s3VersionId)
      .await
      .value shouldBe 1

    get(
      s"/${ts.nodeId}/files",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val files = parsedBody.extract[List[com.pennsieve.dtos.FileDTO]]
      val a = files.find(_.content.id == file1.id).get
      val b = files.find(_.content.id == file2.id).get
      a.content.publishedS3VersionId shouldBe None
      b.content.publishedS3VersionId.value shouldBe s3VersionId
      b.content.s3bucket shouldBe publishBucket
      b.content.s3key shouldBe publishKey
    }
  }

  test("setPublished marks all files as published when no s3Key is provided") {
    val pkg = packageManager
      .create(
        "eeg_scan",
        PackageType.TimeSeries,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    fileManager
      .create(
        "eeg_scan.dat",
        FileType.Persyst,
        pkg,
        "s3bucketName",
        "data/eeg_scan.dat",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await
      .value
    fileManager
      .create(
        "eeg_scan.lay",
        FileType.Persyst,
        pkg,
        "s3bucketName",
        "data/eeg_scan.lay",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await
      .value
    fileManager.setPublished(pkg, published = true).await.value

    get(
      s"/${pkg.nodeId}/files",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val files = parsedBody.extract[List[com.pennsieve.dtos.FileDTO]]
      files.foreach(_.content.published shouldBe true)
    }
  }

  test("setPublished can toggle files back to unpublished") {
    val coll = packageManager
      .create(
        "Toggle Test Folder",
        PackageType.Collection,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    fileManager
      .create(
        "toggle-test.pdf",
        FileType.PDF,
        coll,
        "s3bucketName",
        "data/toggle-test.pdf",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await
      .value
    fileManager.setPublished(coll, published = true).await.value
    get(
      s"/${coll.nodeId}/files",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody
        .extract[List[com.pennsieve.dtos.FileDTO]]
        .head
        .content
        .published shouldBe true
    }
    fileManager.setPublished(coll, published = false).await.value
    get(
      s"/${coll.nodeId}/files",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody
        .extract[List[com.pennsieve.dtos.FileDTO]]
        .head
        .content
        .published shouldBe false
    }
  }

  test("setFileUnpublished can toggle files back to unpublished") {
    val mefPackage = packageManager
      .create(
        "Toggle Test TimeSeries",
        PackageType.TimeSeries,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    val storageBucket = "storage-bucket"
    val storageKey = s"${UUID.randomUUID()}/${UUID.randomUUID()}"
    val file = fileManager
      .create(
        "toggle-test.mef",
        FileType.MEF,
        mefPackage,
        storageBucket,
        storageKey,
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await
      .value
    val s3VersionId = UUID.randomUUID().toString
    val publishBucket = "publish-bucket"
    val publishKey = s"9/files/toggle-test.mef"
    fileManager
      .setFilePublished(file, publishBucket, publishKey, s3VersionId)
      .await
      .value

    get(
      s"/${mefPackage.nodeId}/files",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      val files = parsedBody.extract[List[com.pennsieve.dtos.FileDTO]]
      files.head.content.publishedS3VersionId.value shouldBe s3VersionId
      files.head.content.s3bucket shouldBe publishBucket
    }
    fileManager.setFileUnpublished(file, storageBucket, storageKey).await.value
    get(
      s"/${mefPackage.nodeId}/files",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      val files = parsedBody.extract[List[com.pennsieve.dtos.FileDTO]]
      files.head.content.publishedS3VersionId shouldBe None
      files.head.content.s3bucket shouldBe storageBucket
    }
  }

  test("files default to unpublished") {
    val collection = packageManager
      .create(
        "Test Folder",
        PackageType.Collection,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    fileManager
      .create(
        "document1.pdf",
        FileType.PDF,
        collection,
        "s3bucketName",
        "folder/document1.pdf",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await
      .value
    fileManager
      .create(
        "document2.pdf",
        FileType.PDF,
        collection,
        "s3bucketName",
        "folder/document2.pdf",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        0
      )
      .await
      .value
    get(
      s"/${collection.nodeId}/files",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val files = parsedBody.extract[List[com.pennsieve.dtos.FileDTO]]
      files.foreach { f =>
        f.content.publishedS3VersionId shouldBe None
      }
    }
  }

  test(
    "gets a package and resets channel start times with the startAtEpoch flag"
  ) {
    val ts = packageManager
      .create(
        "SensitiveTimeSeries",
        PackageType.TimeSeries,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    secureContainer.timeSeriesManager
      .createChannel(
        ts,
        "S Channel1",
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
    secureContainer.timeSeriesManager
      .createChannel(
        ts,
        "S Channel2",
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
      s"/${ts.nodeId}?startAtEpoch=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val dto = parse(response.body).extract[PackageDTO]
      val starts = dto.channels.get.map(_.content.start)
      val ends = dto.channels.get.map(_.content.end)
      starts should contain theSameElementsAs List(0L, 100000L)
      ends should contain theSameElementsAs List(300000L, 400000L)
    }
  }

  // ---- download-manifest ------------------------------------------------

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
      json.extract[Map[String, Any]].keySet.head shouldBe "header"

      val payload = json.extract[DownloadManifestDTO]
      payload.header.size shouldBe 10
      payload.header.count shouldBe 1
      payload.data.length shouldBe 1
      payload.data.head.fileExtension shouldBe Some("ome.tiff")
      payload.data.head.fileName shouldBe "file1.ome.tiff"
      payload.data.head.packageName shouldBe "file1.ome.tiff"
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
      json.extract[Map[String, Any]].keySet.head shouldBe "header"

      val payload = json.extract[DownloadManifestDTO]
      payload.header.size shouldBe 0
      payload.header.count shouldBe 0
      payload.data.length shouldBe 0
      payload.data.map(_.packageName).toSet shouldBe Set()
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
    createTestDownloadPackage("child2", Some(root))

    val request = s"""{"nodeIds": ["${root.nodeId}"]}"""

    postJson(
      s"/download-manifest",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val json = parse(response.body)
      json.extract[Map[String, Any]].keySet.head shouldBe "header"

      val payload = json.extract[DownloadManifestDTO]
      payload.header.size shouldBe 10
      payload.header.count shouldBe 1
      payload.data.length shouldBe 1
      payload.data.map(_.fileName).contains("child1.pdf") shouldBe true
      payload.data.map(_.packageName).toSet shouldBe Set("child1.pdf")
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
      val payload = parse(response.body).extract[DownloadManifestDTO]
      payload.header.size shouldBe 10
      payload.header.count shouldBe 1
      payload.data.length shouldBe 1
      payload.data.map(_.fileName).contains("file1.pdf") shouldBe false
      payload.data.map(_.fileName).contains("file2") shouldBe true
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

    val rootCollection2 = createTestDownloadPackage("root.2")
    val childCollection2 =
      createTestDownloadPackage("child.2", Some(rootCollection2))
    val grandChildCollectionC2 = createTestDownloadPackage(
      "childFileNoRoot.pdf",
      Some(childCollection2),
      PackageType.Image
    )
    createTestDownloadFile("childFileNoRoot.pdf", grandChildCollectionC2)

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
      val payload = parse(response.body).extract[DownloadManifestDTO]
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
      val payload = parse(response.body).extract[DownloadManifestDTO]
      payload.header.size shouldBe 20
      payload.header.count shouldBe 2
      payload.data.length shouldBe 2
      payload.data.map(_.fileName).contains("file2.pdf") shouldBe true
      payload.data.map(_.fileName).contains("file1.pdf") shouldBe true
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

  test("download-manifest produces a manifest for files in an external bucket") {
    val externalBucketName = "sparc-dev-discover50-use1"

    val rootPackage =
      createTestDownloadPackage("external-pkg", packageType = PackageType.Image)
    createTestDownloadFileInBucket(
      "external-file.tif",
      rootPackage,
      externalBucketName
    )

    val request = s"""{"nodeIds": ["${rootPackage.nodeId}"]}"""

    postJson(
      s"/download-manifest",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val payload = parse(response.body).extract[DownloadManifestDTO]
      payload.header.count shouldBe 1
      payload.data.length shouldBe 1

      val entry = payload.data.head
      entry.fileName shouldBe "external-file.tif"
      entry.packageName shouldBe "external-pkg"
      entry.url.toString should include(externalBucketName)
      entry.url.getQuery shouldBe null
    }
  }

  test(
    "download-manifest produces a manifest for files across multiple buckets"
  ) {
    val regularBucketName = "pennsieve-storage-use1"
    val externalBucketName = "sparc-dev-discover50-use1"

    val rootCollection =
      createTestDownloadPackage(
        "mixed-bucket-collection",
        packageType = PackageType.Collection
      )

    val regularPackage =
      createTestDownloadPackage(
        "regular-pkg",
        parent = Some(rootCollection),
        packageType = PackageType.Image
      )
    createTestDownloadFileInBucket(
      "regular-file.pdf",
      regularPackage,
      regularBucketName
    )

    val externalPackage =
      createTestDownloadPackage(
        "external-pkg",
        parent = Some(rootCollection),
        packageType = PackageType.Image
      )
    createTestDownloadFileInBucket(
      "external-file.tif",
      externalPackage,
      externalBucketName
    )

    val request = s"""{"nodeIds": ["${rootCollection.nodeId}"]}"""

    postJson(
      s"/download-manifest",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val payload = parse(response.body).extract[DownloadManifestDTO]
      payload.header.count shouldBe 2
      payload.data.length shouldBe 2

      val regularEntry = payload.data.find(_.fileName == "regular-file.pdf")
      val externalEntry = payload.data.find(_.fileName == "external-file.tif")
      regularEntry shouldBe defined
      externalEntry shouldBe defined
      regularEntry.get.url.toString should include(regularBucketName)
      externalEntry.get.url.toString should include(externalBucketName)
    }
  }

  test(
    "download-manifest produces a manifest with presigned URLs taking S3 versionId into account if file is published"
  ) {
    val storageBucket = "storage-bucket"
    val publishBucket = "publish-bucket"

    val publishedPackage =
      createTestDownloadPackage(
        "published-pkg",
        packageType = PackageType.Image
      )

    val publishedS3VersionId = UUID.randomUUID().toString
    val publishedFile = createTestDownloadFileInBucket(
      "published-file.tif",
      publishedPackage,
      publishBucket,
      publishedS3VersionId = Some(publishedS3VersionId)
    )

    val unpublishedPackage =
      createTestDownloadPackage(
        "unpublished-pkg",
        packageType = PackageType.Image
      )
    val unpublishedFile = createTestDownloadFileInBucket(
      "unpublished-file.tif",
      unpublishedPackage,
      storageBucket
    )

    val request =
      s"""{"nodeIds": ["${publishedPackage.nodeId}", "${unpublishedPackage.nodeId}"]}"""

    postJson(
      s"/download-manifest",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val payload = parse(response.body).extract[DownloadManifestDTO]
      payload.header.count shouldBe 2
      payload.data.length shouldBe 2

      val publishedEntry =
        payload.data.find(_.nodeId == publishedPackage.nodeId).value
      publishedEntry.fileName shouldBe publishedFile.name
      publishedEntry.packageName shouldBe publishedPackage.name

      val unpublishedEntry =
        payload.data.find(_.nodeId == unpublishedPackage.nodeId).value
      unpublishedEntry.fileName shouldBe unpublishedFile.name
      unpublishedEntry.packageName shouldBe unpublishedPackage.name

      publishedEntry.url.toString should include(publishBucket)
      publishedEntry.url.getQuery shouldBe s"versionId=$publishedS3VersionId"

      unpublishedEntry.url.toString should include(storageBucket)
      unpublishedEntry.url.getQuery shouldBe null
    }
  }
}
