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
import com.pennsieve.dtos.{
  DataCanvasDTO,
  DownloadManifestDTO,
  DownloadRequest
}
import com.pennsieve.helpers.{ APIContainers, MockObjectStore }
import com.pennsieve.models.{
  CognitoId,
  DBPermission,
  DataCanvas,
  DataCanvasFolder,
  DataCanvasFolderPath,
  Dataset,
  FileObjectType,
  FileProcessingState,
  FileState,
  FileType,
  NodeCodes,
  Organization,
  Package,
  PackageState,
  PackageType,
  Role,
  User
}
import org.json4s.jackson.Serialization.write
import org.scalatest.EitherValues._

import java.util.UUID
import scala.concurrent.Future
import scala.util.Random

class TestDataCanvasController extends BaseApiUnitTest {

  var loggedInUser: User = _
  var colleagueUser: User = _
  var externalUser: User = _
  var loggedInOrganization: Organization = _
  var externalOrganization: Organization = _
  var loggedInJwt: String = _
  var colleagueJwt: String = _
  var externalJwt: String = _
  var secureContainer: APIContainers.SecureAPIContainer = _

  val bogusCanvasId = 314159
  val bogusFolderId = 271828

  override def beforeAll(): Unit = {
    super.beforeAll()
    implicit val httpClient: HttpRequest => Future[HttpResponse] = { _ =>
      Future.successful(HttpResponse())
    }
    addServlet(
      new DataCanvasController(
        insecureContainer,
        secureContainerBuilder,
        new MockObjectStore("test.avi"),
        system,
        system.dispatcher
      ),
      "/*"
    )
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    state.clear()
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

    loggedInUser = mkUser("test@test.com")
    colleagueUser = mkUser("colleague@test.com")
    externalUser = mkUser("external@test.com")

    addOrgMember(loggedInOrganization, loggedInUser, DBPermission.Administer)
    addOrgMember(loggedInOrganization, colleagueUser, DBPermission.Delete)
    addOrgMember(externalOrganization, externalUser, DBPermission.Delete)

    loggedInJwt = mintUserJwt(loggedInUser, loggedInOrganization)
    colleagueJwt = mintUserJwt(colleagueUser, loggedInOrganization)
    externalJwt = mintUserJwt(externalUser, externalOrganization)

    secureContainer = secureContainerBuilder(loggedInUser, loggedInOrganization)
    secureContainer.datasetStatusManager.resetDefaultStatusOptions.await.value
  }

  private def mkUser(email: String): User = {
    val id = state.newId()
    val u = User(
      nodeId = NodeCodes.generateId(NodeCodes.userCode),
      email = email,
      firstName = "first",
      middleInitial = None,
      lastName = "last",
      degree = None,
      credential = "cred",
      color = "",
      url = "http://test.com",
      authyId = 0,
      isSuperAdmin = false,
      isIntegrationUser = false,
      preferredOrganizationId = None,
      status = true,
      orcidAuthorization = None,
      cognitoId = Some(CognitoId.UserPoolId(UUID.randomUUID())),
      id = id
    )
    state.users.put(id, u)
    u
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

  private def randomString(length: Int = 32): String =
    Random.alphanumeric.take(length).mkString

  private def createDataCanvas(
    name: String = randomString(32),
    description: String = randomString(64),
    isPublic: Boolean = false,
    container: APIContainers.SecureAPIContainer = null
  ): DataCanvas = {
    val c = if (container == null) secureContainer else container
    c.dataCanvasManager
      .create(
        name,
        description,
        isPublic = Some(isPublic),
        nodeId = NodeCodes.generateId(NodeCodes.dataCanvasCode)
      )
      .await
      .value
  }

  private def createFolder(
    canvasId: Int,
    name: String = randomString(),
    parent: Option[Int] = None
  ): DataCanvasFolder =
    secureContainer.dataCanvasManager
      .createFolder(
        canvasId,
        name,
        parent.orElse(Some(getRootFolder(canvasId).id))
      )
      .await
      .value

  private def getRootFolder(canvasId: Int): DataCanvasFolder =
    secureContainer.dataCanvasManager.getRootFolder(canvasId).await.value

  private def createDataSet(name: String): Dataset =
    secureContainer.datasetManager.create(name, Some("desc")).await.value

  private def createPackage(
    ds: Dataset,
    name: String,
    `type`: PackageType = PackageType.Collection
  ): Package =
    secureContainer.packageManager
      .create(name, `type`, PackageState.READY, ds, Some(loggedInUser.id), None)
      .await
      .value

  private def createFile(name: String, dataset: Dataset, pkg: Package) =
    secureContainer.fileManager
      .create(
        name = name,
        `type` = FileType.CSV,
        `package` = pkg,
        s3Bucket = "test-data-bucket",
        s3Key = s"${dataset.id}/${UUID.randomUUID().toString}/$name",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        size = 1024,
        fileChecksum = None,
        uploadedState = Some(FileState.UPLOADED)
      )
      .await
      .value

  private def attachPackage(
    canvas: DataCanvas,
    folder: DataCanvasFolder,
    dataset: Dataset,
    pkg: Package,
    organization: Organization = loggedInOrganization
  ): Unit =
    secureContainer.dataCanvasManager
      .attachPackage(canvas.id, folder.id, dataset.id, pkg.id, organization.id)
      .await
      .value

  // -------------- Tests --------------------------------------------------

  // GET tests
  test("get requires authentication") {
    val canvas = createDataCanvas("g1", "g1")
    get(s"/${canvas.id}") {
      status should equal(401)
    }
  }

  test("get an existing data-canvas") {
    val canvas = createDataCanvas("g2", "g2")
    get(
      s"/${canvas.id}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }
  }

  test("get a non-existant data-canvas should return a 404") {
    get(
      s"/$bogusCanvasId",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  test("get a user's own data-canvases requires authentication") {
    createDataCanvas("u1", "u1")
    get("/", headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()) {
      status should equal(200)
    }
  }

  test("get a user's own data-canvases fails when not authenticated") {
    createDataCanvas("u2", "u2")
    get("/") {
      status should equal(401)
    }
  }

  test("get a user's own data-canvases returns only their data-canvases") {
    postJson(
      "/",
      write(
        CreateDataCanvasRequest(
          name = "test: user 1's canvas",
          description = "test: create a new data-canvas"
        )
      ),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
    }
    postJson(
      "/",
      write(
        CreateDataCanvasRequest(
          name = "test: user 2's canvas",
          description = "test: create a new data-canvas"
        )
      ),
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
    }
    get("/", headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()) {
      status should equal(200)
      val result: List[DataCanvasDTO] = parsedBody
        .extract[List[DataCanvasDTO]]
      result.length should equal(1)
    }
  }

  // POST tests
  test("create a new data-canvas") {
    val req = write(
      CreateDataCanvasRequest(
        name = "test: create a new data-canvas",
        description = "test: create a new data-canvas"
      )
    )
    postJson(
      "/",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
    }
  }

  test("create requires authentication") {
    val req = write(
      CreateDataCanvasRequest(
        name = "test: create requires authentication",
        description = "test: create requires authentication"
      )
    )
    postJson("/", req) {
      status should equal(401)
    }
  }

  test("create does not permit name > 255 chars") {
    val req = write(
      CreateDataCanvasRequest(
        name = randomString(256),
        description = "test: create a new data-canvas"
      )
    )
    postJson(
      "/",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
    }
  }

  test("create by default data-canvas is not public") {
    val req = write(
      CreateDataCanvasRequest(
        name = randomString(64),
        description = "test: create a new data-canvas"
      )
    )
    postJson(
      "/",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
      parsedBody.extract[DataCanvasDTO].isPublic shouldBe false
    }
  }

  test("create a public data-canvas") {
    val req = write(
      CreateDataCanvasRequest(
        name = randomString(64),
        description = "test: create a new data-canvas",
        isPublic = Some(true)
      )
    )
    postJson(
      "/",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
      parsedBody.extract[DataCanvasDTO].isPublic shouldBe true
    }
  }

  // PUT tests
  test("update requires authentication (401)") {
    val canvas = createDataCanvas()
    val req = write(
      UpdateDataCanvasRequest(
        name = Some(randomString()),
        description = Some("updated")
      )
    )
    putJson(s"/${canvas.id}", req) {
      status should equal(401)
    }
  }

  test("update an existing data-canvas") {
    val canvas = createDataCanvas()
    val req = write(
      UpdateDataCanvasRequest(
        name = Some(randomString()),
        description = Some("updated")
      )
    )
    putJson(
      s"/${canvas.id}",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
    }
  }

  test("update a non-existent data-canvas should fail") {
    val req = write(
      UpdateDataCanvasRequest(
        name = Some(randomString()),
        description = Some("updated")
      )
    )
    putJson(
      s"/$bogusCanvasId",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  test("update does not permit name > 255 chars") {
    val canvas = createDataCanvas()
    val req = write(
      UpdateDataCanvasRequest(
        name = Some(randomString(256)),
        description = Some("updated")
      )
    )
    putJson(
      s"/${canvas.id}",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
    }
  }

  test("update a data-canvas to be publicly visible") {
    val canvas = createDataCanvas()
    canvas.isPublic shouldBe false
    val req = write(
      UpdateDataCanvasRequest(
        name = Some(randomString()),
        description = Some("updated"),
        isPublic = Some(true)
      )
    )
    putJson(
      s"/${canvas.id}",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
      parsedBody.extract[DataCanvasDTO].isPublic shouldBe true
    }
  }

  test("update data-canvas name and description are optional") {
    val canvas = createDataCanvas()
    putJson(
      s"/${canvas.id}",
      write(UpdateDataCanvasRequest()),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
    }
  }

  // DELETE tests
  test("delete requires authentication") {
    val canvas = createDataCanvas()
    delete(s"/${canvas.id}") {
      status should equal(401)
    }
  }

  test("delete an existing data-canvas") {
    val canvas = createDataCanvas()
    delete(
      s"/${canvas.id}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(204)
    }
  }

  test("delete a non-existent data-canvas should fail") {
    delete(
      s"/$bogusCanvasId",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  // Public/All view tests
  test("public get data-canvases requires authentication") {
    val canvas = createDataCanvas(isPublic = true)
    get(s"/get/${canvas.nodeId}") {
      status should equal(401)
    }
  }

  test("public get a publicly available data-canvas when authenticated") {
    val canvas = createDataCanvas(isPublic = true)
    get(
      s"/get/${canvas.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }
  }

  test("public get fails because data-canvas is not publicly available") {
    val canvas = createDataCanvas(isPublic = false)
    get(
      s"/get/${canvas.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(401)
    }
  }

  test(
    "public get a publicly available data-canvas from a different organization"
  ) {
    val canvas = createDataCanvas(isPublic = true)
    get(
      s"/get/${canvas.nodeId}",
      headers = authorizationHeader(externalJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }
  }

  test(
    "public get all publicly available data-canvas for the user's organization"
  ) {
    createDataCanvas(isPublic = true)
    createDataCanvas(isPublic = false)
    get(
      s"/get/organization/${loggedInOrganization.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody.extract[List[DataCanvasDTO]].length should equal(1)
    }
  }

  // Folder tests
  test("folder create requires authentication") {
    val canvas = createDataCanvas()
    val req =
      write(CreateDataCanvasFolder(name = randomString(), parent = None))
    postJson(s"/${canvas.id}/folder", req) {
      status should equal(401)
    }
  }

  test("folder create succeeds when authenticated") {
    val canvas = createDataCanvas()
    val req =
      write(CreateDataCanvasFolder(name = randomString(), parent = None))
    postJson(
      s"/${canvas.id}/folder",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
    }
  }

  test("folder create fails for non-existent data-canvas") {
    val req =
      write(CreateDataCanvasFolder(name = randomString(), parent = None))
    postJson(
      s"/$bogusCanvasId/folder",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  test("folder create fails when name is too long") {
    val canvas = createDataCanvas()
    val req =
      write(CreateDataCanvasFolder(name = randomString(266), parent = None))
    postJson(
      s"/${canvas.id}/folder",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
    }
  }

  test("folder create fails on duplicate name under same parent") {
    val canvas = createDataCanvas()
    val rootFolder = getRootFolder(canvas.id)
    val filesFolder = createFolder(canvas.id, "Files", Some(rootFolder.id))

    val req = write(
      CreateDataCanvasFolder(name = "Sub-Files", parent = Some(filesFolder.id))
    )
    postJson(
      s"/${canvas.id}/folder",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
    }
    postJson(
      s"/${canvas.id}/folder",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
    }
  }

  test("folder get requires authentication") {
    val canvas = createDataCanvas()
    val folder = createFolder(canvas.id, randomString())
    get(s"/${canvas.id}/folder/${folder.id}") {
      status should equal(401)
    }
  }

  test("folder get succeeds when authenticated") {
    val canvas = createDataCanvas()
    val folder = createFolder(canvas.id, randomString())
    get(
      s"/${canvas.id}/folder/${folder.id}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }
  }

  test("folder get fails on non-existent folder") {
    val canvas = createDataCanvas()
    createFolder(canvas.id, randomString())
    get(
      s"/${canvas.id}/folder/$bogusFolderId",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  test("folder rename requires authentication") {
    val canvas = createDataCanvas()
    val folder = createFolder(canvas.id, "FirstName")
    val req = write(RenameDataCanvasFolder("FirstName", "SecondName"))
    putJson(s"/${canvas.id}/folder/${folder.id}/rename", req) {
      status should equal(401)
    }
  }

  test("folder rename succeeds when authenticated") {
    val canvas = createDataCanvas()
    val folder = createFolder(canvas.id, "FirstName")
    val req = write(RenameDataCanvasFolder("FirstName", "SecondName"))
    putJson(
      s"/${canvas.id}/folder/${folder.id}/rename",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
    }
  }

  test("folder rename fails when name is too long") {
    val canvas = createDataCanvas()
    val folder = createFolder(canvas.id, "FirstName")
    val req = write(RenameDataCanvasFolder("FirstName", randomString(256)))
    putJson(
      s"/${canvas.id}/folder/${folder.id}/rename",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
    }
  }

  test("folder move requires authentication") {
    val canvas = createDataCanvas()
    val parent1 = createFolder(canvas.id, "parent-1")
    val parent2 = createFolder(canvas.id, "parent-2")
    val folder = createFolder(canvas.id, "sub-folder", Some(parent1.id))
    val req = write(MoveDataCanvasFolder(parent1.id, parent2.id))
    putJson(s"/${canvas.id}/folder/${folder.id}/move", req) {
      status should equal(401)
    }
  }

  test("folder move succeeds when authenticated") {
    val canvas = createDataCanvas()
    val parent1 = createFolder(canvas.id, "parent-1")
    val parent2 = createFolder(canvas.id, "parent-2")
    val folder = createFolder(canvas.id, "sub-folder", Some(parent1.id))
    val req = write(MoveDataCanvasFolder(parent1.id, parent2.id))
    putJson(
      s"/${canvas.id}/folder/${folder.id}/move",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
    }
  }

  test("folder move fails on non-existent folder") {
    val canvas = createDataCanvas()
    val parent1 = createFolder(canvas.id, "parent-1")
    val parent2 = createFolder(canvas.id, "parent-2")
    createFolder(canvas.id, "sub-folder", Some(parent1.id))
    val req = write(MoveDataCanvasFolder(parent1.id, parent2.id))
    putJson(
      s"/${canvas.id}/folder/$bogusFolderId/move",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  test("folder move fails on non-existent old parent") {
    val canvas = createDataCanvas()
    val parent1 = createFolder(canvas.id, "parent-1")
    val parent2 = createFolder(canvas.id, "parent-2")
    val folder = createFolder(canvas.id, "sub-folder", Some(parent1.id))
    val req = write(MoveDataCanvasFolder(bogusFolderId, parent2.id))
    putJson(
      s"/${canvas.id}/folder/${folder.id}/move",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  test("folder move fails on non-existent new parent") {
    val canvas = createDataCanvas()
    val parent1 = createFolder(canvas.id, "parent-1")
    val folder = createFolder(canvas.id, "sub-folder", Some(parent1.id))
    val req = write(MoveDataCanvasFolder(parent1.id, bogusFolderId))
    putJson(
      s"/${canvas.id}/folder/${folder.id}/move",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  test("folder delete requires authentication") {
    val canvas = createDataCanvas()
    val folder = createFolder(canvas.id)
    delete(s"/${canvas.id}/folder/${folder.id}") {
      status should equal(401)
    }
  }

  test("folder delete succeeds when authenticated") {
    val canvas = createDataCanvas()
    val folder = createFolder(canvas.id)
    delete(
      s"/${canvas.id}/folder/${folder.id}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(204)
    }
  }

  test("folder delete fails for non-existent folder") {
    val canvas = createDataCanvas()
    createFolder(canvas.id)
    delete(
      s"/${canvas.id}/folder/$bogusFolderId",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  test("folder delete fails when removing root folder") {
    val canvas = createDataCanvas()
    val rootFolder = getRootFolder(canvas.id)
    delete(
      s"/${canvas.id}/folder/${rootFolder.id}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
    }
  }

  test("folder delete removes sub-folders") {
    val canvas = createDataCanvas()
    val topFolder = createFolder(canvas.id, "Top-Folder")
    val subFolder1 = createFolder(canvas.id, "sub-folder-1", Some(topFolder.id))
    createFolder(canvas.id, "sub-folder-2", Some(topFolder.id))

    delete(
      s"/${canvas.id}/folder/${topFolder.id}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(204)
    }
    get(
      s"/${canvas.id}/folder/${subFolder1.id}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  test("folder get paths should return entire folder structure") {
    val canvas = createDataCanvas()
    val researchFolder = createFolder(canvas.id, "research")
    createFolder(canvas.id, "phase1", Some(researchFolder.id))
    createFolder(canvas.id, "phase2", Some(researchFolder.id))

    get(
      s"/${canvas.id}/folder/paths",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody.extract[List[DataCanvasFolderPath]].length should equal(4)
    }
  }

  // Package tests
  test("package attach requires authentication") {
    val ds = createDataSet("a test dataset")
    val pkg = createPackage(ds, "a test package")
    val canvas = createDataCanvas()
    val folder = createFolder(canvas.id)
    val req = write(
      AttachPackageRequest(
        datasetId = ds.id,
        packageId = pkg.id,
        organizationId = Some(loggedInOrganization.id)
      )
    )
    postJson(s"/${canvas.id}/folder/${folder.id}/package", req) {
      status should equal(401)
    }
  }

  test("package attach succeeds when no organization is specified") {
    val ds = createDataSet("a test dataset")
    val pkg = createPackage(ds, "a test package")
    val canvas = createDataCanvas()
    val folder = createFolder(canvas.id)
    val req =
      write(AttachPackageRequest(datasetId = ds.id, packageId = pkg.id, None))
    postJson(
      s"/${canvas.id}/folder/${folder.id}/package",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
    }
  }

  test("package attach succeeds when an organization is specified") {
    val ds = createDataSet("a test dataset")
    val pkg = createPackage(ds, "a test package")
    val canvas = createDataCanvas()
    val folder = createFolder(canvas.id)
    val req = write(
      AttachPackageRequest(
        datasetId = ds.id,
        packageId = pkg.id,
        organizationId = Some(loggedInOrganization.id)
      )
    )
    postJson(
      s"/${canvas.id}/folder/${folder.id}/package",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
    }
  }

  test("package detach requires authentication") {
    val ds = createDataSet("a test dataset")
    val pkg = createPackage(ds, "a test package")
    val canvas = createDataCanvas()
    val folder = createFolder(canvas.id)
    val req = write(
      AttachPackageRequest(
        datasetId = ds.id,
        packageId = pkg.id,
        organizationId = Some(loggedInOrganization.id)
      )
    )
    postJson(
      s"/${canvas.id}/folder/${folder.id}/package",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
    }

    delete(s"/${canvas.id}/folder/${folder.id}/package/${pkg.id}") {
      status should equal(401)
    }
  }

  // Download manifest
  test("download manifest for simple package and canvas structures") {
    val ds = createDataSet("dataset for download manifest")
    val package1 = createPackage(ds, "1.csv", `type` = PackageType.CSV)
    val package2 = createPackage(ds, "2.csv", `type` = PackageType.CSV)
    val package3 = createPackage(ds, "3.csv", `type` = PackageType.CSV)
    createFile("1.csv", ds, package1)
    createFile("2.csv", ds, package2)
    createFile("3.csv", ds, package3)

    val canvas = createDataCanvas(
      "data-canvas for download manifest",
      "data-canvas for download manifest"
    )
    val folder1 = createFolder(canvas.id, "folder-1")
    val folder2 = createFolder(canvas.id, "folder-2")
    val folder3a = createFolder(canvas.id, "complete")
    val folder3b = createFolder(canvas.id, "folder-3", Some(folder3a.id))
    attachPackage(canvas, folder1, ds, package1)
    attachPackage(canvas, folder2, ds, package2)
    attachPackage(canvas, folder3b, ds, package3)

    val req = write(DownloadRequest(nodeIds = List(canvas.nodeId)))
    postJson(
      "/download-manifest",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
      parsedBody.extract[DownloadManifestDTO].data.length should equal(3)
    }
  }
}
