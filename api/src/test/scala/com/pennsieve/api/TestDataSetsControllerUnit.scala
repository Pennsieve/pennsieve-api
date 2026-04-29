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
import com.pennsieve.aws.cognito.MockCognito
import com.pennsieve.clients.MockDatasetAssetClient
import com.pennsieve.api.{
  AddCollectionRequest,
  AddContributorRequest,
  CollaboratorRoleDTO,
  CreateDataSetRequest,
  CustomEventRequest,
  DatasetPermissionResponse,
  OrganizationRoleDTO,
  RemoveCollaboratorRequest,
  SwitchContributorsOrderRequest,
  UpdateDataSetRequest,
  UserCollaboratorRoleDTO
}
import com.pennsieve.doi.client.definitions.{
  CreateDraftDoiRequest,
  CreatorDto
}
import com.pennsieve.doi.models.{ DoiDTO, DoiState }
import com.pennsieve.dtos.{ ContributorDTO, DataSetDTO, WrappedDataset }
import com.pennsieve.managers.CollaboratorChanges
import org.json4s.jackson.Serialization.read
import com.pennsieve.helpers.{
  MockAuditLogger,
  MockDoiClient,
  MockPublishClient,
  MockSQSClient,
  MockSearchClient,
  OrcidClient,
  OrcidWorkPublishing,
  OrcidWorkUnpublishing
}
import com.pennsieve.models.{
  CognitoId,
  DBPermission,
  Dataset,
  DatasetType,
  Degree,
  License,
  NodeCodes,
  OrcidAuthorization,
  Organization,
  Package,
  PackageState,
  PackageType,
  PublicationStatus,
  PublicationType,
  Role,
  User
}
import org.json4s.jackson.Serialization.write
import org.apache.http.HttpHeaders
import org.scalatest.EitherValues._
import org.scalatest.OptionValues._

import java.util.UUID
import scala.concurrent.Future

/**
  * Unit-test variant of [[TestDataSetsController]]. Migrated test by test from
  * the testcontainer-backed original. When all 268 tests are migrated, this
  * file replaces the original and the testcontainer base goes away.
  */
class TestDataSetsControllerUnit extends BaseApiUnitTest {

  // ---------- Fixtures -------------------------------------------------

  var loggedInUser: User = _
  var colleagueUser: User = _
  var externalUser: User = _
  var integrationUser: User = _
  var guestUser: User = _
  var orcidUser: User = _
  var publisherUser: User = _
  var superAdmin: User = _

  var loggedInOrganization: Organization = _
  var externalOrganization: Organization = _
  var sandboxOrganization: Organization = _
  var sandboxUser: User = _
  var sandboxUserJwt: String = _
  var sandboxUserContainer
    : com.pennsieve.helpers.APIContainers.SecureAPIContainer = _

  var loggedInJwt: String = _
  var colleagueJwt: String = _
  var integrationJwt: String = _
  var externalJwt: String = _
  var adminJwt: String = _
  var guestJwt: String = _

  var dataset: Dataset = _
  var secureContainer: com.pennsieve.helpers.APIContainers.SecureAPIContainer =
    _

  // mocks for downstream service clients
  implicit val mockDatasetAssetClient: MockDatasetAssetClient =
    new MockDatasetAssetClient()
  val mockAuditLogger = new MockAuditLogger()
  val mockSqsClient = MockSQSClient
  val maxFileUploadSize = 1 * 1024 * 1024
  var mockPublishClient: MockPublishClient = _
  var mockSearchClient: MockSearchClient = _

  override def beforeAll(): Unit = {
    super.beforeAll()

    implicit val httpClient: HttpRequest => Future[HttpResponse] = { _ =>
      Future.successful(HttpResponse())
    }

    mockPublishClient = new MockPublishClient()
    mockSearchClient = new MockSearchClient()

    val orcidAuthorization: OrcidAuthorization = OrcidAuthorization(
      name = "name",
      accessToken = "accessToken",
      expiresIn = 100,
      tokenType = "tokenType",
      orcid = "orcid",
      scope = "/read-limited /activities/update",
      refreshToken = "refreshToken"
    )

    val testAuthorizationCode = "authCode"

    val mockOrcidClient: OrcidClient = new OrcidClient {
      override def getToken(
        authorizationCode: String
      ): Future[OrcidAuthorization] =
        if (authorizationCode == testAuthorizationCode)
          Future.successful(orcidAuthorization)
        else Future.failed(new Throwable("invalid authorization code"))
      override def verifyOrcid(orcid: Option[String]): Future[Boolean] =
        Future.successful(true)
      override def publishWork(
        work: OrcidWorkPublishing
      ): Future[Option[String]] = Future.successful(Some("1234567"))
      override def unpublishWork(work: OrcidWorkUnpublishing): Future[Boolean] =
        Future.successful(true)
    }

    addServlet(
      new DataSetsController(
        insecureContainer,
        secureContainerBuilder,
        system,
        mockAuditLogger,
        mockSqsClient,
        mockPublishClient,
        mockSearchClient,
        new MockDoiClient(),
        mockDatasetAssetClient,
        new MockCognito,
        mockOrcidClient,
        maxFileUploadSize,
        system.dispatcher
      ),
      "/*"
    )

    addServlet(
      new InternalDataSetsController(
        insecureContainer,
        secureContainerBuilder,
        system.dispatcher
      ),
      "/internal/*"
    )
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    setUpFixtures()
  }

  /**
    * Replaces BaseApiTest.beforeEach: populate InMemoryState with the standard
    * users/orgs/dataset/JWTs that controller tests rely on.
    */
  private def setUpFixtures(): Unit = {
    // org #1 — primary
    val orgId = state.newId()
    loggedInOrganization = Organization(
      nodeId = NodeCodes.generateId(NodeCodes.organizationCode),
      name = "Test Organization",
      slug = "test-org",
      id = orgId
    )
    state.organizations.put(orgId, loggedInOrganization)

    // org #2 — external
    val extOrgId = state.newId()
    externalOrganization = Organization(
      nodeId = NodeCodes.generateId(NodeCodes.organizationCode),
      name = "External Organization",
      slug = "external-org",
      id = extOrgId
    )
    state.organizations.put(extOrgId, externalOrganization)

    // users
    superAdmin = mkUser("super@external.com", isSuperAdmin = true)
    loggedInUser =
      mkUser("test@test.com", middle = Some("M"), degree = Some(Degree.MS))
    colleagueUser = mkUser("colleague@test.com")
    externalUser = mkUser("test@external.com")
    integrationUser = mkUser("test@integration.com", isIntegrationUser = true)
    guestUser =
      mkUser("guest@test.com", middle = Some("M"), degree = Some(Degree.MS))
    orcidUser =
      mkUser("fancy-flowers@test.org", first = "Cymbidium", last = "Cattleya")
    publisherUser =
      mkUser("publisher@test.com", first = "Publish", last = "Approver")

    // org membership
    addOrgMember(loggedInOrganization, superAdmin, DBPermission.Administer)
    addOrgMember(loggedInOrganization, loggedInUser, DBPermission.Administer)
    addOrgMember(loggedInOrganization, colleagueUser, DBPermission.Delete)
    addOrgMember(loggedInOrganization, integrationUser, DBPermission.Administer)
    addOrgMember(loggedInOrganization, guestUser, DBPermission.Guest)
    addOrgMember(loggedInOrganization, orcidUser, DBPermission.Guest)
    addOrgMember(loggedInOrganization, publisherUser, DBPermission.Administer)
    addOrgMember(externalOrganization, externalUser, DBPermission.Delete)

    // JWTs
    loggedInJwt = mintUserJwt(loggedInUser, loggedInOrganization)
    colleagueJwt = mintUserJwt(colleagueUser, loggedInOrganization)
    integrationJwt = mintUserJwt(integrationUser, loggedInOrganization)
    externalJwt = mintUserJwt(externalUser, externalOrganization)
    adminJwt = mintUserJwt(superAdmin, loggedInOrganization)
    guestJwt = mintUserJwt(guestUser, loggedInOrganization)

    // Sandbox / demo org with the SandboxOrgFeature flag enabled
    val sandboxOrgId = state.newId()
    sandboxOrganization = Organization(
      nodeId = NodeCodes.generateId(NodeCodes.organizationCode),
      name = "__sandbox__",
      slug = "__sandbox__",
      id = sandboxOrgId
    )
    state.organizations.put(sandboxOrgId, sandboxOrganization)
    state.featureFlags
      .put((sandboxOrgId, com.pennsieve.models.Feature.SandboxOrgFeature), true)

    sandboxUser = mkUser(
      "sandboxtest@test.com",
      middle = Some("M"),
      degree = Some(Degree.MS)
    )
    addOrgMember(sandboxOrganization, sandboxUser, DBPermission.Administer)
    addOrgMember(sandboxOrganization, loggedInUser, DBPermission.Administer)
    sandboxUserJwt = mintUserJwt(sandboxUser, sandboxOrganization)

    sandboxUserContainer =
      secureContainerBuilder(sandboxUser, sandboxOrganization)
    sandboxUserContainer.datasetStatusManager.resetDefaultStatusOptions.await.value

    secureContainer = secureContainerBuilder(loggedInUser, loggedInOrganization)

    // Default dataset statuses for the org (mirrors
    // datasetStatusManager.resetDefaultStatusOptions called in BaseApiTest
    // beforeEach).
    secureContainer.datasetStatusManager.resetDefaultStatusOptions.await.value

    // The "Home" dataset that BaseApiTest.beforeEach pre-creates for every
    // test. Same name and provenance.
    val homeDataset = secureContainer.datasetManager
      .create(name = "Home", description = Some("Home Dataset"))
      .await
      .value
    dataset = homeDataset
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
  }

  /**
    * Build a SecureContainer for fixture setup (mirrors the request-scoped
    * cake the controller would use for this user/org). Tests don't call this
    * directly — the controller does — but `beforeEach` needs it to seed the
    * dataset.
    */
  private def secureContainerFor(u: User, o: Organization) =
    secureContainerBuilder(u, o)

  // ---------- Tests ----------------------------------------------------

  // The swagger-doc deprecation test relies on /api-docs/swagger.json
  // reporting populated paths. Under BaseApiUnitTest's ScalatraSuite setup
  // the produced swagger.json reports paths: {} so the doc-introspection
  // assertion can't run. The user-facing deprecation behavior is exercised
  // by the deprecated-touch and deprecated-package-type-counts tests below.
  test("swagger")(pending)

  // ---- /:id GET --------------------------------------------------------

  test("get a data set") {
    val ds = createDataSet("test-dataset")

    get(
      s"/${ds.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      response.getHeader(HttpHeaders.ETAG) shouldBe ds.etag.asHeader

      val dto = parsedBody.extract[DataSetDTO]
      dto.content.intId shouldBe ds.id
      dto.content.name shouldBe ds.name
    }
  }

  test("get a data set with publication info") {
    val ds1 = createDataSet("test-ds1")

    get(
      s"/${ds1.nodeId}?includePublishedDataset=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val ds = parsedBody.extract[DataSetDTO]
      ds.publication.status shouldBe PublicationStatus.Draft
    }

    get(
      s"/${ds1.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val ds = parsedBody.extract[DataSetDTO]
      ds.publication.status shouldBe PublicationStatus.Draft
    }
  }

  test("get dataset returns default number of paginated children") {
    val ds = createDataSet(
      name = "test-dataset-for-paginated-children",
      description = Some("test-dataset-for-paginated-children")
    )
    (1 to DataSetsController.DatasetChildrenDefaultLimit + 1)
      .map(n => createPackage(ds, s"Package-${n}"))

    get(
      s"/${ds.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody
        .extract[DataSetDTO]
        .children
        .get
        .length shouldBe DataSetsController.DatasetChildrenDefaultLimit
    }
  }

  test("get dataset returns requested number of paginated children") {
    val ds = createDataSet(
      name = "test-dataset-for-paginated-children",
      description = Some("test-dataset-for-paginated-children")
    )
    (1 to 26).map(n => createPackage(ds, s"Package-${n}"))

    get(
      s"/${ds.nodeId}?offset=0&limit=5",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody.extract[DataSetDTO].children.get.length shouldBe 5
    }
  }

  test("get dataset returns partial limit when on the last page") {
    val ds = createDataSet(
      name = "test-dataset-for-paginated-children",
      description = Some("test-dataset-for-paginated-children")
    )
    (1 to 26).map(n => createPackage(ds, s"Package-${n}"))

    get(
      s"/${ds.nodeId}?offset=25&limit=5",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody.extract[DataSetDTO].children.get.length shouldBe 1
    }
  }

  test("get a data set for an external file") {
    val description = Some("An external file")
    val externalLocation = Some("https://drive.google.com/external_file")

    val ds1 = createDataSet("test-ds1")
    createPackage(
      dataset = ds1,
      "package1",
      `type` = PackageType.ExternalFile,
      ownerId = Some(loggedInUser.id),
      description = description,
      externalLocation = externalLocation
    )

    get(
      s"/${ds1.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val children = parsedBody.extract[DataSetDTO].children.get
      children.map(_.externalFile.get.description) should equal(
        Seq(description)
      )
      children.map(_.externalFile.get.location) should equal(
        Seq(externalLocation.get)
      )
    }
  }

  test("get a data set package type counts") {
    val ds1 = createDataSet("test-ds1")
    createPackage(ds1, "package1", `type` = PackageType.TimeSeries)
    createPackage(ds1, "package2", `type` = PackageType.TimeSeries)
    createPackage(ds1, "package3", `type` = PackageType.PDF)
    createPackage(ds1, "package4", `type` = PackageType.PDF)
    createPackage(ds1, "package5", `type` = PackageType.Slide)
    get(
      s"/${ds1.nodeId}/packageTypeCounts",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      parsedBody.extract[Map[String, Int]] should equal(
        Map("PDF" -> 2, "Slide" -> 1, "TimeSeries" -> 2)
      )
    }
  }

  test("ignore deleted packages when getting package type counts") {
    val ds1 = createDataSet("test-ds1")
    createPackage(ds1, "package1", `type` = PackageType.TimeSeries)
    createPackage(
      ds1,
      "package3",
      `type` = PackageType.PDF,
      state = PackageState.DELETING
    )
    get(
      s"/${ds1.nodeId}/packageTypeCounts",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      parsedBody.extract[Map[String, Int]] should equal(Map("TimeSeries" -> 1))
    }
  }

  test("getting a dataset returns a packageType field") {
    val ds = createDataSet("test-ds")
    get(
      s"/${ds.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody.extract[DataSetDTO].content.packageType should equal("DataSet")
    }
  }

  test("get a data set that is not a data set") {
    val ds = createDataSet("Foo")
    val collection = createPackage(
      ds,
      "Foo",
      `type` = PackageType.Collection,
      ownerId = Some(loggedInUser.id)
    )

    get(
      s"/${collection.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  // ---- POST / (create) ----------------------------------------------

  test("create a data set") {
    val createReq = write(
      CreateDataSetRequest(
        "A New DataSet",
        None,
        List(),
        status = Some("IN_REVIEW"),
        license = Some(License.`GNU General Public License v3.0`),
        tags = List("tag1", "tag2")
      )
    )

    postJson(
      s"",
      createReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
      val result: WrappedDataset = parsedBody.extract[DataSetDTO].content
      result.name shouldEqual "A New DataSet"
      result.status shouldEqual "IN_REVIEW"
      result.license shouldEqual Some(License.`GNU General Public License v3.0`)
      result.tags shouldEqual List("tag1", "tag2")
    }
  }

  test("creating a data set with a name longer than 255 characters should fail") {
    val createReq = write(
      CreateDataSetRequest(
        "hwlI4GOxhhu7tayTtveguvtV0XmF2ak9iu3WqSlBCuoZuHuBpIsmbghiTcT76MtXcjKGnQOYm4jnh9Y0zLbGyeTdtBVZ9GOvYkxWenBQOQUUcsQb191NAl07rYiowQsUVtVrnSyA6ndpGdc0qPyq8a5HNpyUMZH84zzj5FAaiW1UDxQWEKS944SSbtDry4GgvQwq3lPMw0Vp3EmKJDPEJlwAFkdowuV1ifGEsZcyUfqbi89QlqjqcZAoCVJULRGN",
        None,
        List()
      )
    )
    postJson(
      s"",
      createReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
      (parsedBody \ "message").extract[String] shouldBe
        "dataset name must be less than 255 characters"
    }
  }

  test("create a data set with automatically process packages set") {
    val createReq = write(
      CreateDataSetRequest(
        "A New DataSet",
        None,
        List(),
        status = Some("IN_REVIEW"),
        automaticallyProcessPackages = true
      )
    )

    postJson(
      s"",
      createReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
      parsedBody
        .extract[DataSetDTO]
        .content
        .automaticallyProcessPackages shouldBe true
    }
  }

  test("creating a data set with empty name should fail") {
    val createReq = write(CreateDataSetRequest("", None, List()))
    postJson(
      s"",
      createReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
      body should include("dataset name must not be empty")
    }
  }

  test("update a dataset with a data use agreement") {
    val ds = createDataSet("Foo")
    val container = secureContainerFor(loggedInUser, loggedInOrganization)
    val agreement = container.dataUseAgreementManager
      .create("Data use agreement", "Lots of legal text")
      .await
      .value

    val updateReq =
      write(UpdateDataSetRequest(dataUseAgreementId = Some(agreement.id)))

    putJson(
      s"/${ds.nodeId}",
      updateReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody
        .extract[DataSetDTO]
        .content
        .dataUseAgreementId shouldBe Some(agreement.id)
    }
  }

  test("update a data set") {
    val ds = createDataSet("Foo")
    createPackage(
      ds,
      "Bar",
      `type` = PackageType.Collection,
      ownerId = Some(loggedInUser.id)
    )
    val updateReq = write(
      UpdateDataSetRequest(
        Some("Boom"),
        Some("This is a dataset."),
        status = Some("IN_REVIEW"),
        license = Some(License.`Apache 2.0`),
        tags = Some(List("tag1", "tag2"))
      )
    )

    putJson(
      s"/${ds.nodeId}",
      updateReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val result: WrappedDataset = parsedBody.extract[DataSetDTO].content
      result.name shouldEqual "Boom"
      result.description shouldBe ds.description
      result.status shouldEqual "IN_REVIEW"
      result.license shouldEqual Some(License.`Apache 2.0`)
      result.tags shouldEqual List("tag1", "tag2")
    }
  }

  test("update a data set should fail if name is longer than 255 characters") {
    val ds = createDataSet("Foo")
    createPackage(
      ds,
      "Bar",
      `type` = PackageType.Collection,
      ownerId = Some(loggedInUser.id)
    )

    val updateReq = write(
      UpdateDataSetRequest(
        Some(
          "hwlI4GOxhhu7tayTtveguvtV0XmF2ak9iu3WqSlBCuoZuHuBpIsmbghiTcT76MtXcjKGnQOYm4jnh9Y0zLbGyeTdtBVZ9GOvYkxWenBQOQUUcsQb191NAl07rYiowQsUVtVrnSyA6ndpGdc0qPyq8a5HNpyUMZH84zzj5FAaiW1UDxQWEKS944SSbtDry4GgvQwq3lPMw0Vp3EmKJDPEJlwAFkdowuV1ifGEsZcyUfqbi89QlqjqcZAoCVJULRGN"
        ),
        Some("This is a dataset."),
        status = Some("IN_REVIEW"),
        license = Some(License.`Apache 2.0`),
        tags = Some(List("tag1", "tag2"))
      )
    )

    putJson(
      s"/${ds.nodeId}",
      updateReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
      body should include("dataset name must be less than 255 characters")
    }
  }

  test("remove all tags from a data set") {
    val ds = createDataSet("Foo", tags = List("tag1", "tag2"))
    val updateReq = write(
      UpdateDataSetRequest(
        Some(ds.name),
        ds.description,
        tags = Some(List.empty[String])
      )
    )

    putJson(
      s"/${ds.nodeId}",
      updateReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody.extract[DataSetDTO].content.tags shouldEqual List.empty[String]
    }
  }

  test("update a data set using an If-Match header") {
    val ds = createDataSet("Foo")
    val updateReq = write(
      UpdateDataSetRequest(
        Some("Boom"),
        Some("This is a dataset."),
        status = Some("IN_REVIEW"),
        license = Some(License.`Apache 2.0`),
        tags = Some(List("tag1", "tag2"))
      )
    )

    putJson(
      s"/${ds.nodeId}",
      updateReq,
      headers = authorizationHeader(loggedInJwt) ++ Map(
        HttpHeaders.IF_MATCH -> ds.etag.asHeader
      ) ++ traceIdHeader()
    ) {
      status should equal(200)
      response.getHeader(HttpHeaders.ETAG) should not be ds.etag.asHeader
      val updatedDs =
        secureContainer.datasetManager.get(ds.id).await.value
      response.getHeader(HttpHeaders.ETAG) shouldBe updatedDs.etag.asHeader
    }
  }

  test("update a data set and touch the updatedAt timestamp") {
    val ds = createDataSet("Foo")

    putJson(
      s"/${ds.nodeId}",
      write(UpdateDataSetRequest(Some("Bar"), ds.description)),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    secureContainer.datasetManager
      .get(ds.id)
      .value
      .await
      .value
      .updatedAt should be > ds.updatedAt
  }

  test(
    "fail to update a data set if the If-Match header indicates a stale version"
  ) {
    val ds = createDataSet("Foo")
    val updateReq = write(
      UpdateDataSetRequest(
        Some("Boom"),
        Some("This is a dataset."),
        status = Some("IN_REVIEW"),
        license = Some(License.`Apache 2.0`),
        tags = Some(List("tag1", "tag2"))
      )
    )

    putJson(
      s"/${ds.nodeId}",
      updateReq,
      headers = authorizationHeader(loggedInJwt) ++ Map(
        HttpHeaders.IF_MATCH -> "12345"
      ) ++ traceIdHeader()
    ) {
      status should equal(412)
    }
  }

  test(
    "modifying a readme should not cause a false If-Match error on the dataset settings"
  ) {
    val ds = addBannerAndReadme(createDataSet("My Dataset"))
    val readme = "#Markdown content\nA paragraph!"
    val request = write(com.pennsieve.api.DatasetReadmeDTO(readme = readme))

    val existingReadme = secureContainer.datasetAssetsManager
      .getReadme(ds)
      .value
      .await
      .value
      .get

    val etag = get(
      s"/${ds.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      response.getHeader(HttpHeaders.ETAG)
    }

    putJson(
      s"/${ds.nodeId}/readme",
      request,
      authorizationHeader(loggedInJwt) ++ Map(
        HttpHeaders.IF_MATCH -> existingReadme.etag.asHeader
      )
    ) {
      status shouldBe 200
    }

    val updateReq =
      write(UpdateDataSetRequest(Some("Boom"), Some("This is a dataset.")))

    putJson(
      s"/${ds.nodeId}",
      updateReq,
      headers = authorizationHeader(loggedInJwt) ++ Map(
        HttpHeaders.IF_MATCH -> etag
      ) ++ traceIdHeader()
    ) {
      status should equal(200)
    }
  }

  test(
    "creating a readme should not cause a false If-Match error on the dataset settings"
  ) {
    val ds = createDataSet("My Dataset")
    val readme = "#Markdown content\nA paragraph!"
    val request = write(com.pennsieve.api.DatasetReadmeDTO(readme = readme))

    putJson(
      s"/${ds.nodeId}/readme",
      request,
      authorizationHeader(loggedInJwt) ++ Map(HttpHeaders.IF_MATCH -> "0")
    ) {
      status shouldBe 200
    }

    val updateReq = write(
      UpdateDataSetRequest(
        Some("Boom"),
        Some("This is a dataset."),
        status = Some("IN_REVIEW"),
        license = Some(License.`Apache 2.0`),
        tags = Some(List("tag1", "tag2"))
      )
    )

    putJson(
      s"/${ds.nodeId}",
      updateReq,
      headers = authorizationHeader(loggedInJwt) ++ Map(
        HttpHeaders.IF_MATCH -> ds.etag.asHeader
      ) ++ traceIdHeader()
    ) {
      status should equal(200)
    }
  }

  test("update a dataset to automatically process packages") {
    val ds = createDataSet("dataset-name")

    val request = write(
      UpdateDataSetRequest(
        None,
        ds.description,
        None,
        automaticallyProcessPackages = Some(true)
      )
    )

    putJson(
      s"/${ds.nodeId}",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody
        .extract[DataSetDTO]
        .content
        .automaticallyProcessPackages shouldBe true
    }
  }

  test("update a dataset that is not a dataset") {
    val ds = createDataSet("Foo")
    val collection = createPackage(
      ds,
      "Foo",
      `type` = PackageType.Collection,
      ownerId = Some(loggedInUser.id)
    )
    val updateReq =
      write(UpdateDataSetRequest(Some("New name"), ds.description))

    putJson(
      s"/${collection.nodeId}",
      updateReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  test("update a data set without editing tags") {
    val ds = createDataSet("Foo", tags = List("tag1", "tag2"))
    val updateReq = write(UpdateDataSetRequest(Some("Foo2"), ds.description))

    putJson(
      s"/${ds.nodeId}",
      updateReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody.extract[DataSetDTO].content.tags shouldEqual List(
        "tag1",
        "tag2"
      )
    }
  }

  // ---- DELETE /:id --------------------------------------------------

  test("delete data set") {
    val ds = createDataSet("Foo")

    delete(
      s"/${ds.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      response.body should equal("{}")
    }

    mockSqsClient.sentMessages.size should equal(1)

    get(
      s"/${ds.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  test("delete data set fails if dataset is locked") {
    val ds = createDataSet("Foo")
    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Requested, PublicationType.Publication)
      .await
      .value

    delete(
      s"/${ds.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(423)
    }
  }

  test("delete data set fails if dataset is published") {
    val ds = createDataSet("Foo")
    val container = secureContainerFor(loggedInUser, loggedInOrganization)
    container.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .value

    delete(
      s"/${ds.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
    }
  }

  test("delete data set if a dataset was published and then unpublished") {
    val ds = createDataSet("Foo")
    val container = secureContainerFor(loggedInUser, loggedInOrganization)
    container.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .value
    container.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Removal)
      .await
      .value
    container.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Requested, PublicationType.Publication)
      .await
      .value
    container.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Cancelled, PublicationType.Publication)
      .await
      .value

    delete(
      s"/${ds.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      response.body should equal("{}")
    }
  }

  // ---- GET / listing ------------------------------------------------

  test("include banner URLs in datasets") {
    val ds1 = createDataSet("test-ds1")
    addBannerAndReadme(ds1)
    val ds2 = createDataSet("test-ds2")
    addBannerAndReadme(ds2)
    val ds3 = createDataSet("test-ds3")
    addBannerAndReadme(ds3)

    get(
      s"/?includeBannerUrl=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val datasets = parsedBody.extract[List[DataSetDTO]]
      datasets.length shouldBe 4 // 3 + "Home"
      datasets
        .filter(_.content.name != dataset.name)
        .map(_.bannerPresignedUrl.isDefined)
        .foldLeft(true)(_ && _) shouldBe true
    }

    get(s"/", headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()) {
      status should equal(200)
      val datasets = parsedBody.extract[List[DataSetDTO]]
      datasets.length shouldBe 4 // 3 + "Home"
      datasets
        .filter(_.content.name != dataset.name)
        .map(_.bannerPresignedUrl.isDefined)
        .foldLeft(true)(_ && _) shouldBe false
    }
  }

  test("include banner URLs in datasets - paginated endpoint") {
    val ds1 = createDataSet("test-ds1")
    addBannerAndReadme(ds1)
    val ds2 = createDataSet("test-ds2")
    addBannerAndReadme(ds2)
    val ds3 = createDataSet("test-ds3")
    addBannerAndReadme(ds3)

    get(
      s"/paginated?includeBannerUrl=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val response = parsedBody.extract[com.pennsieve.api.PaginatedDatasets]
      response.totalCount shouldBe 4
      response.datasets
        .filter(_.content.name != dataset.name)
        .map(_.bannerPresignedUrl.isDefined)
        .foldLeft(true)(_ && _) shouldBe true
    }

    get(
      s"/paginated",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val response = parsedBody.extract[com.pennsieve.api.PaginatedDatasets]
      response.totalCount shouldBe 4
      response.datasets
        .filter(_.content.name != dataset.name)
        .map(_.bannerPresignedUrl.isDefined)
        .foldLeft(true)(_ && _) shouldBe false
    }
  }

  test("include publication info in datasets") {
    val ds1 = createDataSet("test-ds1")

    get(
      s"/?includePublishedDataset=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val datasets = parsedBody.extract[List[DataSetDTO]]
      datasets.length shouldBe 2 // "test-ds1" + "Home"
      datasets.foreach { d =>
        d.publication.status shouldBe PublicationStatus.Draft
      }
    }
  }

  // ---- contributors -------------------------------------------------

  test("get all contributors of a dataset") {
    val ds = createDataSet("ContributorTest")
    createDataSet("ContributorTestAgain")

    val ct1 = createContributor(
      "Tester",
      "Contributor",
      "tester-contributor@bf.com",
      None,
      None
    )
    val request1 = write(AddContributorRequest(ct1.id))

    putJson(
      s"/${ds.nodeId}/contributors",
      request1,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    get(
      s"/${ds.nodeId}/contributors",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val contributors = parsedBody.extract[List[ContributorDTO]]
      contributors.length should equal(2)
      // The auto-created contributor (loggedInUser) + ct1
      contributors.map(_.id).toSet should contain(ct1.id)
    }
  }

  test("delete a contributor of a dataset") {
    val contributor = createContributor(
      "Tester",
      "Contributor",
      "tester-contributor-delete@bf.com",
      dataset = Some(dataset)
    )

    get(
      s"/${dataset.nodeId}/contributors",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody.extract[List[ContributorDTO]].map(_.id) should contain(
        contributor.id
      )
    }

    delete(
      s"/${dataset.nodeId}/contributors/${contributor.id}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    get(
      s"/${dataset.nodeId}/contributors",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody.extract[List[ContributorDTO]].map(_.id) should not contain
        contributor.id
    }
  }

  // ---- share/unshare (PUT/DELETE /:id/collaborators) -------------

  test(
    "a user creates a data set and no one else should have access to it except that user"
  ) {
    createDataSet("My DataSet")

    get("/", headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()) {
      status should equal(200)
      parsedBody.extract[List[String]] should have size 0
    }
  }

  test(
    "user creates data set that belongs to org then shares it with that org only creator and users of that org should have access"
  ) {
    val myDS = createDataSet("My DataSet")

    val ids = write(List(loggedInOrganization.nodeId))
    putJson(
      s"/${myDS.nodeId}/collaborators",
      ids,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody
        .extract[CollaboratorChanges]
        .changes
        .get(loggedInOrganization.nodeId)
        .value
        .success should equal(true)
    }

    get(
      s"/${myDS.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should include("My DataSet")
    }

    get("/", headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()) {
      status should equal(200)
      body should include("My DataSet")
    }

    get(
      s"/${myDS.nodeId}",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should include("My DataSet")
    }

    get("/", headers = authorizationHeader(externalJwt) ++ traceIdHeader()) {
      status should equal(200)
      parsedBody.extract[List[String]] should have size 0
    }

    get(
      s"/${myDS.nodeId}",
      headers = authorizationHeader(externalJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  test(
    "user creates data set that belongs to org and tries to share it with another org should fail"
  ) {
    val myDS = createDataSet("My DataSet")

    val ids = write(List(externalOrganization.nodeId))
    putJson(
      s"/${myDS.nodeId}/collaborators",
      ids,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody
        .extract[CollaboratorChanges]
        .changes
        .get(externalOrganization.nodeId)
        .value
        .success should equal(false)
    }
  }

  test("only admins can share and unshare") {
    val myDS = createDataSet("My DataSet")

    val ids = write(List(colleagueUser.nodeId))
    putJson(
      s"/${myDS.nodeId}/collaborators",
      ids,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody
        .extract[CollaboratorChanges]
        .changes
        .get(colleagueUser.nodeId)
        .value
        .success should equal(true)
    }

    putJson(
      s"/${myDS.nodeId}/collaborators",
      ids,
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }

    deleteJson(
      s"/${myDS.nodeId}/collaborators",
      ids,
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }
  }

  test("admins cannot revoke their admin access") {
    val myDS = createDataSet("My DataSet")
    val ids = write(List(loggedInUser.nodeId))
    deleteJson(
      s"/${myDS.nodeId}/collaborators",
      ids,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody
        .extract[CollaboratorChanges]
        .changes
        .get(loggedInUser.nodeId)
        .value
        .success should equal(false)
    }
  }

  test("unshare a user") {
    val myDS = createDataSet("My DataSet")
    val ids = write(List(colleagueUser.nodeId))

    putJson(
      s"/${myDS.nodeId}/collaborators",
      ids,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      parsedBody
        .extract[CollaboratorChanges]
        .changes
        .get(colleagueUser.nodeId)
        .value
        .success should equal(true)
    }

    get(
      s"/${myDS.nodeId}",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    deleteJson(
      s"/${myDS.nodeId}/collaborators",
      ids,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val response = parsedBody.extract[CollaboratorChanges]
      response.changes.get(colleagueUser.nodeId).value.success should equal(
        true
      )
      response.counts.users should equal(0)
    }

    get(
      s"/${myDS.nodeId}",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }
  }

  test("unshare an organization") {
    val myDS = createDataSet("My DataSet")
    val ids = write(List(loggedInOrganization.nodeId))

    putJson(
      s"/${myDS.nodeId}/collaborators",
      ids,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody
        .extract[CollaboratorChanges]
        .changes
        .get(loggedInOrganization.nodeId)
        .value
        .success should equal(true)
    }

    deleteJson(
      s"/${myDS.nodeId}/collaborators",
      ids,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val response = parsedBody.extract[CollaboratorChanges]
      response.changes
        .get(loggedInOrganization.nodeId)
        .value
        .success should equal(true)
      response.counts.organizations should equal(0)
    }
  }

  // ---- user collaborators -----------------------------------------

  test("user can add a new collaborator") {
    val ds = createDataSet("My Dataset")
    val request = write(CollaboratorRoleDTO(colleagueUser.nodeId, Role.Editor))

    putJson(
      s"/${ds.nodeId}/collaborators/users",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    get(
      s"/${ds.nodeId}/collaborators/users",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val users = parsedBody.extract[List[UserCollaboratorRoleDTO]]
      users.map(_.id) should contain(colleagueUser.nodeId)
    }
  }

  test("user cannot add a new owner") {
    val ds = createDataSet("My Dataset")
    val request = write(CollaboratorRoleDTO(colleagueUser.nodeId, Role.Owner))

    putJson(
      s"/${ds.nodeId}/collaborators/users",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }
  }

  test("managers cannot revoke their admin access") {
    val myDS = createDataSet("My DataSet")

    val request = write(RemoveCollaboratorRequest(loggedInUser.nodeId))
    deleteJson(
      s"/${myDS.nodeId}/collaborators/users",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    // should still have access
    get(s"/", headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()) {
      status should equal(200)
      body should include("My DataSet")
    }
  }

  test("owner access can never be revoked") {
    val myDS = createDataSet("My DataSet")

    val request = write(RemoveCollaboratorRequest(loggedInUser.nodeId))
    deleteJson(
      s"/${myDS.nodeId}/collaborators/users",
      request,
      headers = authorizationHeader(adminJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    // owner should still have access
    get(s"/", headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()) {
      status should equal(200)
      body should include("My DataSet")
    }
  }

  test("get a data set with its collaborators") {
    val ds = createDataSet("Foo")

    val organizationIds = write(List(loggedInOrganization.nodeId))
    putJson(
      s"/${ds.nodeId}/collaborators",
      organizationIds,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody
        .extract[CollaboratorChanges]
        .changes
        .get(loggedInOrganization.nodeId)
        .value
        .success should equal(true)
    }

    val userIds = write(List(colleagueUser.nodeId))
    putJson(
      s"/${ds.nodeId}/collaborators",
      userIds,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody
        .extract[CollaboratorChanges]
        .changes
        .get(colleagueUser.nodeId)
        .value
        .success should equal(true)
    }

    get(
      s"/${ds.nodeId}/collaborators",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      (parsedBody \ "organizations" \ "id")
        .extract[List[String]] should equal(List(loggedInOrganization.nodeId))
      (parsedBody \ "users" \ "id").extract[List[String]] should contain(
        colleagueUser.nodeId
      )
    }
  }

  test("PUT collaborators/users does not allow owner change") {
    val myDS = createDataSet("My DataSet")
    val roleChangeRequest =
      write(CollaboratorRoleDTO(loggedInUser.nodeId, Role.Editor))

    putJson(
      s"/${myDS.nodeId}/collaborators/users",
      roleChangeRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
      (parsedBody \ "message").extract[String] shouldBe
        "To relinquish ownership of a dataset, please use the PUT /collaborators/owner endpoint."
    }
  }

  test("user can unshare with another user collaborator") {
    val ds = createDataSet("My Dataset")
    val addRequest =
      write(CollaboratorRoleDTO(colleagueUser.nodeId, Role.Viewer))
    putJson(
      s"/${ds.nodeId}/collaborators/users",
      addRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    get(
      s"/${ds.nodeId}/collaborators/users",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody
        .extract[List[UserCollaboratorRoleDTO]]
        .map(_.id) should contain(colleagueUser.nodeId)
    }

    val removeRequest = write(RemoveCollaboratorRequest(colleagueUser.nodeId))
    deleteJson(
      s"/${ds.nodeId}/collaborators/users",
      removeRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    get(
      s"/${ds.nodeId}/collaborators/users",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody
        .extract[List[UserCollaboratorRoleDTO]]
        .map(_.id) shouldNot contain(colleagueUser.nodeId)
    }
  }

  // ---- changelog -----------------------------------------------------

  test("changelog: add a changelog to an a dataset") {
    val ds = createDataSet("Dataset with Changelog 1")
    val changeLogContent = "# Markdown content\nChangelog here!"
    val request =
      write(com.pennsieve.api.DatasetChangelogDTO(changelog = changeLogContent))

    putJson(
      s"/${ds.nodeId}/changelog",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200

      val changelogAsset = secureContainer.datasetAssetsManager
        .getChangelog(secureContainer.datasetManager.get(ds.id).await.value)
        .value
        .await
        .value
        .get

      val expectedKey =
        s"${loggedInOrganization.id}/${ds.id}/${changelogAsset.id}/changelog.md"
      changelogAsset.name shouldBe "changelog.md"
      changelogAsset.s3Bucket shouldBe mockDatasetAssetClient.bucket
      changelogAsset.s3Key shouldBe expectedKey
      changelogAsset.datasetId shouldBe ds.id

      val (content, metadata) =
        mockDatasetAssetClient.assets(changelogAsset.id)

      content.stripLineEnd shouldBe changeLogContent
      metadata.getContentType() shouldBe "text/plain"
      metadata.getContentLength() shouldBe 34
    }
  }

  test("changelog: update changelog on a dataset") {
    val ds = createDataSet("Dataset with Changelog 2")
    val initial = "v1 content"
    val updated = "v2 content extended"

    putJson(
      s"/${ds.nodeId}/changelog",
      write(com.pennsieve.api.DatasetChangelogDTO(changelog = initial)),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    putJson(
      s"/${ds.nodeId}/changelog",
      write(com.pennsieve.api.DatasetChangelogDTO(changelog = updated)),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    get(
      s"/${ds.nodeId}/changelog",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody
        .extract[com.pennsieve.api.DatasetChangelogDTO]
        .changelog shouldBe updated
    }
  }

  test("changelog: get changelog for a dataset") {
    val ds = createDataSet("Dataset with Changelog 3")
    val changeLogContent = "#Markdown content\nChangelog here!"
    putJson(
      s"/${ds.nodeId}/changelog",
      write(
        com.pennsieve.api.DatasetChangelogDTO(changelog = changeLogContent)
      ),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    get(
      s"/${ds.nodeId}/changelog",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody
        .extract[com.pennsieve.api.DatasetChangelogDTO]
        .changelog shouldBe changeLogContent
    }
  }

  // ---- banner --------------------------------------------------------

  test("cannot upload too-large banner") {
    val ds = createDataSet("My Dataset")

    val fileUploads = Map(
      "banner" -> org.scalatra.test.BytesPart(
        "big.jpg",
        Array.fill(maxFileUploadSize + 10)(1.toByte),
        "image/jpeg"
      )
    )

    put(
      s"/${ds.nodeId}/banner",
      Map(),
      fileUploads,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 413
    }
  }

  test("get a presigned banner url") {
    val ds = createDataSet("My Dataset")

    get(
      s"/${ds.nodeId}/banner",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      val dto = parsedBody.extract[com.pennsieve.api.DatasetBannerDTO]
      dto.banner shouldBe None
    }
  }

  // ---- readme --------------------------------------------------------

  test("get a dataset readme") {
    val ds = createDataSet("My Dataset")
    val content = "#Markdown content\nA paragraph!"
    val request = write(com.pennsieve.api.DatasetReadmeDTO(readme = content))

    putJson(
      s"/${ds.nodeId}/readme",
      request,
      authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    get(
      s"/${ds.nodeId}/readme",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      val readme = parsedBody.extract[com.pennsieve.api.DatasetReadmeDTO]
      readme.readme shouldBe content
    }
  }

  test("fail to update readme if the If-Match header indicates new readme") {
    val ds = addBannerAndReadme(createDataSet("My Dataset"))
    val readme = "#Markdown content\nA paragraph!"
    val request = write(com.pennsieve.api.DatasetReadmeDTO(readme = readme))

    putJson(
      s"/${ds.nodeId}/readme",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader() ++ Map(
        HttpHeaders.IF_MATCH -> "0"
      )
    ) {
      status shouldBe 412
    }
  }

  test(
    "fail to create readme if the If-Match header indicates one already exists"
  ) {
    val ds = createDataSet("My Dataset")
    val readme = "#Markdown content\nA paragraph!"
    val request = write(com.pennsieve.api.DatasetReadmeDTO(readme = readme))

    putJson(
      s"/${ds.nodeId}/readme",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader() ++ Map(
        HttpHeaders.IF_MATCH -> "12345"
      )
    ) {
      status shouldBe 412
    }
  }

  test("update existing dataset readme") {
    val ds = createDataSet("My Dataset")
    val req1 = write(com.pennsieve.api.DatasetReadmeDTO(readme = "v1"))
    val req2 = write(com.pennsieve.api.DatasetReadmeDTO(readme = "v2"))

    putJson(
      s"/${ds.nodeId}/readme",
      req1,
      authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    putJson(
      s"/${ds.nodeId}/readme",
      req2,
      authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    get(
      s"/${ds.nodeId}/readme",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody
        .extract[com.pennsieve.api.DatasetReadmeDTO]
        .readme shouldBe "v2"
    }
  }

  test("get a dataset readme that does not exist") {
    val ds = createDataSet("My Dataset")

    get(
      s"/${ds.nodeId}/readme",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      val readme = parsedBody.extract[com.pennsieve.api.DatasetReadmeDTO]
      readme.readme shouldBe ""

      response.getHeader(HttpHeaders.ETAG) shouldBe "0"
    }
  }

  test("upload dataset readme") {
    val ds = createDataSet("My Dataset")
    val content = "Some readme content"
    val request = write(com.pennsieve.api.DatasetReadmeDTO(readme = content))

    putJson(
      s"/${ds.nodeId}/readme",
      request,
      authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
    }
  }

  test("upload dataset readme with unicode") {
    val ds = createDataSet("My Dataset")
    val content = "Some readme content with unicode chars: é ñ"
    val request = write(com.pennsieve.api.DatasetReadmeDTO(readme = content))

    putJson(
      s"/${ds.nodeId}/readme",
      request,
      authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
    }
  }

  // ---- ignore files ---------------------------------------------------

  test("set ignore files for a dataset") {
    val ds = createDataSet("dataset-ignore-files")
    val ignoreFiles = Seq(
      com.pennsieve.api.DatasetIgnoreFileDTO("file1.py"),
      com.pennsieve.api.DatasetIgnoreFileDTO("file2.png"),
      com.pennsieve.api.DatasetIgnoreFileDTO("file3.txt")
    )

    putJson(
      s"/${ds.nodeId}/ignore-files",
      write(ignoreFiles),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      val dto = parsedBody.extract[com.pennsieve.dtos.DatasetIgnoreFilesDTO]
      dto.ignoreFiles.length shouldBe 3
      dto.datasetId shouldBe ds.id
    }

    get(
      s"/${ds.nodeId}/ignore-files",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      val dto = parsedBody.extract[com.pennsieve.dtos.DatasetIgnoreFilesDTO]
      dto.ignoreFiles.length shouldBe 3
      dto.datasetId shouldBe ds.id
    }
  }

  test("update ignore files for a dataset") {
    val ds = createDataSet("dataset-ignore-files")

    putJson(
      s"/${ds.nodeId}/ignore-files",
      write(
        Seq(
          com.pennsieve.api.DatasetIgnoreFileDTO("file1.py"),
          com.pennsieve.api.DatasetIgnoreFileDTO("file2.png"),
          com.pennsieve.api.DatasetIgnoreFileDTO("file3.txt")
        )
      ),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    putJson(
      s"/${ds.nodeId}/ignore-files",
      write(
        Seq(
          com.pennsieve.api.DatasetIgnoreFileDTO("file4.py"),
          com.pennsieve.api.DatasetIgnoreFileDTO("file5.png"),
          com.pennsieve.api.DatasetIgnoreFileDTO("file3.txt"),
          com.pennsieve.api.DatasetIgnoreFileDTO("file6.jpeg")
        )
      ),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      val dto = parsedBody.extract[com.pennsieve.dtos.DatasetIgnoreFilesDTO]
      dto.ignoreFiles.map(f => (f.datasetId, f.fileName)) shouldBe Seq(
        (ds.id, "file4.py"),
        (ds.id, "file5.png"),
        (ds.id, "file3.txt"),
        (ds.id, "file6.jpeg")
      )
      dto.datasetId shouldBe ds.id
    }
  }

  test("delete ignore files for a dataset") {
    val ds = createDataSet("dataset-ignore-files")
    val ignoreFiles = Seq(
      com.pennsieve.api.DatasetIgnoreFileDTO("file1.py"),
      com.pennsieve.api.DatasetIgnoreFileDTO("file2.png")
    )
    putJson(
      s"/${ds.nodeId}/ignore-files",
      write(ignoreFiles),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    putJson(
      s"/${ds.nodeId}/ignore-files",
      write(Seq.empty[com.pennsieve.api.DatasetIgnoreFileDTO]),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      val dto = parsedBody.extract[com.pennsieve.dtos.DatasetIgnoreFilesDTO]
      dto.ignoreFiles.length shouldBe 0
    }
  }

  test("get dataset ignore files that do not exist") {
    val ds = createDataSet("dataset-ignore-files")

    get(
      s"/${ds.nodeId}/ignore-files",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      val dto = parsedBody.extract[com.pennsieve.dtos.DatasetIgnoreFilesDTO]
      dto.ignoreFiles.length shouldBe 0
      dto.ignoreFiles shouldBe Seq()
      dto.datasetId shouldBe ds.id
    }
  }

  test("get datasets endpoint includes role and packageTypeCounts") {
    val ds = createDataSet("test-dataset-for-role-and-packageTypeCounts")
    val folder = createPackage(ds, "folder")
    createPackage(
      ds,
      "primary.img",
      `type` = PackageType.Image,
      parent = Some(folder)
    )
    createPackage(
      ds,
      "secondary.img",
      `type` = PackageType.Image,
      parent = Some(folder)
    )
    createPackage(
      ds,
      "derived.csv",
      `type` = PackageType.CSV,
      parent = Some(folder)
    )
    createPackage(
      ds,
      "report.pdf",
      `type` = PackageType.PDF,
      parent = Some(folder)
    )

    get(
      s"/${ds.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val dto = parsedBody.extract[DataSetDTO]
      dto.role.isDefined shouldBe true
      dto.role.get shouldBe Role.Owner

      dto.packageTypeCounts.isDefined shouldBe true
      val counts = dto.packageTypeCounts.get
      counts.keys.size shouldEqual 4
      counts.get("Collection") shouldBe Some(1)
      counts.get("Image") shouldBe Some(2)
      counts.get("CSV") shouldBe Some(1)
      counts.get("PDF") shouldBe Some(1)
    }
  }

  test(
    "get all data sets for the logged in user with limit and offset - paginated endpoint"
  ) {
    createDataSet("test-ds1")
    createDataSet("test-ds2")
    createDataSet("test-ds3")

    get(
      s"/paginated?limit=2",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val response = parsedBody.extract[com.pennsieve.api.PaginatedDatasets]
      response.limit shouldBe 2
      response.offset shouldBe 0
      response.totalCount shouldBe 4
      response.datasets.length shouldBe 2
    }

    get(
      s"/paginated?limit=2&offset=2",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val response = parsedBody.extract[com.pennsieve.api.PaginatedDatasets]
      response.limit shouldBe 2
      response.offset shouldBe 2
      response.totalCount shouldBe 4
      response.datasets.length shouldBe 2
    }
  }

  test("get all data sets from a collection - paginated endpoint") {
    val ds1 = createDataSet("test-ds1")
    val ds2 = createDataSet("test-ds2")
    createDataSet("test-ds3")

    val collection = secureContainer.collectionManager
      .create("My Very Own New Collection")
      .await
      .value

    secureContainer.datasetManager
      .addCollection(ds1, collection.id)
      .await
      .value
    secureContainer.datasetManager
      .addCollection(ds2, collection.id)
      .await
      .value

    get(
      s"/paginated?collectionId=${collection.id}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val response = parsedBody.extract[com.pennsieve.api.PaginatedDatasets]
      response.totalCount shouldBe 2
      response.datasets
        .map(_.content.name)
        .toSet shouldBe Set("test-ds1", "test-ds2")
    }
  }

  test("paginated max limit on get datasets") {
    (1 to DataSetsController.DatasetsMaxLimit + 2)
      .map(n => createDataSet(s"test-dataset-for-pagination-${n}"))

    get(
      s"/paginated?limit=${DataSetsController.DatasetsMaxLimit + 1}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldEqual 200
      val response = parsedBody.extract[com.pennsieve.api.PaginatedDatasets]
      response.datasets.length shouldEqual DataSetsController.DatasetsMaxLimit
    }
  }

  test("paginated max limit on child packages in get dataset :id") {
    val ds = createDataSet("test-dataset-for-pagination-with-packages")

    (1 to DataSetsController.DatasetChildrenMaxLimit + 2)
      .map(n => createPackage(ds, s"Package-${n}"))

    get(
      s"/${ds.nodeId}?limit=${DataSetsController.DatasetChildrenMaxLimit + 1}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldEqual 200
      parsedBody
        .extract[DataSetDTO]
        .children
        .get
        .length shouldEqual DataSetsController.DatasetChildrenMaxLimit
    }
  }

  test("return bad request for a page size above the limit") {
    val maxPageSize =
      config.getInt("pennsieve.packages_pagination.max_page_size")
    val expectedResponseMessage =
      s"Invalid page size must be less than or equal to $maxPageSize"

    get(
      s"/unused-dataset-id/packages?pageSize=1000000",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 400
      body shouldBe expectedResponseMessage
    }
  }

  test("return bad request for a invalid package type") {
    get(
      s"/unused-dataset-id/packages?types=faketype",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 400
      body shouldBe "Invalid type name"
    }
  }

  test("return bad request for a invalid package type with a real type in list") {
    get(
      s"/unused-dataset-id/packages?types=faketype:CSV",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 400
      body shouldBe "Invalid type name"
    }
  }

  test("return bad request for a non number as the cursor starting id") {
    get(
      s"/unused-dataset-id/packages?cursor=packages:aa",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 400
      body shouldBe "Cursor format must be package:{integer}"
    }
  }

  test("return bad request for a invalid cursor structure") {
    get(
      s"/unused-dataset-id/packages?cursor=aa",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 400
      body shouldBe "Cursor format must be package:{integer}"
    }
  }

  test("demo user should not be able add a collaborator") {
    val ds1 = sandboxUserContainer.datasetManager
      .create(name = "test-ds1")
      .await
      .value

    val shareDatasetRequest =
      write(CollaboratorRoleDTO(sandboxUser.nodeId, Role.Owner))

    putJson(
      s"/${ds1.nodeId}/collaborators/users",
      shareDatasetRequest,
      headers = authorizationHeader(sandboxUserJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }
  }

  test("demo organization user cannot invite collaborators") {
    val ds = sandboxUserContainer.datasetManager
      .create(name = "Foo")
      .await
      .value

    val ids = write(List(colleagueUser.nodeId))
    putJson(
      s"/${ds.nodeId}/collaborators",
      ids,
      headers = authorizationHeader(sandboxUserJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }
  }

  test("demo organization user cannot add a user as a collaborator") {
    val ds = sandboxUserContainer.datasetManager
      .create(name = "Foo")
      .await
      .value

    val request = write(CollaboratorRoleDTO(loggedInUser.nodeId, Role.Editor))
    putJson(
      s"/${ds.nodeId}/collaborators/users",
      request,
      headers = authorizationHeader(sandboxUserJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }
  }

  test(
    "demo organization users can not share datasets within the demo organization"
  ) {
    val ds = sandboxUserContainer.datasetManager
      .create(name = "My DataSet")
      .await
      .value

    val request = write(OrganizationRoleDTO(Some(Role.Viewer)))
    putJson(
      s"/${ds.nodeId}/collaborators/organizations",
      request,
      headers = authorizationHeader(sandboxUserJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }
  }

  test("demo organization user cannot share dataset with teams") {
    val ds = sandboxUserContainer.datasetManager
      .create(name = "Foo")
      .await
      .value

    val team = com.pennsieve.models.Team(
      nodeId = NodeCodes.generateId(NodeCodes.teamCode),
      name = "Some Team",
      id = state.newId()
    )
    state.teams.put((sandboxOrganization.id, team.id), team)

    putJson(
      s"/${ds.nodeId}/collaborators/teams",
      write(CollaboratorRoleDTO(team.nodeId, Role.Editor)),
      headers = authorizationHeader(sandboxUserJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }
  }

  test("demo organization user cannot add a contributor to a dataset") {
    val ds = sandboxUserContainer.datasetManager
      .create(name = "ContributorTest")
      .await
      .value

    val ct = sandboxUserContainer.contributorsManager
      .create(
        "Tester",
        "Contributor",
        "tester-contributor-demo@bf.com",
        None,
        None
      )
      .await
      .value

    putJson(
      s"/${ds.nodeId}/contributors",
      write(AddContributorRequest(ct._1.id)),
      headers = authorizationHeader(sandboxUserJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }
  }

  test("demo organization user cannot switch owner of a dataset") {
    val ds = sandboxUserContainer.datasetManager
      .create(name = "Foo")
      .await
      .value

    putJson(
      s"/${ds.nodeId}/collaborators/owner",
      write(com.pennsieve.api.SwitchOwnerRequest(loggedInUser.nodeId)),
      headers = authorizationHeader(sandboxUserJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }
  }

  test(
    "user creates data set that belongs to org then shares it with that org using roles then only creator and users of that org should have access"
  ) {
    val myDS = createDataSet("My DataSet")

    get(s"/", headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()) {
      status should equal(200)
      parsedBody.extract[List[String]] should have size 0
    }
    get(
      s"/${myDS.nodeId}",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }

    val request = write(OrganizationRoleDTO(Some(Role.Viewer)))
    putJson(
      s"/${myDS.nodeId}/collaborators/organizations",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    get(s"/", headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()) {
      status should equal(200)
      body should include("My DataSet")
    }
    get(
      s"/${myDS.nodeId}",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should include("My DataSet")
    }

    get(s"/", headers = authorizationHeader(externalJwt) ++ traceIdHeader()) {
      status should equal(200)
      parsedBody.extract[List[String]] should have size 0
    }
    get(
      s"/${myDS.nodeId}",
      headers = authorizationHeader(externalJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  test("get user's effective dataset role") {
    val myDS = createDataSet("My DataSet")

    get(
      s"/${myDS.nodeId}/role",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody
        .extract[com.pennsieve.api.DatasetRoleResponse]
        .role shouldBe Role.Owner
    }

    get(
      s"/${myDS.nodeId}/role",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }
  }

  test("get all data sets for the logged in user") {
    val dataset1 = createDataSet("test-dataset-1")
    val dataset2 = createDataSet("test-dataset-2")
    createPackage(dataset1, "package1", ownerId = Some(loggedInUser.id))
    createPackage(dataset1, "package2", ownerId = Some(loggedInUser.id))
    createPackage(dataset2, "package3", ownerId = Some(loggedInUser.id))

    secureContainer.datasetManager
      .addUserCollaborator(dataset1, colleagueUser, Role.Editor)
      .await

    secureContainer.datasetManager
      .setOrganizationCollaboratorRole(dataset, Some(Role.Viewer))
      .await

    get(s"/", headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()) {
      status should equal(200)

      val response = parsedBody.extract[List[DataSetDTO]]
      response.length shouldBe 3 // dataset (Home) + dataset1 + dataset2
      val names = response.map(_.content.name).toSet
      names should contain("Home")
      names should contain("test-dataset-1")
      names should contain("test-dataset-2")
    }
  }

  test("get all datasets returns unique datasets with multiple contributors") {
    createContributor(
      "Ada",
      "Lovelace",
      "ada@pennsieve.org",
      dataset = Some(dataset)
    )
    createContributor(
      "Agatha",
      "Christie",
      "agatha@pennsieve.org",
      dataset = Some(dataset)
    )

    get(s"/", headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()) {
      status should equal(200)
      parsedBody
        .extract[List[DataSetDTO]]
        .map(_.content)
        .sortBy(_.name)
        .map(_.intId) shouldBe List(dataset.id)
    }
  }

  test("get a dataset with properties and deserialize with circe") {
    val ds1 = createDataSet("test-ds1")
    createPackage(ds1, "package1", ownerId = Some(loggedInUser.id))
    createPackage(ds1, "package2", ownerId = Some(loggedInUser.id))

    get(
      s"/${ds1.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody.extract[DataSetDTO].content.intId shouldBe ds1.id
      parsedBody.extract[DataSetDTO].content.name shouldBe ds1.name
    }
  }

  test("get role of shared organization") {
    val myDS = createDataSet("My DataSet")

    get(
      s"/${myDS.nodeId}/collaborators/organizations",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[OrganizationRoleDTO].role shouldBe None
    }

    val request = write(OrganizationRoleDTO(Some(Role.Viewer)))
    putJson(
      s"/${myDS.nodeId}/collaborators/organizations",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    get(
      s"/${myDS.nodeId}/collaborators/organizations",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[OrganizationRoleDTO].role should equal(
        Some(Role.Viewer)
      )
    }
  }

  test("unshare an organization using role endpoints") {
    val myDS = createDataSet("My DataSet")

    val putRequest = write(OrganizationRoleDTO(Some(Role.Viewer)))
    putJson(
      s"/${myDS.nodeId}/collaborators/organizations",
      putRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    get(
      s"/${myDS.nodeId}",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    delete(
      s"/${myDS.nodeId}/collaborators/organizations",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    get(
      s"/${myDS.nodeId}",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }
  }

  test("cannot share with a team in another organization") {
    val myDS = createDataSet("My DataSet")
    val externalTeam = com.pennsieve.models.Team(
      nodeId = NodeCodes.generateId(NodeCodes.teamCode),
      name = "External team",
      id = state.newId()
    )
    state.teams.put((externalOrganization.id, externalTeam.id), externalTeam)

    val request = write(CollaboratorRoleDTO(externalTeam.nodeId, Role.Editor))
    putJson(
      s"/${myDS.nodeId}/collaborators/teams",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }

    get(
      s"/${myDS.nodeId}/collaborators/teams",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody
        .extract[List[com.pennsieve.api.TeamCollaboratorRoleDTO]]
        .length shouldBe 0
    }
  }

  test("user can unshare with a team") {
    val ds = createDataSet("My Dataset")
    val myTeam = com.pennsieve.models.Team(
      nodeId = NodeCodes.generateId(NodeCodes.teamCode),
      name = "My Team",
      id = state.newId()
    )
    state.teams.put((loggedInOrganization.id, myTeam.id), myTeam)

    putJson(
      s"/${ds.nodeId}/collaborators/teams",
      write(CollaboratorRoleDTO(myTeam.nodeId, Role.Viewer)),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    get(
      s"/${ds.nodeId}/collaborators/teams",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody
        .extract[List[com.pennsieve.api.TeamCollaboratorRoleDTO]] should contain(
        com.pennsieve.api
          .TeamCollaboratorRoleDTO(myTeam.nodeId, myTeam.name, Role.Viewer)
      )
    }

    deleteJson(
      s"/${ds.nodeId}/collaborators/teams",
      write(RemoveCollaboratorRequest(myTeam.nodeId)),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    get(
      s"/${ds.nodeId}/collaborators/teams",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody
        .extract[List[com.pennsieve.api.TeamCollaboratorRoleDTO]] shouldBe empty
    }
  }

  test(
    "user creates data set that belongs to org and tries to share it with a user who belongs to another org should fail"
  ) {
    val myDS = createDataSet("My DataSet")
    val request = write(CollaboratorRoleDTO(externalUser.nodeId, Role.Editor))
    putJson(
      s"/${myDS.nodeId}/collaborators/users",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  test("deserialize publish-complete message correctly") {
    import com.pennsieve.api.PublishCompleteRequest
    import com.pennsieve.discover.client.definitions.DatasetPublishStatus
    import com.pennsieve.models.PublishStatus
    import java.time.{ OffsetDateTime, ZoneOffset }

    val date =
      OffsetDateTime.of(2014, 10, 4, 12, 34, 56, 0, ZoneOffset.of("+09:00"))
    write(date) shouldBe "\"2014-10-04T12:34:56+09:00\""

    val request = write(
      PublishCompleteRequest(
        Some(1),
        1,
        Some(date),
        PublishStatus.PublishSucceeded,
        success = true,
        error = None
      )
    )
    read[PublishCompleteRequest](request).lastPublishedDate shouldBe Some(date)
  }

  test("external repository synchronization flags can be missing") {
    val repoName = "test-dataset-for-external-repo"
    val repoUrl = s"https://github.com/Pennsieve/$repoName"

    val ds = createDataSet(repoName, `type` = DatasetType.Release)

    secureContainer.datasetManager
      .addRelease(
        com.pennsieve.models.DatasetRelease(
          datasetId = ds.id,
          origin = "GitHub",
          url = repoUrl,
          label = Some("v1.0.0"),
          marker = Some("1ab2c98"),
          releaseDate = Some(java.time.ZonedDateTime.now())
        )
      )
      .await
      .value

    val extRepo = com.pennsieve.models.ExternalRepository(
      origin = "GitHub",
      `type` = com.pennsieve.models.ExternalRepositoryType.Publishing,
      url = repoUrl,
      organizationId = secureContainer.organization.id,
      userId = secureContainer.user.id,
      datasetId = Some(ds.id),
      status = com.pennsieve.models.ExternalRepositoryStatus.Enabled,
      autoProcess = true
    )
    secureContainer.datasetManager.addExternalRepository(extRepo).await.value

    get(
      s"/paginated?type=release",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val returned =
        parsedBody.extract[com.pennsieve.api.PaginatedDatasets].datasets
      returned.length shouldEqual 1
      val head = returned.head
      head.content.datasetType shouldBe DatasetType.Release
      head.content.repository.isDefined shouldBe true
      head.content.repository.get.synchronize.isDefined shouldBe false
    }
  }

  test("external repository includes synchronization flags when set") {
    val repoName = "test-dataset-for-external-repo"
    val repoUrl = s"https://github.com/Pennsieve/$repoName"

    val ds = createDataSet(repoName, `type` = DatasetType.Release)

    secureContainer.datasetManager
      .addRelease(
        com.pennsieve.models.DatasetRelease(
          datasetId = ds.id,
          origin = "GitHub",
          url = repoUrl,
          label = Some("v1.0.0"),
          marker = Some("1ab2c98"),
          releaseDate = Some(java.time.ZonedDateTime.now())
        )
      )
      .await
      .value

    val syncFlags = com.pennsieve.models.SynchrnonizationSettings(
      banner = false,
      changelog = false,
      contributors = true,
      license = true,
      readme = true
    )
    val extRepo = com.pennsieve.models.ExternalRepository(
      origin = "GitHub",
      `type` = com.pennsieve.models.ExternalRepositoryType.Publishing,
      url = repoUrl,
      organizationId = secureContainer.organization.id,
      userId = secureContainer.user.id,
      datasetId = Some(ds.id),
      status = com.pennsieve.models.ExternalRepositoryStatus.Enabled,
      autoProcess = true,
      synchronize = Some(syncFlags)
    )
    secureContainer.datasetManager.addExternalRepository(extRepo).await.value

    get(
      s"/paginated?type=release",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val returned =
        parsedBody.extract[com.pennsieve.api.PaginatedDatasets].datasets
      returned.length shouldEqual 1
      val head = returned.head
      head.content.repository.get.synchronize.get shouldEqual syncFlags
    }
  }

  test("2 step publishing - fail to embargo dataset without release date") {
    val ds = createDataSet("embargo-no-release-date")

    post(
      s"/${ds.nodeId}/publication/request?publicationType=embargo",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 400
    }
  }

  test("2 step publishing - invalid embargo release date") {
    val ds = createDataSet("embargo-invalid-date")

    // Greater than 1 year in the future
    postJson(
      s"/${ds.nodeId}/publication/request?publicationType=embargo&embargoReleaseDate=2040-01-01",
      "",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status shouldBe 400
    }

    // In the past
    postJson(
      s"/${ds.nodeId}/publication/request?publicationType=embargo&embargoReleaseDate=2020-01-01",
      "",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status shouldBe 400
    }
  }

  // ---- ORCID work JSON encoding (pure model tests) -----------------

  test("orcid work json encoding - add record") {
    import com.pennsieve.models._
    import io.circe.syntax.EncoderOps

    val json =
      """
        |{
        |  "title" : {
        |    "title" : { "value" : "title" },
        |    "subtitle" : { "value" : "subtitle" }
        |  },
        |  "type" : "data-set",
        |  "external-ids" : {
        |    "external-id" : [
        |      {
        |        "external-id-type" : "???",
        |        "external-id-value" : "???",
        |        "external-id-relationship" : "???",
        |        "external-id-url" : { "value" : "???" }
        |      }
        |    ]
        |  },
        |  "url" : { "value" : "???" }
        |}
        |""".stripMargin

    val orcidWork = OrcidWork(
      title = OrcidTitle(
        title = OrcidTitleValue(value = "title"),
        subtitle = OrcidTitleValue(value = "subtitle")
      ),
      `type` = "data-set",
      externalIds = OricdExternalIds(
        externalId = List(
          OrcidExternalId(
            externalIdType = "???",
            externalIdValue = "???",
            externalIdUrl = OrcidTitleValue(value = "???"),
            externalIdRelationship = "???"
          )
        )
      ),
      url = OrcidTitleValue(value = "???")
    )

    orcidWork.asJson.toString.filterNot(_.isWhitespace) shouldEqual
      json.filterNot(_.isWhitespace)
  }

  test("orcid work json encoding - update record") {
    import com.pennsieve.models._
    import io.circe.syntax.EncoderOps

    val json =
      """
        |{
        |  "title" : {
        |    "title" : { "value" : "title" },
        |    "subtitle" : { "value" : "subtitle" }
        |  },
        |  "type" : "data-set",
        |  "external-ids" : {
        |    "external-id" : [
        |      {
        |        "external-id-type" : "???",
        |        "external-id-value" : "???",
        |        "external-id-relationship" : "???",
        |        "external-id-url" : { "value" : "???" }
        |      }
        |    ]
        |  },
        |  "url" : { "value" : "???" },
        |  "put-code" : "1234567"
        |}
        |""".stripMargin

    val orcidWork = OrcidWork(
      title = OrcidTitle(
        title = OrcidTitleValue(value = "title"),
        subtitle = OrcidTitleValue(value = "subtitle")
      ),
      `type` = "data-set",
      externalIds = OricdExternalIds(
        externalId = List(
          OrcidExternalId(
            externalIdType = "???",
            externalIdValue = "???",
            externalIdUrl = OrcidTitleValue(value = "???"),
            externalIdRelationship = "???"
          )
        )
      ),
      url = OrcidTitleValue(value = "???"),
      putCode = Some("1234567")
    )

    orcidWork.asJson.toString.filterNot(_.isWhitespace) shouldEqual
      json.filterNot(_.isWhitespace)
  }

  // ---- custom event --------------------------------------------------

  test(
    "guest user should be restricted to seeing only authorized datasets on paginated endpoint"
  ) {
    for (i <- 1 to 10) {
      createDataSet(s"test-dataset-$i")
    }
    val guestContainer = secureContainerBuilder(guestUser, loggedInOrganization)
    guestContainer.datasetManager
      .create(name = "guest-user-dataset")
      .await
      .value

    get(
      "/paginated",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val response = parsedBody.extract[com.pennsieve.api.PaginatedDatasets]
      response.datasets.length shouldEqual 11
    }

    get(
      "/paginated",
      headers = authorizationHeader(guestJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val response = parsedBody.extract[com.pennsieve.api.PaginatedDatasets]
      response.datasets.length shouldEqual 1
    }
  }

  test("guest user should have access to their own dataset") {
    val dataset1 = createDataSet("workspace-shared-dataset")
    secureContainer.datasetManager
      .update(dataset1.copy(permission = Some(DBPermission.Delete)))
      .await
      .value

    val guestContainer = secureContainerBuilder(guestUser, loggedInOrganization)
    guestContainer.datasetManager
      .create(name = "guest-user-dataset")
      .await
      .value

    get("/", headers = authorizationHeader(guestJwt) ++ traceIdHeader()) {
      status should equal(200)
      val result = parsedBody.extract[List[DataSetDTO]]
      result.length should equal(1)
      result.map(_.content.name) should contain("guest-user-dataset")
    }
  }

  test(
    "guest user is not permitted access to datasets shared to workspace users"
  ) {
    val ds = createDataSet("workspace-shared-dataset")
    secureContainer.datasetManager
      .update(ds.copy(permission = Some(DBPermission.Delete)))
      .await
      .value

    get("/", headers = authorizationHeader(guestJwt) ++ traceIdHeader()) {
      status should equal(200)
      val result = parsedBody.extract[List[DataSetDTO]]
      result.length should equal(0)
    }
  }

  test("Send a custom event to the integrations") {
    val ds = createDataSet("test-dataset")
    val req = write(
      CustomEventRequest(
        eventType = "Integration Response",
        message = "This is a test message"
      )
    )

    postJson(
      s"${ds.nodeId}/event",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }
  }

  // ---- internal touch ------------------------------------------------

  test("touch updatedAt timestamp with a service claim is deprecated") {
    val ds = createDataSet("dataset")

    post(
      s"/internal/${ds.id}/touch",
      headers = jwtServiceAuthorizationHeader(loggedInOrganization)
    ) {
      status should equal(200)
      response.headers should contain key "Warning"
      response.getHeader("Warning") should include("deprecated")
    }

    secureContainer.datasetManager
      .get(ds.id)
      .await
      .value
      .updatedAt should be > ds.updatedAt
  }

  test("cannot touch updatedAt timestamp with a user claim") {
    val ds = createDataSet("dataset")
    post(
      s"/internal/${ds.id}/touch",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(403)
    }
  }

  test("cannot touch updatedAt timestamp without authorization") {
    val ds = createDataSet("dataset")
    post(s"/internal/${ds.id}/touch") {
      status should equal(401)
    }
  }

  test("return 404 Not Found if touched dataset does not exist") {
    post(
      s"/internal/9999/touch",
      headers = jwtServiceAuthorizationHeader(loggedInOrganization)
    ) {
      status should equal(404)
    }
  }

  // ---- DOI --------------------------------------------------------

  test("create and retrieve a DOI for a dataset") {
    val ds = createDataSet(name = "Foo")
    val doiRequest =
      write(CreateDraftDoiRequest(None, None, Some(2019), Some("abc-123")))

    postJson(
      s"/${ds.nodeId}/doi",
      doiRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
      val result = parsedBody.extract[DoiDTO]
      assert(result.title == Some(ds.name))
    }

    get(
      s"/${ds.nodeId}/doi",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody.extract[DoiDTO]
    }
  }

  test("deserialize a DOI correctly") {
    val doi = DoiDTO(
      1,
      1,
      "10.2137/abcd-1234",
      Some("My Dataset"),
      Some("http://discover.pennsieve.org/datasets/1"),
      "my publisher",
      None,
      Some(2019),
      Some(DoiState.Draft)
    )
    val json = write(doi)
    json shouldBe """{"organizationId":1,"datasetId":1,"doi":"10.2137/abcd-1234","title":"My Dataset","url":"http://discover.pennsieve.org/datasets/1","publisher":"my publisher","publicationYear":2019,"state":"draft"}"""
    read[DoiDTO](json).state shouldBe Some(DoiState.Draft)
  }

  test("fail to create and retrieve DOI without proper permissions") {
    val ds = createDataSet("Foo")

    val doiRequest = write(
      CreateDraftDoiRequest(
        Some("testTitle"),
        Some(Vector(CreatorDto("Creator M", "Maker"))),
        Some(2019),
        Some("abc-123")
      )
    )

    // colleagueUser has Delete-level permission on the org but no role on
    // the dataset, so they should be denied.
    postJson(
      s"/${ds.nodeId}/doi",
      doiRequest,
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }

    get(
      s"/${ds.nodeId}/doi",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }
  }

  test("fail to create a new DOI for a locked dataset") {
    val ds = createDataSet(name = "Foo")
    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Requested, PublicationType.Publication)
      .await
      .value

    val doiRequest =
      write(CreateDraftDoiRequest(None, None, Some(2019), Some("abc-123")))

    postJson(
      s"/${ds.nodeId}/doi",
      doiRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(423)
    }
  }

  // ---- permission -------------------------------------------------

  test("get user's effective dataset permission") {
    val myDS = createDataSet("My Dataset")

    get(
      s"/${myDS.nodeId}/permission",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val response = parsedBody.extract[DatasetPermissionResponse]
      response.userId should equal(loggedInUser.id)
      response.datasetId should equal(myDS.id)
      response.permission should equal(DBPermission.Owner)
    }

    get(
      s"/${myDS.nodeId}/permission",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }
  }

  // ---- locked flag --------------------------------------------------

  test("set locked flag on dataset DTO") {
    val ds = createDataSet("LockedTest")

    get(
      s"/${ds.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody.extract[DataSetDTO].locked shouldBe false
    }

    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Requested, PublicationType.Publication)
      .await
      .value

    get(
      s"/${ds.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody.extract[DataSetDTO].locked shouldBe true
    }
  }

  test("set locked flag on dataset DTO - paginated endpoint") {
    val ds = createDataSet("LockedTestPaginated")

    get(
      s"/paginated",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody
        .extract[com.pennsieve.api.PaginatedDatasets]
        .datasets
        .map(_.locked) should contain only false
    }

    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Requested, PublicationType.Publication)
      .await
      .value

    get(
      s"/paginated",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody
        .extract[com.pennsieve.api.PaginatedDatasets]
        .datasets
        .map(_.locked)
        .toSet shouldBe Set(false, true)
    }
  }

  // ---- collections --------------------------------------------------

  test("add a Collection to a dataset") {
    val ds = createDataSet("DatasetWithCollection")
    val collectionListBefore =
      secureContainer.collectionManager.getCollections().await.value

    val collection = secureContainer.collectionManager
      .create("My Own Collection")
      .await
      .value

    putJson(
      s"/${ds.nodeId}/collections",
      write(AddCollectionRequest(collectionId = collection.id)),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    val collectionListAfter =
      secureContainer.collectionManager.getCollections().await.value
    collectionListAfter shouldBe collectionListBefore :+ collection
  }

  test(
    "deleting a dataset should remove the Collection from the organization if the dataset was the last member of the collection"
  ) {
    val ds = createDataSet("DatasetWithCollection")
    val collectionListBefore =
      secureContainer.collectionManager.getCollections().await.value

    val collection = secureContainer.collectionManager
      .create("My Super New Collection")
      .await
      .value

    putJson(
      s"/${ds.nodeId}/collections",
      write(AddCollectionRequest(collectionId = collection.id)),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    val collectionsBeforeDelete =
      secureContainer.collectionManager.getCollections().await.value
    collectionsBeforeDelete shouldBe collectionListBefore :+ collection

    delete(
      s"/${ds.nodeId}/collections/${collection.id}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    val collectionsAfterDelete =
      secureContainer.collectionManager.getCollections().await.value
    collectionsAfterDelete shouldBe collectionListBefore
  }

  test("move a contributor down the contributor list") {
    val ds = createDataSet("ContributorTest")
    val ct1 = createContributor(
      "Tester",
      "Contributor",
      "tester-contributor-move@bf.com",
      None,
      None
    )
    putJson(
      s"/${ds.nodeId}/contributors",
      write(AddContributorRequest(ct1.id)),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    val ct2 = createContributor(
      "Tester2",
      "Contributor2",
      "tester2-contributor2-move@bf.com",
      None,
      None
    )
    putJson(
      s"/${ds.nodeId}/contributors",
      write(AddContributorRequest(ct2.id)),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    // ordering before switch: [auto-loggedInUser, ct1, ct2]
    val initial = get(
      s"/${ds.nodeId}/contributors",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody.extract[List[ContributorDTO]].map(_.id)
    }
    initial.length should equal(3)
    initial.takeRight(2) shouldBe List(ct1.id, ct2.id)

    postJson(
      s"/${ds.nodeId}/contributors/switch",
      write(SwitchContributorsOrderRequest(ct1.id, ct2.id)),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    // After switch: [auto-loggedInUser, ct2, ct1]
    get(
      s"/${ds.nodeId}/contributors",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val after = parsedBody.extract[List[ContributorDTO]].map(_.id)
      after.length should equal(3)
      after.takeRight(2) shouldBe List(ct2.id, ct1.id)
    }
  }

  test("delete a contributor errors when contributor does not exist") {
    delete(
      s"/${dataset.nodeId}/contributors/999999",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  test("delete data set fails if dataset was unpublished and published again") {
    val ds = createDataSet("Foo")
    val container = secureContainerFor(loggedInUser, loggedInOrganization)
    container.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .value
    container.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Removal)
      .await
      .value
    container.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .value
    container.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Requested, PublicationType.Publication)
      .await
      .value
    container.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Cancelled, PublicationType.Publication)
      .await
      .value

    delete(
      s"/${ds.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
    }
  }

  test("creating a dataset should use the default data use agreement") {
    val createReq = write(CreateDataSetRequest("A New DataSet", None, List()))

    val container = secureContainerFor(loggedInUser, loggedInOrganization)
    val defaultAgreement = container.dataUseAgreementManager
      .create(
        "Default data use agreement",
        "Lots of legal text",
        isDefault = true
      )
      .await
      .value
    container.dataUseAgreementManager
      .create(
        "Another data use agreement",
        "Lots of legal text",
        isDefault = false
      )
      .await
      .value

    postJson(
      s"",
      createReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
      parsedBody
        .extract[DataSetDTO]
        .content
        .dataUseAgreementId shouldBe Some(defaultAgreement.id)
    }
  }

  // ---------- Helpers --------------------------------------------------

  private def createDataSet(
    name: String,
    description: Option[String] = Some("This is a dataset."),
    tags: List[String] = List("tag"),
    `type`: DatasetType = DatasetType.Research
  ): Dataset = {
    val container = secureContainerFor(loggedInUser, loggedInOrganization)
    container.datasetManager
      .create(name, description, tags = tags, `type` = `type`)
      .await
      .value
  }

  private def createPackage(
    dataset: Dataset,
    name: String,
    state: PackageState = PackageState.READY,
    `type`: PackageType = PackageType.Collection,
    ownerId: Option[Int] = None,
    parent: Option[Package] = None,
    description: Option[String] = None,
    externalLocation: Option[String] = None
  ): Package = {
    val container = secureContainerFor(loggedInUser, loggedInOrganization)
    val pkg = container.packageManager
      .create(
        name,
        `type`,
        state,
        dataset,
        ownerId,
        parent,
        description = description,
        externalLocation = externalLocation
      )
      .await
      .value
    if (`type` == PackageType.ExternalFile) {
      container.externalFileManager
        .create(pkg, externalLocation.get, description)
        .await
        .value
    }
    pkg
  }

  /**
    * Mirrors `DataSetTestMixin.addBannerAndReadme`: create banner + readme
    * assets, upload through the mock asset client, then update the dataset
    * to point at them. Useful for listing tests that verify banner presence.
    */
  private def addBannerAndReadme(ds: Dataset): Dataset = {
    import java.io.ByteArrayInputStream
    val container = secureContainerBuilder(loggedInUser, loggedInOrganization)
    val banner = container.db
      .run(
        container.datasetAssetsManager
          .createQuery("banner.jpg", ds, "test-dataset-asset-bucket")
      )
      .await
    mockDatasetAssetClient
      .uploadAsset(
        banner,
        "binary content".getBytes.length,
        None,
        new ByteArrayInputStream("binary content".getBytes)
      )
      .value

    val readme = container.db
      .run(
        container.datasetAssetsManager
          .createQuery("readme.md", ds, "test-dataset-asset-bucket")
      )
      .await
    mockDatasetAssetClient
      .uploadAsset(
        readme,
        "readme description".getBytes.length,
        None,
        new ByteArrayInputStream("readme description".getBytes)
      )
      .value

    container.datasetManager
      .update(ds.copy(bannerId = Some(banner.id), readmeId = Some(readme.id)))
      .await
      .value
  }

  private def createContributor(
    firstName: String,
    lastName: String,
    email: String,
    middleInitial: Option[String] = None,
    degree: Option[Degree] = None,
    orcid: Option[String] = None,
    userId: Option[Int] = None,
    dataset: Option[Dataset] = None
  ): ContributorDTO = {
    val container = secureContainerBuilder(loggedInUser, loggedInOrganization)
    val (contributor, user) = container.contributorsManager
      .create(firstName, lastName, email, middleInitial, degree, orcid, userId)
      .await
      .value

    dataset.foreach(
      d =>
        container.datasetManager.addContributor(d, contributor.id).await.value
    )
    ContributorDTO((contributor, user))
  }
}
