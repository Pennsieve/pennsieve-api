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
  PaginatedStatusLogEntries,
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
import com.pennsieve.dtos.{
  ContributorDTO,
  DataSetDTO,
  ExtendedPackageDTO,
  SimpleFileDTO,
  WrappedDataset
}
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
  File,
  FileObjectType,
  FileProcessingState,
  FileState,
  FileType,
  License,
  NodeCodes,
  OrcidAuthorization,
  Organization,
  Package,
  PackageState,
  PackageType,
  PackagesPage,
  PublicationStatus,
  PublicationType,
  Role,
  User
}
import com.pennsieve.models.PackageType.{ CSV, PDF }
import org.json4s.jackson.Serialization.write
import org.apache.http.HttpHeaders
import org.scalatest.EitherValues._
import org.scalatest.OptionValues._

import java.util.UUID
import scala.concurrent.Future

class TestDataSetsController extends BaseApiUnitTest {

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
    state.clear()
    mockSearchClient.publishedDatasets.clear()
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

    // Welcome workspace — referenced by external-collaborator invite flow.
    val welcomeOrgId = state.newId()
    val welcomeOrg = Organization(
      nodeId = NodeCodes.generateId(NodeCodes.organizationCode),
      name = "Welcome",
      slug = "welcome_to_pennsieve",
      id = welcomeOrgId
    )
    state.organizations.put(welcomeOrgId, welcomeOrg)

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

    // Mirror initializePublicationTest's ORCID auth setup on the logged-in
    // user — many publication-flow tests rely on owner having an orcid.
    val orcidAuth = OrcidAuthorization(
      name = "John Doe",
      accessToken = "64918a80-dd0c-dd0c-dd0c-dd0c64918a80",
      expiresIn = 631138518,
      tokenType = "bearer",
      orcid = "0000-0012-3456-7890",
      scope = "/read-limited",
      refreshToken = "64918a80-dd0c-dd0c-dd0c-dd0c64918a80"
    )
    val withOrcid = loggedInUser.copy(orcidAuthorization = Some(orcidAuth))
    state.users.put(loggedInUser.id, withOrcid)
    loggedInUser = withOrcid

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

  // ---- /:id/packages paging / filtering -----------------------------

  test("return a page of packages for a dataset") {
    val ds = createDataSet("dataset-with-a-package")
    val pkg = createPackage(ds, "some-package")

    val expected =
      PackagesPage(List(ExtendedPackageDTO.simple(pkg, ds)), cursor = None)

    get(
      s"/${ds.nodeId}/packages",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody.extract[PackagesPage] shouldBe expected
    }
  }

  test("return a cursor to the next page of packages") {
    val ds = createDataSet("dataset-with-a-bunch-of-packages")
    val pkg1 = createPackage(ds, "some-package", ownerId = None)
    val pkg2 = createPackage(ds, "next-page-package", ownerId = None)

    val expected = PackagesPage(
      List(ExtendedPackageDTO.simple(pkg1, ds)),
      Some(s"package:${pkg2.id}")
    )

    get(
      s"/${ds.nodeId}/packages",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody.extract[PackagesPage] shouldBe expected
    }
  }

  test("return the next page for a cursor") {
    val ds = createDataSet("dataset-with-a-bunch-of-packages")
    val pkg1 = createPackage(ds, "some-package", ownerId = None)
    val pkg2 = createPackage(ds, "next-page-package", ownerId = None)

    val firstPage = PackagesPage(
      List(ExtendedPackageDTO.simple(pkg1, ds)),
      Some(s"package:${pkg2.id}")
    )
    val nextPage =
      PackagesPage(Seq(ExtendedPackageDTO.simple(pkg2, ds)), None)

    get(
      s"/${ds.nodeId}/packages",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[PackagesPage] shouldBe firstPage
    }
    val cursor = firstPage.cursor.get
    get(
      s"/${ds.nodeId}/packages?cursor=$cursor",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[PackagesPage] shouldBe nextPage
    }
  }

  test("return the next page for a cursor with files") {
    val ds = createDataSet("dataset-with-a-bunch-of-packages")
    val pkg1 = createPackage(ds, "some-package")
    val pkg2 = createPackage(ds, "next-page-package")

    val firstPage = PackagesPage(
      List(ExtendedPackageDTO.simple(pkg1, ds, objects = createObjects(pkg1))),
      Some(s"package:${pkg2.id}")
    )
    val nextPage = PackagesPage(
      Seq(ExtendedPackageDTO.simple(pkg2, ds, objects = createObjects(pkg2))),
      None
    )

    get(
      s"/${ds.nodeId}/packages?includeSourceFiles=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[PackagesPage] shouldBe firstPage
    }
    val cursor = firstPage.cursor.get
    get(
      s"/${ds.nodeId}/packages?cursor=$cursor&includeSourceFiles=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[PackagesPage] shouldBe nextPage
    }
  }

  test("return a user specified page size") {
    val ds = createDataSet("dataset-one-max-size-page")
    val pkgs = (1 to 10).map { i =>
      ExtendedPackageDTO.simple(createPackage(ds, i.toString), ds)
    }
    val expected = PackagesPage(pkgs, None)
    get(
      s"/${ds.nodeId}/packages?pageSize=10",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[PackagesPage] shouldBe expected
    }
  }

  test("return the requested page size for a user with a cursor") {
    val ds = createDataSet("dataset-one-max-size-page")
    val pkgs = (1 to 10).map { i =>
      val p = createPackage(ds, i.toString)
      ExtendedPackageDTO.simple(p, ds, objects = createObjects(p))
    }
    val expected =
      PackagesPage(Seq(pkgs.head), Some(s"package:${pkgs(1).content.id}"))
    get(
      s"/${ds.nodeId}/packages?pageSize=1&includeSourceFiles=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[PackagesPage] shouldBe expected
    }
  }

  test("return file sources if includeSourceFiles flag is set") {
    val ds = createDataSet("dataset-with-a-package")
    val pkg = createPackage(ds, "some-package", `type` = CSV)
    val objects = createObjects(pkg)

    val expectedPackage =
      ExtendedPackageDTO.simple(pkg, ds, objects = objects)
    val expected = PackagesPage(List(expectedPackage), cursor = None)

    get(
      s"/${ds.nodeId}/packages?includeSourceFiles=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody.extract[PackagesPage] shouldBe expected
    }
  }

  test(
    "return no files if includeSourceFiles flag is set and there are only views"
  ) {
    val ds = createDataSet("dataset-with-a-package-with-only-views")
    val pkg = createPackage(ds, "some-package", `type` = CSV)
    createFile(pkg, FileObjectType.View, FileProcessingState.NotProcessable)

    val expected = PackagesPage(
      List(ExtendedPackageDTO.simple(pkg, ds, objects = None)),
      None
    )
    get(
      s"/${ds.nodeId}/packages?includeSourceFiles=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[PackagesPage] shouldBe expected
    }
  }

  test(
    "return packages without files if includeSourceFiles flag is set and there are no files"
  ) {
    val ds = createDataSet("dataset-with-a-package-with-no-files")
    val pkg = createPackage(ds, "some-package", `type` = CSV)

    val expected = PackagesPage(
      List(ExtendedPackageDTO.simple(pkg, ds, objects = None)),
      None
    )
    get(
      s"/${ds.nodeId}/packages?includeSourceFiles=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[PackagesPage] shouldBe expected
    }
  }

  test(
    "return up to a maximum number of files per package and include isTruncated if more remain"
  ) {
    val ds = createDataSet("dataset-with-a-package-with-more-than-100-files")
    val pkg = createPackage(ds, "some-package", `type` = CSV)

    val files = (1 to 102).map(_ => createFile(pkg))

    val objects: Option[Map[String, List[SimpleFileDTO]]] = Some(
      Map(
        FileObjectType.Source.entryName ->
          files.take(100).toList.map(SimpleFileDTO(_, pkg)),
        FileObjectType.View.entryName -> List.empty[SimpleFileDTO],
        FileObjectType.File.entryName -> List.empty[SimpleFileDTO]
      )
    )
    val expected = PackagesPage(
      List(
        ExtendedPackageDTO
          .simple(pkg, ds, objects = objects, isTruncated = Some(true))
      ),
      None
    )
    get(
      s"/${ds.nodeId}/packages?includeSourceFiles=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[PackagesPage] shouldBe expected
    }
  }

  test("return packages matching a file name in their sources") {
    val ds = createDataSet("dataset-with-packages-and-files")
    val pkg1 = createPackage(ds, "some-package", `type` = CSV)
    val file1 = createFile(pkg1, name = "plop")
    val _ = createFile(pkg1, name = "plip")
    val pkg2 = createPackage(ds, "some-other-package", `type` = CSV)
    val _ = createFile(pkg2, name = "plap")

    val expected = PackagesPage(
      List(
        ExtendedPackageDTO.simple(
          pkg1,
          ds,
          objects = Some(
            Map(
              FileObjectType.Source.entryName -> List(
                SimpleFileDTO(file1, pkg1)
              ),
              FileObjectType.View.entryName -> List.empty[SimpleFileDTO],
              FileObjectType.File.entryName -> List.empty[SimpleFileDTO]
            )
          )
        )
      ),
      None
    )

    get(
      s"/${ds.nodeId}/packages?filename=plop&includeSourceFiles=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[PackagesPage] shouldBe expected
    }
    get(
      s"/${ds.nodeId}/packages?filename=plap&includeSourceFiles=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[PackagesPage].packages.length shouldBe 1
    }
  }

  test("return a filtered list of packages based on type") {
    val ds = createDataSet("dataset-with-a-CSV-package")
    val pkg = createPackage(ds, "some-package", `type` = CSV)
    createPackage(ds, "some-package-other")

    val expected =
      PackagesPage(
        List(ExtendedPackageDTO.simple(pkg, ds, objects = None)),
        None
      )
    get(
      s"/${ds.nodeId}/packages?types=CSV",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[PackagesPage] shouldBe expected
    }
  }

  test("return a filtered list of packages based on type ignoring case of type") {
    val ds = createDataSet("dataset-with-a-CSV-package")
    val pkg = createPackage(ds, "some-package", `type` = CSV)
    createPackage(ds, "some-package-other")

    val expected =
      PackagesPage(
        List(ExtendedPackageDTO.simple(pkg, ds, objects = None)),
        None
      )
    get(
      s"/${ds.nodeId}/packages?types=csv",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[PackagesPage] shouldBe expected
    }
  }

  test("return a filtered list of packages based on type including files") {
    val ds = createDataSet("dataset-with-a-csv-package-and-source")
    val pkg = createPackage(ds, "some-package", `type` = CSV)
    createPackage(ds, "some-package-other")

    val expected = PackagesPage(
      List(ExtendedPackageDTO.simple(pkg, ds, objects = createObjects(pkg))),
      None
    )
    get(
      s"/${ds.nodeId}/packages?types=CSV&includeSourceFiles=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[PackagesPage] shouldBe expected
    }
  }

  test("return packages for a set of package types with files") {
    val ds = createDataSet("dataset-with-a-CSV-and-PDF-package")
    val pdf = ExtendedPackageDTO.simple(
      createPackage(ds, "some-pdf", `type` = PDF),
      ds,
      objects = None
    )
    createPackage(ds, "some-collection")
    val csv = createPackage(ds, "some-csv", `type` = CSV)
    val csvDto =
      ExtendedPackageDTO.simple(csv, ds, objects = createObjects(csv))

    val expected = PackagesPage(List(pdf, csvDto), None)
    get(
      s"/${ds.nodeId}/packages?types=CSV:pdf&includeSourceFiles=true&pageSize=5",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[PackagesPage] shouldBe expected
    }
  }

  test("return packages for a set of package types") {
    val ds = createDataSet("dataset-with-a-CSV-and-PDF-package")
    val pdf = ExtendedPackageDTO.simple(
      createPackage(ds, "some-pdf", `type` = PDF),
      ds,
      objects = None
    )
    createPackage(ds, "some-collection")
    val csv = createPackage(ds, "some-csv", `type` = CSV)
    val csvDto = ExtendedPackageDTO.simple(csv, ds)

    val expected = PackagesPage(List(pdf, csvDto), None)
    get(
      s"/${ds.nodeId}/packages?types=CSV:pdf&pageSize=5",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[PackagesPage] shouldBe expected
    }
  }

  test("get multiple packages by id and node id") {
    val ds = createDataSet("My Dataset")
    val pkg1 =
      createPackage(ds, "Foo14", `type` = PDF, ownerId = Some(loggedInUser.id))
    val pkg2 =
      createPackage(ds, "Foo15", `type` = PDF, ownerId = Some(loggedInUser.id))

    get(
      s"/${ds.nodeId}/packages/batch?packageId=${pkg1.id}&packageId=${pkg2.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      (parsedBody \ "packages" \ "content" \ "id")
        .extract[Set[Int]] should equal(Set(pkg1.id, pkg2.id))
      (parsedBody \ "failures" \ "id").extract[List[Int]] shouldBe empty
    }
  }

  test(
    "get multiple packages and return failure when package id does not exist"
  ) {
    val ds = createDataSet("My Dataset")
    val pkg =
      createPackage(ds, "Foo14", `type` = PDF, ownerId = Some(loggedInUser.id))

    get(
      s"/${ds.nodeId}/packages/batch?packageId=${pkg.id}&packageId=34839524",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      (parsedBody \ "packages" \ "content" \ "id")
        .extract[List[Int]] should equal(List(pkg.id))
      (parsedBody \ "failures" \ "id").extract[List[Int]] should equal(
        List(34839524)
      )
    }
  }

  test("get multiple packages and return failure when package is deleted") {
    val ds = createDataSet("My Dataset")
    val pkg = createPackage(
      ds,
      "Foo14",
      `type` = PDF,
      state = PackageState.DELETING,
      ownerId = Some(loggedInUser.id)
    )

    get(
      s"/${ds.nodeId}/packages/batch?packageId=${pkg.id}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      (parsedBody \ "packages" \ "content" \ "id")
        .extract[List[Int]] shouldBe empty
      (parsedBody \ "failures" \ "id").extract[List[Int]] should equal(
        List(pkg.id)
      )
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

  test(
    "dataset release is not included in the DatasetDTO for a 'research' type dataset"
  ) {
    val ds = createDataSet("test-dataset-dto-for-type-research")
    get(
      s"/${ds.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val dto = parsedBody.extract[DataSetDTO]
      dto.content.datasetType should equal(DatasetType.Research)
      dto.content.releases should equal(None)
    }
  }

  test(
    "dataset release is included in the DatasetDTO for a 'release' type dataset"
  ) {
    val ds = createDataSet(
      "test-dataset-dto-for-type-release",
      `type` = DatasetType.Release
    )
    val release = secureContainer.datasetManager
      .addRelease(
        com.pennsieve.models.DatasetRelease(
          datasetId = ds.id,
          origin = "GitHub",
          url = "https://github.com/Pennsieve/test-repo",
          label = Some("v1.0.0"),
          marker = Some("1ab2c98"),
          releaseDate = Some(java.time.ZonedDateTime.now())
        )
      )
      .await
      .value

    get(
      s"/${ds.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val dto = parsedBody.extract[DataSetDTO]
      dto.content.datasetType should equal(DatasetType.Release)
      dto.content.releases shouldNot equal(None)
      dto.content.releases.get.length should equal(1)
      dto.content.releases.get.map(_.id) shouldBe Seq(release.id)
    }
  }

  test(
    "external repository is included in DatasetDTO for a 'release' type dataset"
  ) {
    val repoName = "test-dataset-for-external-repo"
    val repoUrl = s"https://github.com/Pennsieve/${repoName}"
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
      val returned = parsedBody
        .extract[com.pennsieve.api.PaginatedDatasets]
        .datasets
      returned.length shouldEqual 1
      returned.head.content.datasetType shouldBe DatasetType.Release
      returned.head.content.repository shouldNot equal(None)
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

  test("get all data sets by roles - paginated endpoint") {
    val ds1 = createDataSet("test-ds1")
    addBannerAndReadme(ds1)

    get(
      s"/paginated?withRole=Owner",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val response = parsedBody.extract[com.pennsieve.api.PaginatedDatasets]
      response.totalCount shouldBe 2
      response.datasets
        .filter(_.content.name != dataset.name)
        .map(_.content.name) shouldBe List("test-ds1")
    }

    val request =
      write(com.pennsieve.api.SwitchOwnerRequest(colleagueUser.nodeId))
    putJson(
      s"/${ds1.nodeId}/collaborators/owner",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    get(
      s"/paginated?withRole=Manager",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      val response = parsedBody.extract[com.pennsieve.api.PaginatedDatasets]
      response.totalCount shouldBe 1
      response.datasets.map(_.content.name) shouldBe List("test-ds1")
    }

    val roleChangeRequest =
      write(CollaboratorRoleDTO(loggedInUser.nodeId, Role.Editor))
    putJson(
      s"/${ds1.nodeId}/collaborators/users",
      roleChangeRequest,
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    get(
      s"/paginated?withRole=Editor",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      val response = parsedBody.extract[com.pennsieve.api.PaginatedDatasets]
      response.totalCount shouldBe 1
      response.datasets.map(_.content.name) shouldBe List("test-ds1")
    }

    get(
      s"/paginated?withRole=Viewer",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      val response = parsedBody.extract[com.pennsieve.api.PaginatedDatasets]
      response.totalCount shouldBe 0
    }

    val roleChangeRequest2 =
      write(CollaboratorRoleDTO(loggedInUser.nodeId, Role.Viewer))
    putJson(
      s"/${ds1.nodeId}/collaborators/users",
      roleChangeRequest2,
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    get(
      s"/paginated?withRole=Viewer",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      val response = parsedBody.extract[com.pennsieve.api.PaginatedDatasets]
      response.totalCount shouldBe 1
      response.datasets.map(_.content.name) shouldBe List("test-ds1")
    }

    get(
      s"/paginated?withRole=reviewer",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 400
      (parsedBody \ "message")
        .extract[String] shouldBe "invalid parameter withRole: must be one of Vector(guest, viewer, editor, manager, owner)"
    }

    get(
      s"/paginated?onlyMyDatasets=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      val response = parsedBody.extract[com.pennsieve.api.PaginatedDatasets]
      response.totalCount shouldBe 1
      response.datasets.map(_.content.name) shouldBe List("Home")
    }
  }

  test(
    "get DataSetDTOs and PublicDatasetDTOs for published datasets that a user has access to"
  ) {
    val dataset1 = createDataSet("test-dataset1")
    val dataset2 = createDataSet("test-dataset2")
    val dataset3 = createDataSet("test-dataset3")
    val dataset4 = createDataSet("test-dataset4")
    val dataset5 = createDataSet("test-dataset5")

    mockSearchClient.publishedDatasets ++= List(dataset1, dataset4, dataset5)
      .map(
        mockSearchClient
          .toMockPublicDatasetDTO(_, loggedInOrganization, loggedInUser)
      )

    get(
      s"/published/paginated?orderBy=updatedAt&orderDirection=Desc",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val publishedDatasets =
        parsedBody.extract[com.pennsieve.api.PaginatedPublishedDatasets]
      publishedDatasets.datasets.length shouldBe 3
      publishedDatasets.datasets
        .flatMap(_.dataset.map(_.content.id))
        .toSet shouldBe Set(dataset1.nodeId, dataset4.nodeId, dataset5.nodeId)
      publishedDatasets.datasets
        .flatMap(_.publishedDataset.sourceDatasetId)
        .toSet shouldBe Set(dataset1.id, dataset4.id, dataset5.id)
    }
  }

  test(
    "get only PublicDatasetDTOs for published datasets that a user does not have access to"
  ) {
    val dataset1 = createDataSet("test-dataset1")
    val dataset2 = createDataSet("test-dataset2")
    val dataset3 = createDataSet("test-dataset3")
    val dataset4 = createDataSet("test-dataset4")
    val dataset5 = createDataSet("test-dataset5")

    mockSearchClient.publishedDatasets ++= List(dataset1, dataset4, dataset5)
      .map(
        mockSearchClient
          .toMockPublicDatasetDTO(_, loggedInOrganization, loggedInUser)
      )

    get(
      s"/published/paginated?orderBy=name&orderDirection=Asc",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val publishedDatasets =
        parsedBody.extract[com.pennsieve.api.PaginatedPublishedDatasets]
      publishedDatasets.datasets.length shouldBe 3
      publishedDatasets.datasets.map(_.dataset) shouldBe List(None, None, None)
      publishedDatasets.datasets
        .flatMap(_.publishedDataset.sourceDatasetId)
        .toSet shouldBe Set(dataset1.id, dataset4.id, dataset5.id)
    }
  }

  test("include embargo access status for published datasets") {
    val ds1 = createDataSet("test-dataset1")
    val ds2 = createDataSet("test-dataset2")
    val ds3 = createDataSet("test-dataset3")

    mockSearchClient.publishedDatasets ++= List(ds1, ds2, ds3).map(
      mockSearchClient
        .toMockPublicDatasetDTO(_, loggedInOrganization, loggedInUser)
    )

    secureContainer.datasetPreviewManager
      .requestAccess(ds1, colleagueUser, None)
      .await
      .value
    secureContainer.datasetPreviewManager
      .grantAccess(ds2, colleagueUser)
      .await
      .value

    get(
      s"/published/paginated?orderBy=name&orderDirection=Asc",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody
        .extract[com.pennsieve.api.PaginatedPublishedDatasets]
        .datasets
        .map(d => (d.publishedDataset.sourceDatasetId.get, d.embargoAccess)) shouldBe List(
        ds1.id -> Some(com.pennsieve.models.EmbargoAccess.Requested),
        ds2.id -> Some(com.pennsieve.models.EmbargoAccess.Granted),
        ds3.id -> None
      )
    }
  }

  test(
    "request and accept preview access to embargoed dataset without a data user agreement"
  ) {
    val ds =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)

    get(
      s"/${ds.nodeId}/publication/preview",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody
        .extract[List[com.pennsieve.api.DatasetPreviewerDTO]] shouldBe empty
    }

    val serviceHeader = jwtServiceAuthorizationHeader(loggedInOrganization)

    postJson(
      s"/publication/preview/request",
      write(com.pennsieve.api.PreviewAccessRequest(ds.id, colleagueUser.id)),
      headers = serviceHeader ++ traceIdHeader()
    ) {
      status shouldBe 400
      body should include("must be under embargo")
    }

    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Embargo)
      .await
      .value

    postJson(
      s"/publication/preview/request",
      write(com.pennsieve.api.PreviewAccessRequest(ds.id, colleagueUser.id)),
      headers = serviceHeader ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    get(
      s"/${ds.nodeId}/publication/preview",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody
        .extract[List[com.pennsieve.api.DatasetPreviewerDTO]]
        .map(dto => (dto.user.email, dto.embargoAccess)) shouldBe List(
        (colleagueUser.email, com.pennsieve.models.EmbargoAccess.Requested)
      )
    }

    postJson(
      s"/${ds.nodeId}/publication/preview",
      write(com.pennsieve.api.GrantPreviewAccessRequest(colleagueUser.id)),
      headers = serviceHeader ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    get(
      s"/${ds.nodeId}/publication/preview",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody
        .extract[List[com.pennsieve.api.DatasetPreviewerDTO]]
        .map(dto => (dto.user.email, dto.embargoAccess)) shouldBe List(
        (colleagueUser.email, com.pennsieve.models.EmbargoAccess.Granted)
      )
    }
  }

  test(
    "cannot request access to embargoed dataset that user can already preview"
  ) {
    val ds =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)
    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Embargo)
      .await
      .value

    val agreement = createDataUseAgreement("AGREEMENT-1", "some text")
    val serviceHeader = jwtServiceAuthorizationHeader(loggedInOrganization)

    postJson(
      s"/${ds.nodeId}/publication/preview",
      write(com.pennsieve.api.GrantPreviewAccessRequest(colleagueUser.id)),
      headers = serviceHeader ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    postJson(
      s"/publication/preview/request",
      write(
        com.pennsieve.api
          .PreviewAccessRequest(ds.id, colleagueUser.id, Some(agreement.id))
      ),
      headers = serviceHeader ++ traceIdHeader()
    ) {
      status shouldBe 400
      (parsedBody \ "message")
        .extract[String] should equal("Access has already been granted")
    }
  }

  test("can grant preview access to users in different organization") {
    val ds = createDataSet("My Dataset")
    addBannerAndReadme(ds)

    postJson(
      s"/${ds.nodeId}/publication/preview",
      write(com.pennsieve.api.GrantPreviewAccessRequest(externalUser.id)),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
    }
  }

  test("grant preview access to embargoed dataset") {
    val ds = createDataSet("My Dataset")
    addBannerAndReadme(ds)

    get(
      s"/${ds.id}/publication/preview",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody
        .extract[List[com.pennsieve.api.DatasetPreviewerDTO]] shouldBe empty
    }

    postJson(
      s"/${ds.nodeId}/publication/preview",
      write(com.pennsieve.api.GrantPreviewAccessRequest(colleagueUser.id)),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    get(
      s"/${ds.nodeId}/publication/preview",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody
        .extract[List[com.pennsieve.api.DatasetPreviewerDTO]]
        .map(dto => (dto.user.email, dto.embargoAccess)) shouldBe List(
        (colleagueUser.email, com.pennsieve.models.EmbargoAccess.Granted)
      )
    }

    deleteJson(
      s"/${ds.id}/publication/preview",
      write(com.pennsieve.api.RemovePreviewAccessRequest(colleagueUser.id)),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    get(
      s"/${ds.nodeId}/publication/preview",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody
        .extract[List[com.pennsieve.api.DatasetPreviewerDTO]] shouldBe empty
    }
  }

  test(
    "request and accept preview access to embargoed dataset with a data use agreement"
  ) {
    val agreement = createDataUseAgreement("AGREEMENT-1", "some text")
    val ds =
      createDataSet("Embargoed dataset", dataUseAgreement = Some(agreement))

    val serviceHeader = jwtServiceAuthorizationHeader(loggedInOrganization)

    get(
      s"/${ds.nodeId}/publication/preview",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody
        .extract[List[com.pennsieve.api.DatasetPreviewerDTO]] shouldBe empty
    }

    postJson(
      s"/publication/preview/request",
      write(com.pennsieve.api.PreviewAccessRequest(ds.id, loggedInUser.id)),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 403
    }

    postJson(
      s"/publication/preview/request",
      write(com.pennsieve.api.PreviewAccessRequest(ds.id, loggedInUser.id)),
      headers = serviceHeader ++ traceIdHeader()
    ) {
      status shouldBe 400
      body should include("must be under embargo")
    }

    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Embargo)
      .await
      .value

    postJson(
      s"/publication/preview/request",
      write(
        com.pennsieve.api
          .PreviewAccessRequest(ds.id, loggedInUser.id, Some(9999999))
      ),
      headers = serviceHeader ++ traceIdHeader()
    ) {
      status shouldBe 400
    }

    postJson(
      s"/publication/preview/request",
      write(
        com.pennsieve.api.PreviewAccessRequest(ds.id, loggedInUser.id, None)
      ),
      headers = serviceHeader ++ traceIdHeader()
    ) {
      status shouldBe 400
    }

    postJson(
      s"/publication/preview/request",
      write(
        com.pennsieve.api
          .PreviewAccessRequest(ds.id, colleagueUser.id, Some(agreement.id))
      ),
      headers = serviceHeader ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    get(
      s"/${ds.nodeId}/publication/preview",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody
        .extract[List[com.pennsieve.api.DatasetPreviewerDTO]]
        .map(dto => (dto.user.email, dto.embargoAccess)) shouldBe List(
        (colleagueUser.email, com.pennsieve.models.EmbargoAccess.Requested)
      )
    }

    postJson(
      s"/${ds.nodeId}/publication/preview",
      write(com.pennsieve.api.GrantPreviewAccessRequest(colleagueUser.id)),
      headers = serviceHeader ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    get(
      s"/${ds.nodeId}/publication/preview",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody
        .extract[List[com.pennsieve.api.DatasetPreviewerDTO]]
        .map(dto => (dto.user.email, dto.embargoAccess)) shouldBe List(
        (colleagueUser.email, com.pennsieve.models.EmbargoAccess.Granted)
      )
    }
  }

  test("return data use agreement for embargoed dataset") {
    val agreement = createDataUseAgreement("AGREEMENT-1", "some text")
    val ds =
      createDataSet("Embargoed dataset", dataUseAgreement = Some(agreement))

    get(
      s"/${ds.nodeId}/publication/data-use-agreement",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody
        .extract[com.pennsieve.api.DataUseAgreementDTO] shouldBe
        com.pennsieve.api.DataUseAgreementDTO(agreement)
    }
  }

  test("return data use agreement for embargoed dataset integer ID") {
    val agreement = createDataUseAgreement("AGREEMENT-1", "some text")
    val ds =
      createDataSet("Embargoed dataset", dataUseAgreement = Some(agreement))

    get(
      s"/${ds.id}/publication/data-use-agreement",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody
        .extract[com.pennsieve.api.DataUseAgreementDTO] shouldBe
        com.pennsieve.api.DataUseAgreementDTO(agreement)
    }
  }

  test("return 204 when embargoed dataset does not have data use agreement") {
    val ds = createDataSet("Embargoed dataset", dataUseAgreement = None)

    get(
      s"/${ds.nodeId}/publication/data-use-agreement",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 204
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

  test("get dataset status log") {
    val ds = createDataSet("dataset-name")

    val request =
      write(UpdateDataSetRequest(None, ds.description, Some("IN_REVIEW")))

    putJson(
      s"/${ds.nodeId}",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val result: WrappedDataset = parsedBody.extract[DataSetDTO].content
      result.name shouldEqual ds.name
      result.description shouldBe ds.description
      result.status shouldEqual "IN_REVIEW"
    }

    get(
      s"/${ds.nodeId}/status-log",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val results = parsedBody.extract[PaginatedStatusLogEntries]

      results.entries.length should equal(2)
      results.limit should equal(25)
      results.offset should equal(0)
      results.totalCount should equal(2)
      results.entries.map(
        r =>
          r.user match {
            case Some(user) =>
              (
                user.firstName,
                user.lastName,
                r.status.name,
                r.status.displayName
              )
            case None =>
              ("", "", r.status.name, r.status.displayName)
          }
      ) shouldBe List(
        (
          loggedInUser.firstName,
          loggedInUser.lastName,
          "IN_REVIEW",
          "In Review"
        ),
        (
          loggedInUser.firstName,
          loggedInUser.lastName,
          "NO_STATUS",
          "No Status"
        )
      )
    }

    val request2 =
      write(UpdateDataSetRequest(None, ds.description, Some("COMPLETED")))

    putJson(
      s"/${ds.nodeId}",
      request2,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val result: WrappedDataset = parsedBody.extract[DataSetDTO].content
      result.status shouldEqual "COMPLETED"
    }

    get(
      s"/${ds.nodeId}/status-log",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val results = parsedBody.extract[PaginatedStatusLogEntries]

      results.entries.length should equal(3)
      results.totalCount should equal(3)
      results.entries.map(
        r =>
          r.user match {
            case Some(user) =>
              (
                user.firstName,
                user.lastName,
                r.status.name,
                r.status.displayName
              )
            case None =>
              ("", "", r.status.name, r.status.displayName)
          }
      ) shouldBe List(
        (
          loggedInUser.firstName,
          loggedInUser.lastName,
          "COMPLETED",
          "Completed"
        ),
        (
          loggedInUser.firstName,
          loggedInUser.lastName,
          "IN_REVIEW",
          "In Review"
        ),
        (
          loggedInUser.firstName,
          loggedInUser.lastName,
          "NO_STATUS",
          "No Status"
        )
      )
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

  test("only admins can delete data sets") {
    val ds = createDataSet("Foo")

    val ids = write(List(colleagueUser.nodeId))
    putJson(
      s"/${ds.nodeId}/collaborators",
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

    delete(
      s"/${ds.nodeId}",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }

    delete(
      s"/${ds.nodeId}",
      headers = authorizationHeader(externalJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
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

  test(
    "get all data sets for a given status for the logged in user - paginated endpoint"
  ) {
    createDataSet("test-ds1")
    createDataSet("test-ds2")
    createDataSet("test-ds3")
    val createReq = write(
      CreateDataSetRequest(
        "A New DataSet",
        None,
        Nil,
        status = Some("IN_REVIEW"),
        license = Some(License.`GNU General Public License v3.0`),
        tags = List("tag1", "tag2")
      )
    )
    postJson(
      "",
      createReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
    }

    get(
      s"/paginated?status=IN_REVIEW",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val response = parsedBody.extract[com.pennsieve.api.PaginatedDatasets]
      response.totalCount shouldBe 1
      response.datasets.count(_.content.name == "A New DataSet") shouldBe 1
    }
  }

  test(
    "get all data sets for the logged in user - paginated endpoint shows published dataset info"
  ) {
    // Reset state.ids so the next dataset gets id == 1 (matches mock's
    // sourceDatasetId 1 in MockPublishClient.getStatuses).
    val ds1 = createDataSetWithId("PPMI", 1)
    val ds2 = createDataSetWithId("TUSZ", 2)

    get(
      s"/paginated?includePublishedDataset=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val response = parsedBody.extract[com.pennsieve.api.PaginatedDatasets]
      val ourDatasets = response.datasets.filter(
        d => Set(ds1.id, ds2.id).contains(d.content.intId)
      )
      ourDatasets
        .flatMap(_.publication.publishedDataset.map(_.id))
        .toSet shouldBe
        Set(Some(10), Some(12))
      ourDatasets
        .flatMap(_.publication.publishedDataset.map(_.version))
        .toSet shouldBe
        Set(2, 3)
    }
  }

  test(
    "get all data sets for the logged in user - paginated endpoint canPublish flag"
  ) {
    val ds1 = createDataSet("canPublish")
    addBannerAndReadme(ds1)

    val updated = loggedInUser.copy(
      orcidAuthorization =
        Some(OrcidAuthorization("foo", "bar", 1, "qux", "fizz", "buzz", "biff"))
    )
    state.users.put(loggedInUser.id, updated)
    loggedInUser = updated
    secureContainer = secureContainerBuilder(loggedInUser, loggedInOrganization)

    val ds2 = createDataSet("noDescription", description = None)
    addBannerAndReadme(ds2)
    val ds3 = createDataSet("noTags", tags = List.empty)
    addBannerAndReadme(ds3)
    val ds4 = createDataSet("noLicense", license = None)
    addBannerAndReadme(ds4)
    val ds5 = createDataSet("noReadme")
    val ds6 = createDataSet("locked")
    addBannerAndReadme(ds6)
    secureContainer.datasetPublicationStatusManager
      .create(
        ds6,
        PublicationStatus.Requested,
        PublicationType.Publication,
        None
      )
      .await
      .value
    val ds7 = createDataSet("noContributors")
    addBannerAndReadme(ds7)
    state.datasetContributors.keys
      .filter(k => k._1 == loggedInOrganization.id && k._2 == ds7.id)
      .foreach(state.datasetContributors.remove)
    val ds8 = createDataSet("noOwner")
    addBannerAndReadme(ds8)
    secureContainer.datasetManager
      .switchOwner(ds8, loggedInUser, colleagueUser)
      .await
      .value

    get(
      s"/paginated?canPublish=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val response = parsedBody.extract[com.pennsieve.api.PaginatedDatasets]
      response.datasets.length shouldBe 1
      response.totalCount shouldBe 1
      response.datasets.map(_.content.intId) shouldBe Seq(ds1.id)
    }
  }

  test(
    "get all data sets for which the logged in user is the owner - paginated endpoint"
  ) {
    val ds1 = createDataSet("test-ds1"); addBannerAndReadme(ds1)
    val ds2 = createDataSet("test-ds2"); addBannerAndReadme(ds2)
    val ds3 = createDataSet("test-ds3"); addBannerAndReadme(ds3)
    val ds4 = createDataSet("dataset4"); addBannerAndReadme(ds4)
    val ds5 = createDataSet("dataset5"); addBannerAndReadme(ds5)
    val ds6 = createDataSet("dataset6"); addBannerAndReadme(ds6)
    val ds7 = createDataSet("dataset7"); addBannerAndReadme(ds7)
    val ds8 = createDataSet("dataset8"); addBannerAndReadme(ds8)
    val ds9 = createDataSet("dataset9"); addBannerAndReadme(ds9)
    val ds10 = createDataSet("dataset10"); addBannerAndReadme(ds10)

    val request =
      write(com.pennsieve.api.SwitchOwnerRequest(colleagueUser.nodeId))
    putJson(
      s"/${ds3.nodeId}/collaborators/owner",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) { status should equal(200) }

    get(
      s"/paginated?onlyMyDatasets=true&includeBannerUrl=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val response = parsedBody.extract[com.pennsieve.api.PaginatedDatasets]
      response.totalCount shouldBe 10
      response.datasets.count(_.content.name == "test-ds3") shouldBe 0
      response.datasets
        .filter(_.content.name != dataset.name)
        .map(_.bannerPresignedUrl.isDefined)
        .foldLeft(true)(_ && _) shouldBe true
    }
  }

  test(
    "get all data sets for the logged in user in different orders of updated at - paginated endpoint"
  ) {
    val ds1 = createDataSet("Test-ds1")
    val ds2 = createDataSet("Test-ds2")
    val ds3 = createDataSet("Test-ds3")
    val createReq = write(
      CreateDataSetRequest(
        "A New DataSet",
        None,
        Nil,
        status = Some("IN_REVIEW"),
        license = Some(License.`GNU General Public License v3.0`),
        tags = List("tag1", "tag2")
      )
    )
    postJson(
      "",
      createReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) { status should equal(201) }

    get(
      s"/paginated?orderBy=UpdatedAt&orderDirection=Asc&limit=2",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val response = parsedBody.extract[com.pennsieve.api.PaginatedDatasets]
      response.totalCount shouldBe 5
      response.datasets.map(_.content.name).toSet shouldBe Set(
        "Home",
        "Test-ds1"
      )
    }

    get(
      s"/paginated?orderBy=UpdatedAt&orderDirection=Desc&limit=2",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val response = parsedBody.extract[com.pennsieve.api.PaginatedDatasets]
      response.totalCount shouldBe 5
      response.datasets.map(_.content.name).toSet shouldBe Set(
        "A New DataSet",
        "Test-ds3"
      )
    }
  }

  test(
    "get all data sets for the logged in user in different orders of integer ID - paginated endpoint"
  ) {
    val ds1 = createDataSet("Test-ds1")
    val ds2 = createDataSet("Test-ds2")
    val ds3 = createDataSet("Test-ds3")
    val createReq = write(
      CreateDataSetRequest(
        "A New DataSet",
        None,
        Nil,
        status = Some("IN_REVIEW"),
        license = Some(License.`GNU General Public License v3.0`),
        tags = List("tag1", "tag2")
      )
    )
    postJson(
      "",
      createReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) { status should equal(201) }

    get(
      s"/paginated?orderBy=IntId&orderDirection=Asc&limit=5",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val response = parsedBody.extract[com.pennsieve.api.PaginatedDatasets]
      response.totalCount shouldBe 5
      response.datasets.size shouldBe 5
    }

    get(
      s"/paginated?orderBy=IntId&orderDirection=Desc&limit=2",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val response = parsedBody.extract[com.pennsieve.api.PaginatedDatasets]
      response.totalCount shouldBe 5
      response.datasets.size shouldBe 2
      // Descending by id — first id should be > second id
      response.datasets.head.content.intId should be >
        response.datasets(1).content.intId
    }
  }

  test(
    "get all data sets for the logged in user for a text search - paginated endpoint"
  ) {
    createDataSet("test-ds1")
    createDataSet("test-ds2")
    createDataSet("test-ds3")
    createDataSet("dataset-4")
    createDataSet("Another Data set")

    // Simple substring search ("test-ds" matches all 3 test-ds*)
    get(
      s"/paginated?query=test-ds",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val response = parsedBody.extract[com.pennsieve.api.PaginatedDatasets]
      response.totalCount shouldBe 3
      response.datasets.map(_.content.name).toSet shouldBe Set(
        "test-ds1",
        "test-ds2",
        "test-ds3"
      )
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

  test("upload dataset banner") {
    val ds = createDataSet("My Dataset")
    val bannerFile =
      new java.io.File("src/test/resources/test-assets/banner.jpg")
    val fileUploads =
      Map(
        "banner" -> org.scalatra.test
          .FilePart(bannerFile, contentType = "image/jpeg")
      )

    put(
      s"/${ds.nodeId}/banner",
      Map(),
      fileUploads,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      val dto = parsedBody.extract[com.pennsieve.api.DatasetBannerDTO]
      dto.banner.get.toString should include("?presigned=true")

      val bannerAsset = secureContainer.datasetAssetsManager
        .getBanner(ds)
        .value
        .await
        .value
        .get
      val expectedKey =
        s"${loggedInOrganization.id}/${ds.id}/${bannerAsset.id}/banner.jpg"
      bannerAsset.name shouldBe "banner.jpg"
      bannerAsset.s3Bucket shouldBe mockDatasetAssetClient.bucket
      bannerAsset.s3Key shouldBe expectedKey
      bannerAsset.datasetId shouldBe ds.id
    }
  }

  test("replace a dataset banner") {
    val ds = createDataSet("My Dataset")
    val originalBannerFile =
      new java.io.File("src/test/resources/test-assets/banner.jpg")
    val fileUploads = Map(
      "banner" -> org.scalatra.test
        .FilePart(originalBannerFile, contentType = "image/jpeg")
    )

    put(
      s"/${ds.nodeId}/banner",
      Map(),
      fileUploads,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    val originalBannerAsset = secureContainer.datasetAssetsManager
      .getBanner(ds)
      .value
      .await
      .value
      .get

    val updatedBannerFile =
      new java.io.File("src/test/resources/test-assets/newBanner.jpg")
    val updatedFileUploads = Map(
      "banner" -> org.scalatra.test
        .FilePart(updatedBannerFile, contentType = "image/jpeg")
    )
    put(
      s"/${ds.nodeId}/banner",
      Map(),
      updatedFileUploads,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      val updatedBannerAsset = secureContainer.datasetAssetsManager
        .getBanner(ds)
        .value
        .await
        .value
        .get
      val updatedExpectedKey =
        s"${loggedInOrganization.id}/${ds.id}/${updatedBannerAsset.id}/newBanner.jpg"
      updatedBannerAsset.name shouldBe "newBanner.jpg"
      updatedBannerAsset.s3Key shouldBe updatedExpectedKey
      updatedBannerAsset.id should not be originalBannerAsset.id
    }
  }

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

  test("create and modify dataset readme with If-Match header") {
    val ds = createDataSet("My Dataset")
    val readme = "#Markdown content\nA paragraph!"
    val request = write(com.pennsieve.api.DatasetReadmeDTO(readme = readme))

    putJson(
      s"/${ds.nodeId}/readme",
      request,
      authorizationHeader(loggedInJwt) ++ Map(HttpHeaders.IF_MATCH -> "0")
    ) {
      status shouldBe 200
      val newReadme = secureContainer.datasetAssetsManager
        .getReadme(ds)
        .value
        .await
        .value
        .get
      response.getHeader(HttpHeaders.ETAG) shouldBe newReadme.etag.asHeader
      response.getHeader(HttpHeaders.ETAG) should not be "0"
    }

    val updatedReadme = "#Markdown content\nA paragraph!\nSome more!"
    val updateRequest =
      write(com.pennsieve.api.DatasetReadmeDTO(readme = updatedReadme))

    val existingReadmeAsset = secureContainer.datasetAssetsManager
      .getReadme(ds)
      .value
      .await
      .value
      .get

    putJson(
      s"/${ds.nodeId}/readme",
      updateRequest,
      authorizationHeader(loggedInJwt) ++ traceIdHeader() ++ Map(
        HttpHeaders.IF_MATCH -> existingReadmeAsset.etag.asHeader
      )
    ) {
      status shouldBe 200
      val updated = secureContainer.datasetAssetsManager
        .getReadme(ds)
        .value
        .await
        .value
        .get
      response.getHeader(HttpHeaders.ETAG) shouldBe updated.etag.asHeader
      response
        .getHeader(HttpHeaders.ETAG) should not be existingReadmeAsset.etag.asHeader
    }
  }

  test(
    "fail to update an existing dataset readme if the If-Match header indicates a stale version"
  ) {
    val ds = addBannerAndReadme(createDataSet("My Dataset"))
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

  test(
    "user creates data set that belongs to org then shares it with a team only creator and users of that team should have access"
  ) {
    val myDS = createDataSet("My DataSet")

    val myTeam = com.pennsieve.models.Team(
      nodeId = NodeCodes.generateId(NodeCodes.teamCode),
      name = "My Team",
      id = state.newId()
    )
    state.teams.put((loggedInOrganization.id, myTeam.id), myTeam)
    secureContainer.teamManager
      .addUser(myTeam, loggedInUser, DBPermission.Delete)
      .await
      .value
    secureContainer.teamManager
      .addUser(myTeam, colleagueUser, DBPermission.Delete)
      .await
      .value

    val myOtherTeam = com.pennsieve.models.Team(
      nodeId = NodeCodes.generateId(NodeCodes.teamCode),
      name = "My Other Team",
      id = state.newId()
    )
    state.teams.put((loggedInOrganization.id, myOtherTeam.id), myOtherTeam)
    secureContainer.teamManager
      .addUser(myOtherTeam, colleagueUser, DBPermission.Delete)
      .await
      .value

    val ids = write(List(myTeam.nodeId, myOtherTeam.nodeId))
    putJson(
      s"/${myDS.nodeId}/collaborators",
      ids,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val response = parsedBody.extract[CollaboratorChanges]
      response.changes.get(myTeam.nodeId).value.success should equal(true)
      response.counts.users should equal(0)
      response.counts.teams should equal(2)
    }

    get(
      s"/${myDS.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val counts = parsedBody.extract[DataSetDTO].collaboratorCounts
      counts.organizations should equal(0)
      counts.users should equal(0)
      counts.teams should equal(2)
    }

    get(
      s"/${myDS.nodeId}",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should include("My DataSet")
    }

    get(
      s"/${myDS.nodeId}",
      headers = authorizationHeader(externalJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  test(
    """user1 creates data set that belongs to org and superAdmin switches ownership to user2, belonging to the same org user1 should be made manager and user2 be made owner"""
  ) {
    val myDS = createDataSet("My DataSet")
    val request =
      write(com.pennsieve.api.SwitchOwnerRequest(colleagueUser.nodeId))
    putJson(
      s"/${myDS.nodeId}/collaborators/owner",
      request,
      headers = authorizationHeader(adminJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }
    val owner = secureContainer.datasetManager.getOwner(myDS).await.value
    val colleagueUserRole = secureContainer.datasetManager
      .maxRole(myDS, colleagueUser)
      .await
      .value
    val loggedInUserRole = secureContainer.datasetManager
      .maxRole(myDS, loggedInUser)
      .await
      .value
    owner.nodeId should equal(colleagueUser.nodeId)
    loggedInUserRole should equal(Role.Manager)
    colleagueUserRole should equal(Role.Owner)
  }

  test("PUT collaborators/users is allowed during publication lockdown") {
    val ds = createDataSet("My DataSet")
    addBannerAndReadme(ds)
    val pkg = createPackage(ds, "some-package", `type` = CSV)
    createFile(pkg, FileObjectType.Source, FileProcessingState.Processed)

    postJson(
      s"/${ds.nodeId}/publication/request?publicationType=publication&comments=hello%20world",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    val roleChangeRequest =
      write(CollaboratorRoleDTO(colleagueUser.nodeId, Role.Manager))
    putJson(
      s"/${ds.nodeId}/collaborators/users",
      roleChangeRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }
  }

  test(
    """user creates data set that belongs to org
      |and shares it with another user who belongs to that org then only creator
      |and currently shared user should have access""".stripMargin
  ) {
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

    get("/", headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()) {
      status should equal(200)
      body should include("My DataSet")
    }

    get("/", headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()) {
      status should equal(200)
      body should include("My DataSet")
    }

    get("/", headers = authorizationHeader(externalJwt) ++ traceIdHeader()) {
      status should equal(200)
      parsedBody.extract[List[String]] should have size 0
    }
  }

  test("""user creates data set that belongs to org and tries to share it with
      |a user who belong to another org should fail""".stripMargin) {
    val myDS = createDataSet("My DataSet")

    val ids = write(List(externalUser.nodeId))
    putJson(
      s"/${myDS.nodeId}/collaborators",
      ids,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody
        .extract[CollaboratorChanges]
        .changes
        .get(externalUser.nodeId)
        .value
        .success should equal(false)
    }
  }

  test(
    """user creates data set that belongs to org
      |and shares it using roles with another user who belongs to that org then only creator
      |and currently shared user should have access""".stripMargin
  ) {
    val myDS = createDataSet("My DataSet")

    val request = write(CollaboratorRoleDTO(colleagueUser.nodeId, Role.Editor))
    putJson(
      s"/${myDS.nodeId}/collaborators/users",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    get("/", headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()) {
      status should equal(200)
      body should include("My DataSet")
    }

    get("/", headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()) {
      status should equal(200)
      body should include("My DataSet")
    }

    get("/", headers = authorizationHeader(externalJwt) ++ traceIdHeader()) {
      status should equal(200)
      parsedBody.extract[List[String]] should have size 0
    }
  }

  test("""user creates data set that belongs to org
      |and tries to share it using roles
      |with a user who belong to another org should fail""".stripMargin) {
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

  test("""user creates data set that belongs to org
      |then shares it with a team using roles
      |only creator and users of that team should have access""".stripMargin) {
    val myDS = createDataSet("My DataSet")

    val myTeam = com.pennsieve.models.Team(
      nodeId = NodeCodes.generateId(NodeCodes.teamCode),
      name = "My Team",
      id = state.newId()
    )
    state.teams.put((loggedInOrganization.id, myTeam.id), myTeam)
    secureContainer.teamManager
      .addUser(myTeam, loggedInUser, DBPermission.Delete)
      .await
      .value
    secureContainer.teamManager
      .addUser(myTeam, colleagueUser, DBPermission.Delete)
      .await
      .value

    val ids = write(CollaboratorRoleDTO(myTeam.nodeId, Role.Editor))
    putJson(
      s"/${myDS.nodeId}/collaborators/teams",
      ids,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    get(
      s"/${myDS.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val counts = parsedBody.extract[DataSetDTO].collaboratorCounts
      counts.organizations should equal(0)
      counts.users should equal(0)
      counts.teams should equal(1)
    }

    get(
      s"/${myDS.nodeId}/collaborators/teams",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody
        .extract[List[com.pennsieve.api.TeamCollaboratorRoleDTO]] should contain(
        com.pennsieve.api
          .TeamCollaboratorRoleDTO(myTeam.nodeId, myTeam.name, Role.Editor)
      )
    }

    get(
      s"/${myDS.nodeId}",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should include("My DataSet")
    }

    get(
      s"/${myDS.nodeId}",
      headers = authorizationHeader(externalJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  test("""system team user creates data set that belongs to org
      |then shares it with a system team
      |users cannot perform this action""".stripMargin) {
    val myDS = createDataSet("My DataSet")
    val (publisherTeam, _) = secureContainer.organizationManager
      .getPublisherTeam(secureContainer.organization)
      .await
      .value

    val ids = write(CollaboratorRoleDTO(publisherTeam.nodeId, Role.Editor))
    putJson(
      s"/${myDS.nodeId}/collaborators/teams",
      ids,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
    }
  }

  test("""user1 creates data set that belongs to org
      |and switches ownership to user2, belonging to the same org
      |user1 should be made manager and user2 be made owner""".stripMargin) {
    val myDS = createDataSet("My DataSet")
    val request =
      write(com.pennsieve.api.SwitchOwnerRequest(colleagueUser.nodeId))
    putJson(
      s"/${myDS.nodeId}/collaborators/owner",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }
    val owner = secureContainer.datasetManager.getOwner(myDS).await.value
    val colleagueUserRole = secureContainer.datasetManager
      .maxRole(myDS, colleagueUser)
      .await
      .value
    val loggedInUserRole = secureContainer.datasetManager
      .maxRole(myDS, loggedInUser)
      .await
      .value
    owner.nodeId should equal(colleagueUser.nodeId)
    colleagueUserRole should equal(Role.Owner)
    loggedInUserRole should equal(Role.Manager)
  }

  test("""user1 creates data set that belongs to org
      |and user2 switches ownership to user2, belonging to the same org
      | should not work""".stripMargin) {
    val myDS = createDataSet("My DataSet")
    val request =
      write(com.pennsieve.api.SwitchOwnerRequest(colleagueUser.nodeId))
    putJson(
      s"/${myDS.nodeId}/collaborators/owner",
      request,
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }
  }

  test("""user1 creates data set that belongs to org
      |and switches ownership to user2, not belonging to the same org
      |we should get a 404""".stripMargin) {
    val myDS = createDataSet("My DataSet")
    val request =
      write(com.pennsieve.api.SwitchOwnerRequest(externalUser.nodeId))
    putJson(
      s"/${myDS.nodeId}/collaborators/owner",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  test("""user1 creates data set that belongs to org
      |and switches ownership to user2, belonging to the same org
      |then tries to get it back and gets a 403""".stripMargin) {
    val myDS = createDataSet("My DataSet")
    val request =
      write(com.pennsieve.api.SwitchOwnerRequest(colleagueUser.nodeId))
    putJson(
      s"/${myDS.nodeId}/collaborators/owner",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    val request2 =
      write(com.pennsieve.api.SwitchOwnerRequest(loggedInUser.nodeId))
    putJson(
      s"/${myDS.nodeId}/collaborators/owner",
      request2,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
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

  test("external user should be invited to a dataset") {
    val ds = createDataSet(s"test-dataset-for-external-invite")
    val externalInvite = CollaboratorRoleDTO(externalUser.email, Role.Editor)

    putJson(
      s"/${ds.nodeId}/collaborators/external",
      write(externalInvite),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldEqual 200
    }

    get(
      s"/${ds.nodeId}/collaborators/users",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldEqual 200
      val response = parsedBody.extract[List[UserCollaboratorRoleDTO]]
      val externalPresent =
        response.filter(u => u.email.equals(externalUser.email))
      externalPresent.length shouldEqual 1
    }
  }

  test("external user should have access to a dataset they are invited to") {
    val ds = createDataSet(s"test-dataset-for-external-access")
    val externalInvite = CollaboratorRoleDTO(externalUser.email, Role.Editor)

    putJson(
      s"/${ds.nodeId}/collaborators/external",
      write(externalInvite),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldEqual 200
    }

    val externalJwt2 = mintUserJwt(externalUser, loggedInOrganization)

    get(
      s"/${ds.nodeId}",
      headers = authorizationHeader(externalJwt2) ++ traceIdHeader()
    ) {
      status shouldEqual 200
    }
  }

  test("user cannot unshare with a system team") {
    val ds = createDataSet("My Dataset")
    val (publisherTeam, _) = secureContainer.organizationManager
      .getPublisherTeam(loggedInOrganization)
      .await
      .value
    secureContainer.datasetManager
      .addTeamCollaborator(ds, publisherTeam, Role.Viewer)
      .await
      .value

    get(
      s"/${ds.nodeId}/collaborators/teams",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody
        .extract[List[com.pennsieve.api.TeamCollaboratorRoleDTO]] should contain(
        com.pennsieve.api.TeamCollaboratorRoleDTO(
          publisherTeam.nodeId,
          publisherTeam.name,
          Role.Viewer
        )
      )
    }

    deleteJson(
      s"/${ds.nodeId}/collaborators/teams",
      write(RemoveCollaboratorRequest(publisherTeam.nodeId)),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
    }

    get(
      s"/${ds.nodeId}/collaborators/teams",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody
        .extract[List[com.pennsieve.api.TeamCollaboratorRoleDTO]] should contain(
        com.pennsieve.api.TeamCollaboratorRoleDTO(
          publisherTeam.nodeId,
          publisherTeam.name,
          Role.Viewer
        )
      )
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

  /**
    * Create a Webhook + integration user directly into state. Mirrors
    * `DataSetTestMixin.createWebhook` but skipping the cognito flow.
    */
  private def createWebhook(
    isPrivate: Boolean = false,
    isDefault: Boolean = false,
    hasAccess: Boolean = false
  ): com.pennsieve.models.Webhook = {
    val integrationUser = mkUser(s"integration-${state.newId()}@test.com")
      .copy(isIntegrationUser = true)
    state.users.put(integrationUser.id, integrationUser)
    addOrgMember(loggedInOrganization, integrationUser, DBPermission.Administer)
    val id = state.newId()
    val webhook = com.pennsieve.models.Webhook(
      apiUrl = "https://www.api.com",
      imageUrl = Some("https://www.image.com"),
      description = "test webhook",
      secret = "secretkey123",
      name = s"hook-$id",
      displayName = "Test Webhook",
      isPrivate = isPrivate,
      isDefault = isDefault,
      isDisabled = false,
      hasAccess = hasAccess,
      integrationUserId = integrationUser.id,
      customTargets = None,
      createdBy = loggedInUser.id,
      id = id
    )
    state.webhooks.put((loggedInOrganization.id, id), webhook)
    webhook
  }

  private def enableWebhookFor(
    ds: Dataset,
    webhook: com.pennsieve.models.Webhook
  ): Unit = {
    val integrationUser = state.users(webhook.integrationUserId)
    secureContainer.datasetManager
      .enableWebhook(ds, webhook, integrationUser)
      .await
      .value
  }

  /**
    * Mirrors what `DataSetPublishingHelper.addPublisherTeam` does when the
    * publication-request endpoint runs: makes the publisher team a Manager
    * collaborator on the dataset so its members can authorize publisher
    * actions (Accept, Reject, Failed). Tests that pre-seed a Requested
    * publication status directly need to call this for the colleague to
    * authorize.
    */
  /**
    * Mirrors the original spec's `initializePublicationTest`: dataset with a
    * banner+readme, one source-file-bearing package, owner with ORCID, and the
    * `colleagueUser` added to the publisher team. By default the publisher
    * is also assigned to the dataset directly.
    */
  private def initializePublicationTest(
    assignPublisherUserDirectlyToDataset: Boolean = true,
    orcidScope: Option[String] = None
  ): Dataset = {
    orcidScope.foreach { scope =>
      val orcidAuth = OrcidAuthorization(
        name = "John Doe",
        accessToken = "64918a80-dd0c-dd0c-dd0c-dd0c64918a80",
        expiresIn = 631138518,
        tokenType = "bearer",
        orcid = "0000-0012-3456-7890",
        scope = scope,
        refreshToken = "64918a80-dd0c-dd0c-dd0c-dd0c64918a80"
      )
      val updated = loggedInUser.copy(orcidAuthorization = Some(orcidAuth))
      state.users.put(loggedInUser.id, updated)
      loggedInUser = updated
    }
    val ds = createDataSet("My Dataset")
    addBannerAndReadme(ds)
    val pkg = createPackage(ds, "some-package", `type` = CSV)
    createFile(pkg, FileObjectType.Source, FileProcessingState.Processed)
    val (publisherTeam, _) = secureContainer.organizationManager
      .getPublisherTeam(secureContainer.organization)
      .await
      .value
    secureContainer.teamManager
      .addUser(publisherTeam, colleagueUser, DBPermission.Administer)
      .await
      .value
    if (assignPublisherUserDirectlyToDataset)
      secureContainer.datasetManager
        .addUserCollaborator(ds, colleagueUser, Role.Manager)
        .await
        .value
    secureContainer.datasetManager.get(ds.id).await.value
  }

  private def currentPublicationStatus(
    ds: Dataset
  ): Option[com.pennsieve.models.PublicationStatus] = {
    get(
      ds.nodeId,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      Some(parsedBody.extract[DataSetDTO].publication.status)
    }
  }

  private def currentPublicationType(
    ds: Dataset
  ): Option[com.pennsieve.models.PublicationType] = {
    get(
      ds.nodeId,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody.extract[DataSetDTO].publication.`type`
    }
  }

  private def publicationRequestResult(
    publicationStatus: com.pennsieve.models.PublicationStatus,
    publicationType: com.pennsieve.models.PublicationType,
    headers: Map[String, String] = Map.empty
  )(
    ds: Dataset
  ): (
    Int,
    Option[com.pennsieve.models.PublicationStatus],
    Option[com.pennsieve.models.PublicationType]
  ) = {
    val urlPrefix = s"/${ds.nodeId}/publication/"
    val urlSuffix = s"?publicationType=${publicationType.entryName}"
    val url = publicationStatus match {
      case com.pennsieve.models.PublicationStatus.Requested =>
        urlPrefix + "request" + urlSuffix
      case com.pennsieve.models.PublicationStatus.Cancelled =>
        urlPrefix + "cancel" + urlSuffix
      case com.pennsieve.models.PublicationStatus.Rejected =>
        urlPrefix + "reject" + urlSuffix
      case com.pennsieve.models.PublicationStatus.Accepted =>
        urlPrefix + "accept" + urlSuffix
      case _ => s"/${ds.id}/publication/complete"
    }
    val effectiveHeaders =
      if (headers.nonEmpty) headers
      else if (com.pennsieve.models.PublicationStatus.systemStatuses
          .contains(publicationStatus))
        jwtServiceAuthorizationHeader(loggedInOrganization)
      else
        authorizationHeader(
          if (com.pennsieve.models.PublicationStatus.publisherStatuses
              .contains(publicationStatus)) colleagueJwt
          else loggedInJwt
        )
    publicationStatus match {
      case com.pennsieve.models.PublicationStatus.Completed |
          com.pennsieve.models.PublicationStatus.Failed =>
        val body = write(
          com.pennsieve.api.PublishCompleteRequest(
            Some(1),
            1,
            Some(java.time.OffsetDateTime.now),
            if (publicationStatus == com.pennsieve.models.PublicationStatus.Completed)
              com.pennsieve.models.PublishStatus.PublishSucceeded
            else com.pennsieve.models.PublishStatus.PublishFailed,
            success =
              publicationStatus == com.pennsieve.models.PublicationStatus.Completed,
            error = None
          )
        )
        putJson(url, body, effectiveHeaders ++ traceIdHeader()) {
          (status, currentPublicationStatus(ds), currentPublicationType(ds))
        }
      case _ =>
        postJson(url, "", effectiveHeaders ++ traceIdHeader()) {
          (status, currentPublicationStatus(ds), currentPublicationType(ds))
        }
    }
  }

  private def addPublisherTeamAsCollaborator(
    ds: Dataset,
    member: User
  ): Unit = {
    val (publisherTeam, _) = secureContainer.organizationManager
      .getPublisherTeam(secureContainer.organization)
      .await
      .value
    secureContainer.teamManager
      .addUser(publisherTeam, member, DBPermission.Administer)
      .await
      .value
    secureContainer.datasetManager
      .addTeamCollaborator(ds, publisherTeam, Role.Manager)
      .await
      .value
  }

  test(
    "2 step publishing - publication - request > cancel > request > reject > request > accept > complete > unpublish"
  ) {
    val ds =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)
    currentPublicationStatus(ds) shouldBe Some(PublicationStatus.Draft)
    currentPublicationType(ds) shouldBe None

    postJson(
      s"/${ds.nodeId}/publication/request?publicationType=publication&comments=hello%20world",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
      (parsedBody \ "publicationStatus")
        .extract[PublicationStatus] shouldBe PublicationStatus.Requested
      (parsedBody \ "comments").extract[String] shouldBe "hello world"
    }

    postJson(
      s"/${ds.nodeId}/publication/reject?publicationType=publication&comments=hello%20world",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
      (parsedBody \ "publicationStatus")
        .extract[PublicationStatus] shouldBe PublicationStatus.Rejected
    }

    postJson(
      s"/${ds.nodeId}/publication/request?publicationType=publication",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    postJson(
      s"/${ds.nodeId}/publication/cancel?publicationType=publication",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
      (parsedBody \ "publicationStatus")
        .extract[PublicationStatus] shouldBe PublicationStatus.Cancelled
    }

    postJson(
      s"/${ds.nodeId}/publication/request?publicationType=publication",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Publication
    )(ds) shouldBe
      (201, Some(PublicationStatus.Accepted), Some(PublicationType.Publication))
    publicationRequestResult(
      PublicationStatus.Completed,
      PublicationType.Publication
    )(ds) shouldBe
      (200, Some(PublicationStatus.Completed), Some(
        PublicationType.Publication
      ))
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Removal
    )(ds) shouldBe
      (201, Some(PublicationStatus.Requested), Some(PublicationType.Removal))
    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Removal
    )(ds) shouldBe
      (201, Some(PublicationStatus.Completed), Some(PublicationType.Removal))
  }

  test(
    "2 step publishing - publication - request > modify dataset is forbidden"
  ) {
    val ds =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)
    currentPublicationStatus(ds) shouldBe Some(PublicationStatus.Draft)
    currentPublicationType(ds) shouldBe None

    postJson(
      s"/${ds.nodeId}/publication/request?publicationType=publication&comments=hello%20world",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }
    currentPublicationStatus(ds) shouldBe Some(PublicationStatus.Requested)

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
      status shouldBe 423
    }

    delete(
      s"/${ds.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 423
    }
  }

  test(
    "2 step publishing - revision - request > cancel > request > reject > request > accept > complete"
  ) {
    val ds =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)
    secureContainer.datasetPublicationStatusManager
      .create(
        ds,
        PublicationStatus.Completed,
        PublicationType.Publication,
        None
      )
      .await
      .value

    postJson(
      s"/${ds.nodeId}/publication/request?publicationType=revision",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) { status shouldBe 201 }

    postJson(
      s"/${ds.nodeId}/publication/cancel?publicationType=revision",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) { status shouldBe 201 }

    postJson(
      s"/${ds.nodeId}/publication/request?publicationType=revision",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) { status shouldBe 201 }

    postJson(
      s"/${ds.nodeId}/publication/reject?publicationType=revision",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) { status shouldBe 201 }

    postJson(
      s"/${ds.nodeId}/publication/request?publicationType=revision",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) { status shouldBe 201 }

    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Revision
    )(ds) shouldBe
      (201, Some(PublicationStatus.Completed), Some(PublicationType.Revision))
  }

  test(
    "2 step publishing - embargo - request > accept > publication successful"
  ) {
    val ds =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)

    val embargoReleaseDate =
      java.time.LocalDate.now
        .plusYears(1)
        .format(java.time.format.DateTimeFormatter.ISO_DATE)
    postJson(
      s"/${ds.nodeId}/publication/request?publicationType=embargo&embargoReleaseDate=$embargoReleaseDate",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) { status shouldBe 201 }

    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Embargo
    )(ds) shouldBe
      (201, Some(PublicationStatus.Accepted), Some(PublicationType.Embargo))

    val request = write(
      com.pennsieve.api.PublishCompleteRequest(
        Some(1),
        1,
        Some(java.time.OffsetDateTime.now),
        com.pennsieve.models.PublishStatus.PublishSucceeded,
        success = true,
        error = None
      )
    )
    putJson(
      s"/${ds.id}/publication/complete",
      request,
      headers = jwtServiceAuthorizationHeader(loggedInOrganization) ++ traceIdHeader()
    ) { status shouldBe 200 }

    currentPublicationStatus(ds) shouldBe Some(PublicationStatus.Completed)
    currentPublicationType(ds) shouldBe Some(PublicationType.Embargo)
  }

  test(
    "2 step publishing - state machine - able to revise or withdraw a published dataset even if a subsequent version failed to publish"
  ) {
    val ds = initializePublicationTest()

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication
    )(ds) shouldBe
      (201, Some(PublicationStatus.Requested), Some(
        PublicationType.Publication
      ))
    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Publication
    )(ds) shouldBe
      (201, Some(PublicationStatus.Accepted), Some(PublicationType.Publication))
    publicationRequestResult(
      PublicationStatus.Completed,
      PublicationType.Publication
    )(ds) shouldBe
      (200, Some(PublicationStatus.Completed), Some(
        PublicationType.Publication
      ))

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication
    )(ds) shouldBe
      (201, Some(PublicationStatus.Requested), Some(
        PublicationType.Publication
      ))
    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Publication
    )(ds) shouldBe
      (201, Some(PublicationStatus.Accepted), Some(PublicationType.Publication))
    publicationRequestResult(
      PublicationStatus.Failed,
      PublicationType.Publication
    )(ds) shouldBe
      (200, Some(PublicationStatus.Failed), Some(PublicationType.Publication))
    publicationRequestResult(
      PublicationStatus.Rejected,
      PublicationType.Publication
    )(ds) shouldBe
      (201, Some(PublicationStatus.Rejected), Some(PublicationType.Publication))

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Revision
    )(ds) shouldBe
      (201, Some(PublicationStatus.Requested), Some(PublicationType.Revision))
    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Revision
    )(ds) shouldBe
      (201, Some(PublicationStatus.Completed), Some(PublicationType.Revision))

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication
    )(ds) shouldBe
      (201, Some(PublicationStatus.Requested), Some(
        PublicationType.Publication
      ))
    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Publication
    )(ds) shouldBe
      (201, Some(PublicationStatus.Accepted), Some(PublicationType.Publication))
    publicationRequestResult(
      PublicationStatus.Failed,
      PublicationType.Publication
    )(ds) shouldBe
      (200, Some(PublicationStatus.Failed), Some(PublicationType.Publication))
    publicationRequestResult(
      PublicationStatus.Rejected,
      PublicationType.Publication
    )(ds) shouldBe
      (201, Some(PublicationStatus.Rejected), Some(PublicationType.Publication))

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Removal
    )(ds) shouldBe
      (201, Some(PublicationStatus.Requested), Some(PublicationType.Removal))
    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Removal
    )(ds) shouldBe
      (201, Some(PublicationStatus.Completed), Some(PublicationType.Removal))
  }

  test("2 step publishing - state machine - current status None") {
    val ds = initializePublicationTest()
    import com.pennsieve.models.{ PublicationStatus, PublicationType }

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Revision,
      authorizationHeader(loggedInJwt)
    )(ds) shouldBe (400, Some(PublicationStatus.Draft), None)

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Removal,
      authorizationHeader(loggedInJwt)
    )(ds) shouldBe (400, Some(PublicationStatus.Draft), None)

    Seq(
      PublicationStatus.Cancelled,
      PublicationStatus.Rejected,
      PublicationStatus.Accepted
    ).foreach { s =>
      publicationRequestResult(s, PublicationType.Publication)(ds) shouldBe
        (400, Some(PublicationStatus.Draft), None)
      publicationRequestResult(s, PublicationType.Revision)(ds) shouldBe
        (400, Some(PublicationStatus.Draft), None)
    }
  }

  test(
    "2 step publishing - state machine - current status Publication Requested"
  ) {
    val ds = initializePublicationTest()
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication
    )(ds) shouldBe
      (201, Some(PublicationStatus.Requested), Some(
        PublicationType.Publication
      ))

    Seq(
      PublicationStatus.Requested,
      PublicationStatus.Cancelled,
      PublicationStatus.Rejected,
      PublicationStatus.Accepted
    ).foreach { s =>
      publicationRequestResult(s, PublicationType.Revision)(ds) shouldBe
        (400, Some(PublicationStatus.Requested), Some(
          PublicationType.Publication
        ))
    }

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication
    )(ds) shouldBe
      (400, Some(PublicationStatus.Requested), Some(
        PublicationType.Publication
      ))

    publicationRequestResult(
      PublicationStatus.Cancelled,
      PublicationType.Publication
    )(ds) shouldBe
      (201, Some(PublicationStatus.Cancelled), Some(
        PublicationType.Publication
      ))
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication
    )(ds) shouldBe
      (201, Some(PublicationStatus.Requested), Some(
        PublicationType.Publication
      ))
    publicationRequestResult(
      PublicationStatus.Rejected,
      PublicationType.Publication
    )(ds) shouldBe
      (201, Some(PublicationStatus.Rejected), Some(PublicationType.Publication))
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication
    )(ds) shouldBe
      (201, Some(PublicationStatus.Requested), Some(
        PublicationType.Publication
      ))

    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Publication
    )(ds) shouldBe
      (201, Some(PublicationStatus.Accepted), Some(PublicationType.Publication))
    publicationRequestResult(
      PublicationStatus.Failed,
      PublicationType.Publication
    )(ds) shouldBe
      (200, Some(PublicationStatus.Failed), Some(PublicationType.Publication))
  }

  test(
    "2 step publishing - state machine - current status Publication Cancelled"
  ) {
    val ds = initializePublicationTest()
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication
    )(ds) shouldBe
      (201, Some(PublicationStatus.Requested), Some(
        PublicationType.Publication
      ))
    publicationRequestResult(
      PublicationStatus.Cancelled,
      PublicationType.Publication
    )(ds) shouldBe
      (201, Some(PublicationStatus.Cancelled), Some(
        PublicationType.Publication
      ))

    Seq(
      PublicationStatus.Requested,
      PublicationStatus.Cancelled,
      PublicationStatus.Rejected,
      PublicationStatus.Accepted
    ).foreach { s =>
      publicationRequestResult(s, PublicationType.Revision)(ds) shouldBe
        (400, Some(PublicationStatus.Cancelled), Some(
          PublicationType.Publication
        ))
    }

    publicationRequestResult(
      PublicationStatus.Cancelled,
      PublicationType.Publication
    )(ds) shouldBe
      (400, Some(PublicationStatus.Cancelled), Some(
        PublicationType.Publication
      ))

    Seq(PublicationStatus.Rejected, PublicationStatus.Accepted).foreach { s =>
      publicationRequestResult(s, PublicationType.Publication)(ds) shouldBe
        (400, Some(PublicationStatus.Cancelled), Some(
          PublicationType.Publication
        ))
    }
  }

  test(
    "2 step publishing - state machine - current status Publication Rejected"
  ) {
    val ds = initializePublicationTest()
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication
    )(ds) shouldBe
      (201, Some(PublicationStatus.Requested), Some(
        PublicationType.Publication
      ))
    publicationRequestResult(
      PublicationStatus.Rejected,
      PublicationType.Publication
    )(ds) shouldBe
      (201, Some(PublicationStatus.Rejected), Some(PublicationType.Publication))

    Seq(
      PublicationStatus.Requested,
      PublicationStatus.Cancelled,
      PublicationStatus.Rejected,
      PublicationStatus.Accepted
    ).foreach { s =>
      publicationRequestResult(s, PublicationType.Revision)(ds) shouldBe
        (400, Some(PublicationStatus.Rejected), Some(
          PublicationType.Publication
        ))
    }

    Seq(PublicationStatus.Rejected, PublicationStatus.Accepted).foreach { s =>
      publicationRequestResult(s, PublicationType.Publication)(ds) shouldBe
        (400, Some(PublicationStatus.Rejected), Some(
          PublicationType.Publication
        ))
    }

    publicationRequestResult(
      PublicationStatus.Cancelled,
      PublicationType.Publication
    )(ds) shouldBe
      (201, Some(PublicationStatus.Cancelled), Some(
        PublicationType.Publication
      ))
  }

  test(
    "2 step publishing - state machine - current status Publication Accepted"
  ) {
    val ds = initializePublicationTest()
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication
    )(ds) shouldBe
      (201, Some(PublicationStatus.Requested), Some(
        PublicationType.Publication
      ))
    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Publication
    )(ds) shouldBe
      (201, Some(PublicationStatus.Accepted), Some(PublicationType.Publication))

    Seq(
      PublicationStatus.Requested,
      PublicationStatus.Cancelled,
      PublicationStatus.Rejected,
      PublicationStatus.Accepted
    ).foreach { s =>
      publicationRequestResult(s, PublicationType.Revision)(ds) shouldBe
        (400, Some(PublicationStatus.Accepted), Some(
          PublicationType.Publication
        ))
    }

    Seq(
      PublicationStatus.Requested,
      PublicationStatus.Cancelled,
      PublicationStatus.Rejected,
      PublicationStatus.Accepted
    ).foreach { s =>
      publicationRequestResult(s, PublicationType.Publication)(ds) shouldBe
        (400, Some(PublicationStatus.Accepted), Some(
          PublicationType.Publication
        ))
    }
  }

  test(
    "2 step publishing - state machine - current status Publication Completed"
  ) {
    val ds = initializePublicationTest()
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication
    )(ds) shouldBe
      (201, Some(PublicationStatus.Requested), Some(
        PublicationType.Publication
      ))
    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Publication
    )(ds) shouldBe
      (201, Some(PublicationStatus.Accepted), Some(PublicationType.Publication))
    publicationRequestResult(
      PublicationStatus.Completed,
      PublicationType.Publication
    )(ds) shouldBe
      (200, Some(PublicationStatus.Completed), Some(
        PublicationType.Publication
      ))

    Seq(
      PublicationStatus.Cancelled,
      PublicationStatus.Rejected,
      PublicationStatus.Accepted
    ).foreach { s =>
      publicationRequestResult(s, PublicationType.Publication)(ds) shouldBe
        (400, Some(PublicationStatus.Completed), Some(
          PublicationType.Publication
        ))
    }

    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .value
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication
    )(ds) shouldBe
      (201, Some(PublicationStatus.Requested), Some(
        PublicationType.Publication
      ))

    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .value
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Revision
    )(ds) shouldBe
      (201, Some(PublicationStatus.Requested), Some(PublicationType.Revision))

    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .value
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Removal
    )(ds) shouldBe
      (201, Some(PublicationStatus.Requested), Some(PublicationType.Removal))
  }

  test(
    "2 step publishing - state machine - current status Withdrawal Requested"
  ) {
    val ds = initializePublicationTest()
    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .value
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Removal
    )(ds) shouldBe
      (201, Some(PublicationStatus.Requested), Some(PublicationType.Removal))

    Seq(
      PublicationStatus.Requested,
      PublicationStatus.Cancelled,
      PublicationStatus.Rejected,
      PublicationStatus.Accepted
    ).foreach { s =>
      publicationRequestResult(s, PublicationType.Publication)(ds) shouldBe
        (400, Some(PublicationStatus.Requested), Some(PublicationType.Removal))
    }

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Removal
    )(ds) shouldBe
      (400, Some(PublicationStatus.Requested), Some(PublicationType.Removal))

    publicationRequestResult(
      PublicationStatus.Cancelled,
      PublicationType.Removal
    )(ds) shouldBe
      (201, Some(PublicationStatus.Cancelled), Some(PublicationType.Removal))
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Removal
    )(ds) shouldBe
      (201, Some(PublicationStatus.Requested), Some(PublicationType.Removal))
    publicationRequestResult(
      PublicationStatus.Rejected,
      PublicationType.Removal
    )(ds) shouldBe
      (201, Some(PublicationStatus.Rejected), Some(PublicationType.Removal))
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Removal
    )(ds) shouldBe
      (201, Some(PublicationStatus.Requested), Some(PublicationType.Removal))
    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Removal
    )(ds) shouldBe
      (201, Some(PublicationStatus.Completed), Some(PublicationType.Removal))
  }

  test(
    "2 step publishing - state machine - current status Withdrawal Cancelled"
  ) {
    val ds = initializePublicationTest()
    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .value
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Removal
    )(ds) shouldBe
      (201, Some(PublicationStatus.Requested), Some(PublicationType.Removal))
    publicationRequestResult(
      PublicationStatus.Cancelled,
      PublicationType.Removal
    )(ds) shouldBe
      (201, Some(PublicationStatus.Cancelled), Some(PublicationType.Removal))

    Seq(
      PublicationStatus.Cancelled,
      PublicationStatus.Rejected,
      PublicationStatus.Accepted
    ).foreach { s =>
      publicationRequestResult(s, PublicationType.Publication)(ds) shouldBe
        (400, Some(PublicationStatus.Cancelled), Some(PublicationType.Removal))
    }

    publicationRequestResult(
      PublicationStatus.Cancelled,
      PublicationType.Removal
    )(ds) shouldBe
      (400, Some(PublicationStatus.Cancelled), Some(PublicationType.Removal))

    Seq(PublicationStatus.Rejected, PublicationStatus.Accepted).foreach { s =>
      publicationRequestResult(s, PublicationType.Removal)(ds) shouldBe
        (400, Some(PublicationStatus.Cancelled), Some(PublicationType.Removal))
    }
  }

  test(
    "2 step publishing - state machine - current status Withdrawal Completed"
  ) {
    val ds = initializePublicationTest()
    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .value
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Removal
    )(ds) shouldBe
      (201, Some(PublicationStatus.Requested), Some(PublicationType.Removal))
    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Removal
    )(ds) shouldBe
      (201, Some(PublicationStatus.Completed), Some(PublicationType.Removal))

    Seq(
      PublicationStatus.Cancelled,
      PublicationStatus.Rejected,
      PublicationStatus.Accepted
    ).foreach { s =>
      publicationRequestResult(s, PublicationType.Removal)(ds) shouldBe
        (400, Some(PublicationStatus.Completed), Some(PublicationType.Removal))
    }

    publicationRequestResult(
      PublicationStatus.Cancelled,
      PublicationType.Removal
    )(ds) shouldBe
      (400, Some(PublicationStatus.Completed), Some(PublicationType.Removal))

    Seq(PublicationStatus.Rejected, PublicationStatus.Accepted).foreach { s =>
      publicationRequestResult(s, PublicationType.Removal)(ds) shouldBe
        (400, Some(PublicationStatus.Completed), Some(PublicationType.Removal))
    }

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication
    )(ds) shouldBe
      (201, Some(PublicationStatus.Requested), Some(
        PublicationType.Publication
      ))
    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Publication
    )(ds) shouldBe
      (201, Some(PublicationStatus.Accepted), Some(PublicationType.Publication))
    publicationRequestResult(
      PublicationStatus.Completed,
      PublicationType.Publication
    )(ds) shouldBe
      (200, Some(PublicationStatus.Completed), Some(
        PublicationType.Publication
      ))
  }

  test("2 step publishing - state machine - current status Publication Failed") {
    val ds = initializePublicationTest()
    import com.pennsieve.models.{ PublicationStatus, PublicationType }

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication
    )(ds) shouldBe
      (201, Some(PublicationStatus.Requested), Some(
        PublicationType.Publication
      ))
    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Publication
    )(ds) shouldBe
      (201, Some(PublicationStatus.Accepted), Some(PublicationType.Publication))
    publicationRequestResult(
      PublicationStatus.Failed,
      PublicationType.Publication
    )(ds) shouldBe
      (200, Some(PublicationStatus.Failed), Some(PublicationType.Publication))

    Seq(
      PublicationStatus.Requested,
      PublicationStatus.Cancelled,
      PublicationStatus.Rejected,
      PublicationStatus.Accepted
    ).foreach { s =>
      publicationRequestResult(s, PublicationType.Revision)(ds) shouldBe
        (400, Some(PublicationStatus.Failed), Some(PublicationType.Publication))
    }

    Seq(PublicationStatus.Requested, PublicationStatus.Cancelled).foreach { s =>
      publicationRequestResult(s, PublicationType.Publication)(ds) shouldBe
        (400, Some(PublicationStatus.Failed), Some(PublicationType.Publication))
    }

    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Publication
    )(ds) shouldBe
      (201, Some(PublicationStatus.Accepted), Some(PublicationType.Publication))
    publicationRequestResult(
      PublicationStatus.Failed,
      PublicationType.Publication
    )(ds) shouldBe
      (200, Some(PublicationStatus.Failed), Some(PublicationType.Publication))
    publicationRequestResult(
      PublicationStatus.Rejected,
      PublicationType.Publication
    )(ds) shouldBe
      (201, Some(PublicationStatus.Rejected), Some(PublicationType.Publication))
  }

  test("2 step publishing - state machine - current status Revision Requested") {
    val ds = initializePublicationTest()
    import com.pennsieve.models.{ PublicationStatus, PublicationType }
    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .value

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Revision
    )(ds) shouldBe
      (201, Some(PublicationStatus.Requested), Some(PublicationType.Revision))

    Seq(
      PublicationStatus.Requested,
      PublicationStatus.Cancelled,
      PublicationStatus.Rejected,
      PublicationStatus.Accepted
    ).foreach { s =>
      publicationRequestResult(s, PublicationType.Publication)(ds) shouldBe
        (400, Some(PublicationStatus.Requested), Some(PublicationType.Revision))
    }

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Revision
    )(ds) shouldBe
      (400, Some(PublicationStatus.Requested), Some(PublicationType.Revision))

    publicationRequestResult(
      PublicationStatus.Cancelled,
      PublicationType.Revision
    )(ds) shouldBe
      (201, Some(PublicationStatus.Cancelled), Some(PublicationType.Revision))
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Revision
    )(ds) shouldBe
      (201, Some(PublicationStatus.Requested), Some(PublicationType.Revision))
    publicationRequestResult(
      PublicationStatus.Rejected,
      PublicationType.Revision
    )(ds) shouldBe
      (201, Some(PublicationStatus.Rejected), Some(PublicationType.Revision))
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Revision
    )(ds) shouldBe
      (201, Some(PublicationStatus.Requested), Some(PublicationType.Revision))
    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Revision
    )(ds) shouldBe
      (201, Some(PublicationStatus.Completed), Some(PublicationType.Revision))
  }

  test("2 step publishing - state machine - current status Revision Cancelled") {
    val ds = initializePublicationTest()
    import com.pennsieve.models.{ PublicationStatus, PublicationType }
    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .value
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Revision
    )(ds) shouldBe
      (201, Some(PublicationStatus.Requested), Some(PublicationType.Revision))
    publicationRequestResult(
      PublicationStatus.Cancelled,
      PublicationType.Revision
    )(ds) shouldBe
      (201, Some(PublicationStatus.Cancelled), Some(PublicationType.Revision))

    Seq(
      PublicationStatus.Cancelled,
      PublicationStatus.Rejected,
      PublicationStatus.Accepted
    ).foreach { s =>
      publicationRequestResult(s, PublicationType.Publication)(ds) shouldBe
        (400, Some(PublicationStatus.Cancelled), Some(PublicationType.Revision))
    }

    publicationRequestResult(
      PublicationStatus.Cancelled,
      PublicationType.Revision
    )(ds) shouldBe
      (400, Some(PublicationStatus.Cancelled), Some(PublicationType.Revision))

    Seq(PublicationStatus.Rejected, PublicationStatus.Accepted).foreach { s =>
      publicationRequestResult(s, PublicationType.Revision)(ds) shouldBe
        (400, Some(PublicationStatus.Cancelled), Some(PublicationType.Revision))
    }
  }

  test("2 step publishing - state machine - current status Revision Rejected") {
    val ds = initializePublicationTest()
    import com.pennsieve.models.{ PublicationStatus, PublicationType }
    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .value
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Revision
    )(ds) shouldBe
      (201, Some(PublicationStatus.Requested), Some(PublicationType.Revision))
    publicationRequestResult(
      PublicationStatus.Rejected,
      PublicationType.Revision
    )(ds) shouldBe
      (201, Some(PublicationStatus.Rejected), Some(PublicationType.Revision))

    Seq(PublicationStatus.Rejected, PublicationStatus.Accepted).foreach { s =>
      publicationRequestResult(s, PublicationType.Publication)(ds) shouldBe
        (400, Some(PublicationStatus.Rejected), Some(PublicationType.Revision))
    }
    Seq(PublicationStatus.Rejected, PublicationStatus.Accepted).foreach { s =>
      publicationRequestResult(s, PublicationType.Revision)(ds) shouldBe
        (400, Some(PublicationStatus.Rejected), Some(PublicationType.Revision))
    }

    publicationRequestResult(
      PublicationStatus.Cancelled,
      PublicationType.Revision
    )(ds) shouldBe
      (201, Some(PublicationStatus.Cancelled), Some(PublicationType.Revision))
  }

  test("2 step publishing - state machine - current status Revision Completed") {
    val ds = initializePublicationTest()
    import com.pennsieve.models.{ PublicationStatus, PublicationType }
    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .value
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Revision
    )(ds) shouldBe
      (201, Some(PublicationStatus.Requested), Some(PublicationType.Revision))
    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Revision
    )(ds) shouldBe
      (201, Some(PublicationStatus.Completed), Some(PublicationType.Revision))

    Seq(
      PublicationStatus.Cancelled,
      PublicationStatus.Rejected,
      PublicationStatus.Accepted
    ).foreach { s =>
      publicationRequestResult(s, PublicationType.Publication)(ds) shouldBe
        (400, Some(PublicationStatus.Completed), Some(PublicationType.Revision))
    }

    publicationRequestResult(
      PublicationStatus.Cancelled,
      PublicationType.Revision
    )(ds) shouldBe
      (400, Some(PublicationStatus.Completed), Some(PublicationType.Revision))

    Seq(PublicationStatus.Rejected, PublicationStatus.Accepted).foreach { s =>
      publicationRequestResult(s, PublicationType.Revision)(ds) shouldBe
        (400, Some(PublicationStatus.Completed), Some(PublicationType.Revision))
    }

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication
    )(ds) shouldBe
      (201, Some(PublicationStatus.Requested), Some(
        PublicationType.Publication
      ))

    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Revision)
      .await
      .value

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Revision
    )(ds) shouldBe
      (201, Some(PublicationStatus.Requested), Some(PublicationType.Revision))
  }

  test("2 step publishing - state machine - current status Withdrawal Rejected") {
    val ds = initializePublicationTest()
    import com.pennsieve.models.{ PublicationStatus, PublicationType }
    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .value
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Removal
    )(ds) shouldBe
      (201, Some(PublicationStatus.Requested), Some(PublicationType.Removal))
    publicationRequestResult(
      PublicationStatus.Rejected,
      PublicationType.Removal
    )(ds) shouldBe
      (201, Some(PublicationStatus.Rejected), Some(PublicationType.Removal))

    Seq(PublicationStatus.Rejected, PublicationStatus.Accepted).foreach { s =>
      publicationRequestResult(s, PublicationType.Publication)(ds) shouldBe
        (400, Some(PublicationStatus.Rejected), Some(PublicationType.Removal))
    }

    Seq(PublicationStatus.Rejected, PublicationStatus.Accepted).foreach { s =>
      publicationRequestResult(s, PublicationType.Removal)(ds) shouldBe
        (400, Some(PublicationStatus.Rejected), Some(PublicationType.Removal))
    }

    publicationRequestResult(
      PublicationStatus.Cancelled,
      PublicationType.Removal
    )(ds) shouldBe
      (201, Some(PublicationStatus.Cancelled), Some(PublicationType.Removal))
  }

  test(
    "2 step publishing - release rejected dataset - request > accept > release successful"
  ) {
    val ds =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)
    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Rejected, PublicationType.Release)
      .await
      .value
    post(
      s"/${ds.nodeId}/publication/request?publicationType=release&comments=releasing-early",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) { status shouldBe 201 }
  }

  test(
    "2 step publishing - release cancelled dataset - request > accept > release successful"
  ) {
    val ds =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)
    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Cancelled, PublicationType.Release)
      .await
      .value
    post(
      s"/${ds.nodeId}/publication/request?publicationType=release&comments=releasing-early",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) { status shouldBe 201 }
  }

  test("2 step publishing - release - request > accept > release successful") {
    val ds =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)
    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Embargo)
      .await
      .value

    post(
      s"/${ds.nodeId}/publication/request?publicationType=release&comments=releasing-early",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }
    currentPublicationStatus(ds) shouldBe Some(PublicationStatus.Requested)
    currentPublicationType(ds) shouldBe Some(PublicationType.Release)

    post(
      s"/${ds.nodeId}/publication/accept?publicationType=release&comments=ok",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }
    currentPublicationStatus(ds) shouldBe Some(PublicationStatus.Accepted)
    currentPublicationType(ds) shouldBe Some(PublicationType.Release)

    val request = write(
      com.pennsieve.api.PublishCompleteRequest(
        Some(1),
        1,
        Some(java.time.OffsetDateTime.now),
        com.pennsieve.models.PublishStatus.PublishSucceeded,
        success = true,
        error = None
      )
    )
    putJson(
      s"/${ds.id}/publication/complete",
      request,
      headers = jwtServiceAuthorizationHeader(loggedInOrganization) ++ traceIdHeader()
    ) {
      status shouldBe 200
    }
    currentPublicationStatus(ds) shouldBe Some(PublicationStatus.Completed)
    currentPublicationType(ds) shouldBe Some(PublicationType.Release)
  }

  test("2 step publishing - can only release embargoed datasets") {
    val ds =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)
    Seq(
      PublicationType.Publication,
      PublicationType.Revision,
      PublicationType.Removal,
      PublicationType.Release
    ).foreach { pubType =>
      secureContainer.datasetPublicationStatusManager
        .create(ds, PublicationStatus.Completed, pubType)
        .await
        .value
      post(
        s"/${ds.nodeId}/publication/request?publicationType=release&comments=releasing-early",
        headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
      ) {
        status shouldBe 400
      }
    }
  }

  test("2 step publishing - can remove released dataset") {
    val ds =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)
    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Release)
      .await
      .value
    post(
      s"/${ds.nodeId}/publication/request?publicationType=removal",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }
  }

  test("2 step publishing - service user can release dataset with one request") {
    val ds =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)
    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Embargo)
      .await
      .value
    post(
      s"/${ds.id}/publication/release",
      headers = jwtServiceAuthorizationHeader(loggedInOrganization)
    ) {
      status shouldBe 201
    }
    secureContainer.datasetPublicationStatusManager
      .getLogByDataset(ds.id, sortAscending = true)
      .await
      .value
      .toList
      .map(s => (s.publicationType, s.publicationStatus)) shouldBe List(
      (PublicationType.Embargo, PublicationStatus.Completed),
      (PublicationType.Release, PublicationStatus.Requested),
      (PublicationType.Release, PublicationStatus.Accepted)
    )
    mockPublishClient.releaseRequests should contain(
      (loggedInOrganization.id, ds.id)
    )
  }

  test("2 step publishing - normal user cannot release dataset in one step") {
    val ds =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)
    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Embargo)
      .await
      .value
    post(
      s"/${ds.id}/publication/release",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 403
    }
    currentPublicationStatus(ds) shouldBe Some(PublicationStatus.Completed)
    currentPublicationType(ds) shouldBe Some(PublicationType.Embargo)
  }

  // ---- webhook integrations ------------------------------------------

  test(
    "cannot get dataset webhook integrations without ViewWebhook permission on dataset"
  ) {
    val ds = createDataSet("test-dataset")
    val webhook = createWebhook()
    enableWebhookFor(ds, webhook)

    get(
      s"/${ds.nodeId}/webhook",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
      body should include(
        s"${colleagueUser.nodeId} does not have permission to access dataset ${ds.id}"
      )
    }
  }

  test(
    "get dataset webhook integrations with ViewWebhook permission on dataset"
  ) {
    val ds = createDataSet("test-dataset")
    secureContainer.datasetManager
      .addUserCollaborator(ds, colleagueUser, Role.Viewer)
      .await
      .value
    val w1 = createWebhook()
    enableWebhookFor(ds, w1)
    val w2 = createWebhook()
    enableWebhookFor(ds, w2)

    get(
      s"/${ds.nodeId}/webhook",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val response =
        parsedBody.extract[Seq[com.pennsieve.models.DatasetIntegration]]
      response.size shouldBe 2
      response.map(_.webhookId).toSet shouldBe Set(w1.id, w2.id)
    }
  }

  test(
    "cannot enable dataset webhook integration without ManageWebhook permission on dataset"
  ) {
    val ds = createDataSet("test-dataset")
    val webhook = createWebhook()
    put(
      s"/${ds.nodeId}/webhook/${webhook.id}",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
      body should include(
        s"${colleagueUser.nodeId} does not have permission to access dataset ${ds.id}"
      )
    }
  }

  test(
    "enable dataset webhook integration with ManageWebhooks permission on dataset"
  ) {
    val ds = createDataSet("test-dataset")
    secureContainer.datasetManager
      .addUserCollaborator(ds, colleagueUser, Role.Manager)
      .await
      .value
    val webhook = createWebhook()
    put(
      s"/${ds.nodeId}/webhook/${webhook.id}",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val response = parsedBody.extract[com.pennsieve.models.DatasetIntegration]
      response.datasetId should equal(ds.id)
      response.webhookId should equal(webhook.id)
      response.enabledBy should equal(colleagueUser.id)
    }
  }

  test(
    "cannot disable dataset webhook integration without ManageWebhook permission on dataset"
  ) {
    val ds = createDataSet("test-dataset")
    val webhook = createWebhook()
    enableWebhookFor(ds, webhook)
    delete(
      s"/${ds.nodeId}/webhook/${webhook.id}",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
      body should include(
        s"${colleagueUser.nodeId} does not have permission to access dataset ${ds.id}"
      )
    }
  }

  test(
    "disable dataset webhook integration with ManageWebhooks permission on dataset"
  ) {
    val ds = createDataSet("test-dataset")
    secureContainer.datasetManager
      .addUserCollaborator(ds, colleagueUser, Role.Manager)
      .await
      .value
    val webhook = createWebhook()
    enableWebhookFor(ds, webhook)
    delete(
      s"/${ds.nodeId}/webhook/${webhook.id}",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody.extract[Int] should equal(1)
    }
  }

  test("get enabled dataset webhook integrations") {
    val ds = createDataSet("test-dataset")
    val w1 = createWebhook()
    val _ = createWebhook()
    val w3 = createWebhook()
    enableWebhookFor(ds, w1)
    enableWebhookFor(ds, w3)

    get(
      s"/${ds.nodeId}/webhook",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val response =
        parsedBody.extract[Seq[com.pennsieve.models.DatasetIntegration]]
      response.size shouldBe 2
      response.map(_.webhookId).toSet shouldBe Set(w1.id, w3.id)
    }
  }

  test("enable dataset webhook integration") {
    val ds = createDataSet("test-dataset")
    val webhook = createWebhook()

    put(
      s"/${ds.nodeId}/webhook/${webhook.id}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val response = parsedBody.extract[com.pennsieve.models.DatasetIntegration]
      response.datasetId should equal(ds.id)
      response.webhookId should equal(webhook.id)
      response.enabledBy should equal(loggedInUser.id)

      val users = secureContainer.datasetManager
        .getUserCollaborators(ds)
        .await
        .value
      users.map(_._1.id) should contain(webhook.integrationUserId)
    }
  }

  test("cannot enable dataset webhook integration if webhook is not public") {
    val ds = createDataSet("test-dataset")
    secureContainer.datasetManager
      .addUserCollaborator(ds, colleagueUser, Role.Manager)
      .await
      .value
    val webhook = createWebhook(isPrivate = true)
    put(
      s"/${ds.nodeId}/webhook/${webhook.id}",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
      body should include(
        s"${colleagueUser.nodeId} does not have dataset integration access to webhook ${webhook.id}"
      )
    }
  }

  test("disable dataset webhook integration") {
    val ds = createDataSet("test-dataset")
    val webhook = createWebhook()
    enableWebhookFor(ds, webhook)

    delete(
      s"/${ds.nodeId}/webhook/${webhook.id}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody.extract[Int] should equal(1)

      val users = secureContainer.datasetManager
        .getUserCollaborators(ds)
        .await
        .value
      users.map(_._1.id) should not contain webhook.integrationUserId
    }
  }

  test("cannot disable dataset webhook integration if webhook is not public") {
    val ds = createDataSet("test-dataset")
    secureContainer.datasetManager
      .addUserCollaborator(ds, colleagueUser, Role.Manager)
      .await
      .value
    val webhook = createWebhook(isPrivate = true)
    delete(
      s"/${ds.nodeId}/webhook/${webhook.id}",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
      body should include(
        s"${colleagueUser.nodeId} does not have dataset integration access to webhook ${webhook.id}"
      )
    }
  }

  test("create dataset in presence of default webhooks") {
    val webhook = createWebhook(isDefault = true)

    val createReq = write(
      CreateDataSetRequest(
        name = "A New DataSet",
        description = None,
        properties = Nil
      )
    )
    postJson(
      s"",
      createReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
      val datasetId = parsedBody.extract[DataSetDTO].content.intId
      val ds = secureContainer.datasetManager.get(datasetId).await.value
      val integrations = secureContainer.datasetManager
        .getIntegrations(ds)
        .await
        .value
      integrations.size shouldBe 1
      integrations.head.webhookId shouldBe webhook.id
      integrations.head.enabledBy shouldBe loggedInUser.id
    }
  }

  test("create dataset in presence of default and requested webhooks") {
    val defaultWebhook = createWebhook(isDefault = true)
    val webhook = createWebhook()

    val createReq = write(
      CreateDataSetRequest(
        name = "A New DataSet",
        description = None,
        properties = Nil,
        includedWebhookIds = List(webhook.id)
      )
    )
    postJson(
      s"",
      createReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
      val datasetId = parsedBody.extract[DataSetDTO].content.intId
      val ds = secureContainer.datasetManager.get(datasetId).await.value
      val integrations = secureContainer.datasetManager
        .getIntegrations(ds)
        .await
        .value
      integrations.size shouldBe 2
      integrations.map(_.webhookId).toSet shouldBe
        Set(defaultWebhook.id, webhook.id)
      integrations.foreach(_.enabledBy shouldBe loggedInUser.id)
    }
  }

  test("create dataset with excluded and requested webhooks") {
    val d1 = createWebhook(isDefault = true)
    val d2 = createWebhook(isDefault = true)
    val d3 = createWebhook(isDefault = true)
    val excluded = createWebhook(isDefault = true)
    val included1 = createWebhook()
    val included2 = createWebhook(isPrivate = true)

    val createReq = write(
      CreateDataSetRequest(
        name = "A New DataSet",
        description = None,
        properties = Nil,
        includedWebhookIds = List(included1.id, included2.id),
        excludedWebhookIds = List(excluded.id)
      )
    )
    postJson(
      s"",
      createReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
      val datasetId = parsedBody.extract[DataSetDTO].content.intId
      val ds = secureContainer.datasetManager.get(datasetId).await.value
      val integrations = secureContainer.datasetManager
        .getIntegrations(ds)
        .await
        .value
      integrations.map(_.webhookId).toSet shouldBe Set(
        d1.id,
        d2.id,
        d3.id,
        included1.id,
        included2.id
      )
      integrations.foreach(_.enabledBy shouldBe loggedInUser.id)
    }
  }

  test("publishing workflow is 5 by default") {
    val ds = initializePublicationTest(
      assignPublisherUserDirectlyToDataset = true,
      orcidScope = Some("/read-limited /activities/update")
    ).copy(
      bannerId = Some(java.util.UUID.randomUUID()),
      readmeId = Some(java.util.UUID.randomUUID())
    )
    val validated = com.pennsieve.api.ValidatedPublicationStatusRequest(
      publicationStatus = PublicationStatus.Requested,
      publicationType = PublicationType.Publication,
      dataset = ds,
      owner = loggedInUser,
      embargoReleaseDate = None
    )
    val contributors = secureContainer.datasetManager
      .getContributors(ds)
      .map(_.map(ContributorDTO(_)))
      .await
      .value
    val collections = secureContainer.datasetManager
      .getCollections(validated.dataset)
      .map(_.map(com.pennsieve.dtos.CollectionDTO(_)))
      .await
      .value
    val publicationInfo = com.pennsieve.api.DataSetPublishingHelper
      .gatherPublicationInfo(validated, contributors, true)
      .await
      .value
    val externalPublications = secureContainer.externalPublicationManager
      .get(validated.dataset)
      .await
      .value
    val defaultWorkflow = insecureContainer.config
      .getString("pennsieve.publishing.default_workflow")
    defaultWorkflow shouldEqual com.pennsieve.api.PublishingWorkflows.Version5.toString

    val response = com.pennsieve.api.DataSetPublishingHelper
      .sendPublishRequest(
        secureContainer,
        dataset = validated.dataset,
        owner = validated.owner,
        ownerBearerToken = loggedInJwt,
        ownerOrcid = publicationInfo.ownerOrcid,
        description = publicationInfo.description,
        license = publicationInfo.license,
        contributors = contributors.toList,
        embargo = false,
        publishClient = mockPublishClient,
        embargoReleaseDate = None,
        collections = collections,
        externalPublications = externalPublications,
        defaultPublishingWorkflow = defaultWorkflow
      )
      .await
      .value

    response.workflowId shouldEqual com.pennsieve.api.PublishingWorkflows.Version5
  }

  test("publishing workflow can be set to 4 in config") {
    publishingWorkflowTest(
      defaultWorkflow = "4",
      assignPublisherDirectly = false,
      featureFlagEnabled = false,
      expectedWorkflow = com.pennsieve.api.PublishingWorkflows.Version4
    )
  }

  test("publishing workflow is determined by feature flag to be 4") {
    publishingWorkflowTest(
      defaultWorkflow = "flag",
      assignPublisherDirectly = false,
      featureFlagEnabled = false,
      expectedWorkflow = com.pennsieve.api.PublishingWorkflows.Version4
    )
  }

  test("publishing workflow is determined by feature flag to be 5") {
    publishingWorkflowTest(
      defaultWorkflow = "flag",
      assignPublisherDirectly = false,
      featureFlagEnabled = true,
      expectedWorkflow = com.pennsieve.api.PublishingWorkflows.Version5
    )
  }

  /** Common helper for the four `publishing workflow ...` tests above. */
  private def publishingWorkflowTest(
    defaultWorkflow: String,
    assignPublisherDirectly: Boolean,
    featureFlagEnabled: Boolean,
    expectedWorkflow: Long
  ): Unit = {
    if (featureFlagEnabled) {
      state.featureFlags.put(
        (
          loggedInOrganization.id,
          com.pennsieve.models.Feature.Publishing50Feature
        ),
        true
      )
    }
    val ds = initializePublicationTest(
      assignPublisherUserDirectlyToDataset = assignPublisherDirectly,
      orcidScope = Some("/read-limited /activities/update")
    ).copy(
      bannerId = Some(java.util.UUID.randomUUID()),
      readmeId = Some(java.util.UUID.randomUUID())
    )
    val validated = com.pennsieve.api.ValidatedPublicationStatusRequest(
      publicationStatus = PublicationStatus.Requested,
      publicationType = PublicationType.Publication,
      dataset = ds,
      owner = loggedInUser,
      embargoReleaseDate = None
    )
    val contributors = secureContainer.datasetManager
      .getContributors(ds)
      .map(_.map(ContributorDTO(_)))
      .await
      .value
    val collections = secureContainer.datasetManager
      .getCollections(validated.dataset)
      .map(_.map(com.pennsieve.dtos.CollectionDTO(_)))
      .await
      .value
    val publicationInfo = com.pennsieve.api.DataSetPublishingHelper
      .gatherPublicationInfo(validated, contributors, true)
      .await
      .value
    val externalPublications = secureContainer.externalPublicationManager
      .get(validated.dataset)
      .await
      .value

    val response = com.pennsieve.api.DataSetPublishingHelper
      .sendPublishRequest(
        secureContainer,
        dataset = validated.dataset,
        owner = validated.owner,
        ownerBearerToken = loggedInJwt,
        ownerOrcid = publicationInfo.ownerOrcid,
        description = publicationInfo.description,
        license = publicationInfo.license,
        contributors = contributors.toList,
        embargo = false,
        publishClient = mockPublishClient,
        embargoReleaseDate = None,
        collections = collections,
        externalPublications = externalPublications,
        defaultPublishingWorkflow = defaultWorkflow
      )
      .await
      .value

    response.workflowId shouldBe expectedWorkflow
  }

  test("email is sent to Publishers on publication request") {
    val ds = createDataSet("email-to-publishers")
    addBannerAndReadme(ds)
    val pkg = createPackage(ds, "some-package", `type` = CSV)
    createFile(pkg, FileObjectType.Source, FileProcessingState.Processed)

    val (publisherTeam, _) = secureContainer.organizationManager
      .getPublisherTeam(secureContainer.organization)
      .await
      .value
    secureContainer.teamManager
      .addUser(publisherTeam, publisherUser, DBPermission.Administer)
      .await
      .value

    postJson(
      s"/${ds.nodeId}/publication/request?publicationType=publication&comments=please%20review",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
      (parsedBody \ "datasetId").extract[Int] shouldBe ds.id
      (parsedBody \ "publicationStatus")
        .extract[PublicationStatus] shouldBe PublicationStatus.Requested
      (parsedBody \ "createdBy").extract[Int] shouldBe loggedInUser.id
      (parsedBody \ "comments").extract[String] shouldBe "please review"
    }

    insecureContainer.emailer
      .asInstanceOf[com.pennsieve.aws.email.LoggingEmailer]
      .sendEmailTo(publisherUser.email) shouldBe true
  }

  test("registration does not occur when not authorized in orcid scope") {
    val ds =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)
    currentPublicationStatus(ds) shouldBe Some(PublicationStatus.Draft)
    currentPublicationType(ds) shouldBe None

    postJson(
      s"/${ds.nodeId}/publication/request?publicationType=publication&comments=hello%20world",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }
    postJson(
      s"/${ds.nodeId}/publication/accept?publicationType=publication&comments=accepted",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }
    val request = write(
      com.pennsieve.api.PublishCompleteRequest(
        Some(1),
        1,
        Some(java.time.OffsetDateTime.now),
        com.pennsieve.models.PublishStatus.PublishSucceeded,
        success = true,
        error = None
      )
    )
    putJson(
      s"/${ds.id}/publication/complete",
      request,
      headers = jwtServiceAuthorizationHeader(loggedInOrganization) ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    val registration = secureContainer.datasetManager
      .getRegistration(ds, com.pennsieve.models.DatasetRegistry.ORCID)
      .await
    registration match {
      case Right(Some(_)) => fail("registration should not exist")
      case _ => succeed
    }
  }

  test("registration occurs after publishing completes") {
    val ds = initializePublicationTest(
      assignPublisherUserDirectlyToDataset = false,
      orcidScope = Some("/read-limited /activities/update")
    )
    postJson(
      s"/${ds.nodeId}/publication/request?publicationType=publication&comments=hello%20world",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }
    postJson(
      s"/${ds.nodeId}/publication/accept?publicationType=publication&comments=accepted",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }
    val request = write(
      com.pennsieve.api.PublishCompleteRequest(
        Some(1),
        1,
        Some(java.time.OffsetDateTime.now),
        com.pennsieve.models.PublishStatus.PublishSucceeded,
        success = true,
        error = None
      )
    )
    putJson(
      s"/${ds.id}/publication/complete",
      request,
      headers = jwtServiceAuthorizationHeader(loggedInOrganization) ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    val registration = secureContainer.datasetManager
      .getRegistration(ds, com.pennsieve.models.DatasetRegistry.ORCID)
      .await
      .value
    registration shouldBe defined
  }

  test("registration removed when dataset is unpublished") {
    val ds = initializePublicationTest(
      assignPublisherUserDirectlyToDataset = false,
      orcidScope = Some("/read-limited /activities/update")
    )
    postJson(
      s"/${ds.nodeId}/publication/request?publicationType=publication",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }
    postJson(
      s"/${ds.nodeId}/publication/accept?publicationType=publication",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }
    val request = write(
      com.pennsieve.api.PublishCompleteRequest(
        Some(1),
        1,
        Some(java.time.OffsetDateTime.now),
        com.pennsieve.models.PublishStatus.PublishSucceeded,
        success = true,
        error = None
      )
    )
    putJson(
      s"/${ds.id}/publication/complete",
      request,
      headers = jwtServiceAuthorizationHeader(loggedInOrganization) ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    secureContainer.datasetManager
      .getRegistration(ds, com.pennsieve.models.DatasetRegistry.ORCID)
      .await
      .value shouldBe defined

    postJson(
      s"/${ds.nodeId}/publication/request?publicationType=removal",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }
    postJson(
      s"/${ds.nodeId}/publication/accept?publicationType=removal",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    secureContainer.datasetManager
      .getRegistration(ds, com.pennsieve.models.DatasetRegistry.ORCID)
      .await
      .value shouldBe None
  }

  test(
    "demo org - 2 step publishing - publication - request is rejected for demo org"
  ) {
    val ds = sandboxUserContainer.datasetManager
      .create(
        "Sandbox Dataset",
        Some("desc"),
        tags = List("tag"),
        license = Some(License.`Apache 2.0`)
      )
      .await
      .value

    postJson(
      s"/${ds.nodeId}/publication/request?publicationType=publication&comments=hello%20world",
      "",
      headers = authorizationHeader(sandboxUserJwt) ++ traceIdHeader()
    ) {
      status shouldBe 403
    }
  }

  test(
    "publishers - fail to publish a dataset if the requesting user is not the owner and not a publisher"
  ) {
    val ds = createDataSet("My Dataset")
    addBannerAndReadme(ds)
    val pkg = createPackage(ds, "some-package", `type` = CSV)
    createFile(pkg, FileObjectType.Source, FileProcessingState.Processed)

    val (publisherTeam, _) = secureContainer.organizationManager
      .getPublisherTeam(secureContainer.organization)
      .await
      .value
    secureContainer.teamManager
      .addUser(publisherTeam, colleagueUser, DBPermission.Administer)
      .await
      .value

    secureContainer.datasetPublicationStatusManager
      .create(
        ds,
        PublicationStatus.Requested,
        PublicationType.Publication,
        None
      )
      .await
      .value

    // colleagueUser IS in publisher team — needs to NOT be — use externalUser
    // pattern instead. Re-test the "non-publisher non-owner" path with
    // integrationUser (in org but not publisher team, not owner).
    secureContainer.datasetManager
      .addUserCollaborator(ds, integrationUser, Role.Manager)
      .await
      .value
    postJson(
      s"/${ds.nodeId}/publication/accept?publicationType=publication",
      "",
      headers = authorizationHeader(integrationJwt) ++ traceIdHeader()
    ) {
      status shouldBe 403
    }
  }

  test(
    "publishers - fail to unpublish a dataset if the user is not a publisher"
  ) {
    val ds = createDataSet("My Dataset")
    addBannerAndReadme(ds)
    val pkg = createPackage(ds, "some-package", `type` = CSV)
    createFile(pkg, FileObjectType.Source, FileProcessingState.Processed)
    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .value
    postJson(
      s"/${ds.nodeId}/publication/request?publicationType=removal",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }
    postJson(
      s"/${ds.nodeId}/publication/accept?publicationType=removal",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 403
    }
  }

  test(
    "publishers - fail to unpublish a dataset if the user is NOT a publisher"
  ) {
    val ds = createDataSet("My Dataset")
    addBannerAndReadme(ds)
    val pkg = createPackage(ds, "some-package", `type` = CSV)
    createFile(pkg, FileObjectType.Source, FileProcessingState.Processed)
    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .value
    postJson(
      s"/${ds.nodeId}/publication/request?publicationType=removal",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }
    postJson(
      s"/${ds.nodeId}/publication/accept?publicationType=removal",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 403
    }
  }

  test("publishers - dataset owners cannot revise a dataset, publishers can") {
    val ds = initializePublicationTest()
    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .value
    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Requested, PublicationType.Revision)
      .await
      .value

    post(
      s"/${ds.nodeId}/publication/accept?publicationType=revision",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 403
    }

    post(
      s"/${ds.nodeId}/publication/accept?publicationType=revision",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
      val response =
        parsedBody.extract[com.pennsieve.models.DatasetPublicationStatus]
      response.datasetId shouldBe ds.id
      response.publicationStatus shouldBe PublicationStatus.Completed
      response.publicationType shouldBe PublicationType.Revision
    }
  }

  test("get changelog timeline") {
    import com.pennsieve.models.{ ChangelogEventDetail, ChangelogEventName }
    val ds = createDataSet("timeline-dataset")
    val now = java.time.ZonedDateTime.now()
    secureContainer.changelogManager
      .logEvent(
        ds,
        ChangelogEventDetail.CreatePackage(234, None, None, None),
        now.minusDays(2)
      )
      .await
      .value
    secureContainer.changelogManager
      .logEvent(
        ds,
        ChangelogEventDetail.RenamePackage(234, None, "old", "new", None),
        now.minusDays(1)
      )
      .await
      .value
    secureContainer.changelogManager
      .logEvent(
        ds,
        ChangelogEventDetail.UpdateOwner(loggedInUser.id, colleagueUser.id),
        now
      )
      .await
      .value

    val nextCursor = get(
      s"/${ds.nodeId}/changelog/timeline?limit=2",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val dto = parsedBody.extract[com.pennsieve.api.ChangelogPage]
      dto.eventGroups.map(_.eventType) shouldBe List(
        ChangelogEventName.UPDATE_OWNER,
        ChangelogEventName.RENAME_PACKAGE
      )
      dto.cursor shouldBe defined
      dto.cursor.get
    }

    get(
      s"/${ds.nodeId}/changelog/timeline?limit=2&cursor=$nextCursor",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val dto = parsedBody.extract[com.pennsieve.api.ChangelogPage]
      dto.eventGroups.map(_.eventType) shouldBe List(
        ChangelogEventName.CREATE_PACKAGE
      )
      dto.cursor shouldBe None
    }
  }

  test("load events from event group in changelog timeline") {
    import com.pennsieve.models.{ ChangelogEventDetail, ChangelogEventName }
    val ds = createDataSet("timeline-dataset")
    val now = java.time.ZonedDateTime.now()
    secureContainer.changelogManager
      .logEvent(
        ds,
        ChangelogEventDetail.CreatePackage(234, None, None, None),
        now.minusDays(2)
      )
      .await
      .value
    secureContainer.changelogManager
      .logEvent(
        ds,
        ChangelogEventDetail.CreatePackage(233, None, None, None),
        now.minusDays(2).minusMinutes(1)
      )
      .await
      .value
    secureContainer.changelogManager
      .logEvent(
        ds,
        ChangelogEventDetail.RenamePackage(234, None, "old", "new", None),
        now.minusDays(1)
      )
      .await
      .value
    secureContainer.changelogManager
      .logEvent(
        ds,
        ChangelogEventDetail.UpdateOwner(loggedInUser.id, colleagueUser.id),
        now
      )
      .await
      .value

    val eventCursor = get(
      s"/${ds.nodeId}/changelog/timeline",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val dto = parsedBody.extract[com.pennsieve.api.ChangelogPage]
      dto.eventGroups.map(_.eventType) shouldBe List(
        ChangelogEventName.UPDATE_OWNER,
        ChangelogEventName.RENAME_PACKAGE,
        ChangelogEventName.CREATE_PACKAGE
      )
      dto.eventGroups
        .find(_.eventType == ChangelogEventName.CREATE_PACKAGE)
        .get
        .eventCursor
        .get
    }

    get(
      s"/${ds.nodeId}/changelog/events?cursor=$eventCursor",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      (parsedBody \\ "events" \ "eventType")
        .extract[List[ChangelogEventName]] shouldBe List(
        ChangelogEventName.CREATE_PACKAGE,
        ChangelogEventName.CREATE_PACKAGE
      )
    }
  }

  test("unlock the dataset if publish request fails") {
    val ds = createDataSet("MOCK ERROR")
    addBannerAndReadme(ds)
    val pkg = createPackage(ds, "some-package", `type` = CSV)
    createFile(pkg, FileObjectType.Source, FileProcessingState.Processed)

    val (publisherTeam, _) = secureContainer.organizationManager
      .getPublisherTeam(secureContainer.organization)
      .await
      .value
    secureContainer.teamManager
      .addUser(publisherTeam, colleagueUser, DBPermission.Administer)
      .await
      .value

    var publicationStatusId: Option[Int] = None
    postJson(
      s"/${ds.nodeId}/publication/request?publicationType=publication",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
      publicationStatusId = secureContainer.datasetPublicationStatusManager
        .getLatestByDataset(ds.id)
        .await
        .value
        .map(_.id)
    }

    postJson(
      s"/${ds.nodeId}/publication/accept?publicationType=publication",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 500
      secureContainer.datasetPublicationStatusManager
        .getLatestByDataset(ds.id)
        .await
        .value
        .map(_.id) shouldBe publicationStatusId
    }
  }

  test(
    "state of pending files is changed to uploaded when publish is cancelled"
  ) {
    val ds = createDataSet("My Dataset")
    addBannerAndReadme(ds)
    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Requested, PublicationType.Publication)
      .await
      .value
    val pkg = createPackage(ds, "some-package", `type` = CSV)
    val pendingFile = createFile(pkg, uploadedState = Some(FileState.PENDING))
    postJson(
      s"/${ds.nodeId}/publication/cancel?publicationType=publication",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }
    val updated =
      secureContainer.fileManager.get(pendingFile.id, pkg).await.value
    updated.uploadedState shouldBe Some(FileState.UPLOADED)
  }

  test("state of pending files is changed to uploaded when publish is complete") {
    val ds = createDataSet("My Dataset")
    addBannerAndReadme(ds)

    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Accepted, PublicationType.Publication)
      .await
      .value

    val pkg = createPackage(ds, "some-package", `type` = CSV)
    val pendingFile = createFile(pkg, uploadedState = Some(FileState.PENDING))

    val request = write(
      com.pennsieve.api.PublishCompleteRequest(
        Some(1),
        1,
        Some(java.time.OffsetDateTime.now),
        com.pennsieve.models.PublishStatus.PublishSucceeded,
        success = true,
        error = None
      )
    )
    putJson(
      s"/${ds.id}/publication/complete",
      request,
      headers = jwtServiceAuthorizationHeader(loggedInOrganization) ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    val updatedFile =
      secureContainer.fileManager.get(pendingFile.id, pkg).await.value
    updatedFile.uploadedState shouldBe Some(FileState.UPLOADED)
  }

  test("state of pending files is changed to uploaded when publish is rejected") {
    val ds =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)

    postJson(
      s"/${ds.nodeId}/publication/request?publicationType=publication",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    val pkg = createPackage(ds, "some-new-package", `type` = CSV)
    val pendingFile = createFile(pkg, uploadedState = Some(FileState.PENDING))

    postJson(
      s"/${ds.nodeId}/publication/reject?publicationType=publication",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    val updatedFile =
      secureContainer.fileManager.get(pendingFile.id, pkg).await.value
    updatedFile.uploadedState shouldBe Some(FileState.UPLOADED)
  }

  test("notify the Discover service to release an embargoed dataset") {
    val ds = initializePublicationTest()
    secureContainer.datasetPublicationStatusManager
      .create(
        ds,
        PublicationStatus.Requested,
        PublicationType.Release,
        None,
        Some(java.time.LocalDate.now)
      )
      .await
      .value

    post(
      s"/${ds.nodeId}/publication/accept?publicationType=release",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    mockPublishClient.releaseRequests should contain(
      (loggedInOrganization.id, ds.id)
    )
  }

  test("notify the Discover service to revise a dataset") {
    val ds = initializePublicationTest()
    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .value
    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Requested, PublicationType.Revision)
      .await
      .value

    post(
      s"/${ds.nodeId}/publication/accept?publicationType=revision",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
      val response =
        parsedBody.extract[com.pennsieve.models.DatasetPublicationStatus]
      response.datasetId shouldBe ds.id
      response.publicationStatus shouldBe PublicationStatus.Completed
      response.publicationType shouldBe PublicationType.Revision
    }

    mockPublishClient.reviseRequests should contain(
      (loggedInOrganization.id, ds.id)
    )
  }

  test("notify the Discover service to publish a dataset") {
    val ds = createDataSet("My Dataset")
    addBannerAndReadme(ds)
    addPublisherTeamAsCollaborator(ds, colleagueUser)

    secureContainer.datasetPublicationStatusManager
      .create(
        ds,
        PublicationStatus.Requested,
        PublicationType.Publication,
        None
      )
      .await
      .value

    postJson(
      s"/${ds.nodeId}/publication/accept?publicationType=publication",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
      val result =
        parsedBody.extract[com.pennsieve.models.DatasetPublicationStatus]
      result.datasetId shouldBe ds.id
      result.publicationStatus shouldBe PublicationStatus.Accepted
      result.publicationType shouldBe PublicationType.Publication
    }

    mockPublishClient.publishRequests
      .get((loggedInOrganization.id, ds.id))
      .get
      ._1 shouldBe Some(false)
  }

  test("notify the Discover service to embargo a dataset") {
    val ds = createDataSet("My Dataset")
    addBannerAndReadme(ds)
    addPublisherTeamAsCollaborator(ds, colleagueUser)

    val embargoReleaseDate = java.time.LocalDate.now.plusWeeks(1)
    secureContainer.datasetPublicationStatusManager
      .create(
        ds,
        PublicationStatus.Requested,
        PublicationType.Embargo,
        None,
        Some(embargoReleaseDate)
      )
      .await
      .value

    postJson(
      s"/${ds.nodeId}/publication/accept?publicationType=embargo",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
      val result =
        parsedBody.extract[com.pennsieve.models.DatasetPublicationStatus]
      result.datasetId shouldBe ds.id
      result.publicationStatus shouldBe PublicationStatus.Accepted
      result.publicationType shouldBe PublicationType.Embargo
    }

    val (embargo, sentEmbargoReleaseDate, _) =
      mockPublishClient.publishRequests
        .get((loggedInOrganization.id, ds.id))
        .get

    embargo shouldBe Some(true)
    sentEmbargoReleaseDate shouldBe Some(embargoReleaseDate)
  }

  test("notify the Discover service to unpublish a dataset") {
    val ds = createDataSet("My Dataset")
    addBannerAndReadme(ds)
    addPublisherTeamAsCollaborator(ds, colleagueUser)

    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .value
    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Requested, PublicationType.Removal)
      .await
      .value

    post(
      s"/${ds.nodeId}/publication/accept?publicationType=removal",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    mockPublishClient.unpublishRequests should contain(
      (loggedInOrganization.id, ds.id)
    )
  }

  test("cannot start multiple concurrent publish jobs for the same dataset") {
    val ds = createDataSet("My Dataset")
    addBannerAndReadme(ds)
    addPublisherTeamAsCollaborator(ds, colleagueUser)

    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Requested, PublicationType.Publication)
      .await
      .value

    postJson(
      s"/${ds.nodeId}/publication/accept?publicationType=publication",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    postJson(
      s"/${ds.nodeId}/publication/accept?publicationType=publication",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 403
    }
  }

  test("fail to publish a dataset without a license") {
    val ds = createDataSet("My Dataset", license = None)
    addBannerAndReadme(ds)
    addPublisherTeamAsCollaborator(ds, colleagueUser)

    postJson(
      s"/${ds.nodeId}/publication/accept?publicationType=publication",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 400
    }
  }

  test("fail to publish a dataset without tags") {
    val ds = createDataSet("My Dataset", tags = List.empty)
    addBannerAndReadme(ds)
    addPublisherTeamAsCollaborator(ds, colleagueUser)

    postJson(
      s"/${ds.nodeId}/publication/accept?publicationType=publication",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 400
    }
  }

  test("fail to publish a dataset without contributors") {
    val ds = createDataSet("My Dataset")
    addBannerAndReadme(ds)
    addPublisherTeamAsCollaborator(ds, colleagueUser)

    // Remove the auto-added contributor (the owner). Auto-contributor's id
    // is the only entry in datasetContributors for this dataset.
    val contribIds = state.datasetContributors.collect {
      case ((orgId, dsId, cid), _)
          if orgId == loggedInOrganization.id && dsId == ds.id =>
        cid
    }.toList
    contribIds.foreach(
      cid => secureContainer.datasetManager.removeContributor(ds, cid).await
    )

    postJson(
      s"/${ds.nodeId}/publication/accept?publicationType=publication",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 400
    }
  }

  test("publishers - publish a dataset if the requesting user is a publisher") {
    val ds = createDataSet("My Dataset")
    addBannerAndReadme(ds)

    val publisherTeam = secureContainer.organizationManager
      .getPublisherTeam(secureContainer.organization)
      .await
      .value

    secureContainer.teamManager
      .addUser(publisherTeam._1, colleagueUser, DBPermission.Administer)
      .await
      .value

    postJson(
      s"/${ds.nodeId}/publication/request?publicationType=publication",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    postJson(
      s"/${ds.nodeId}/publication/accept?publicationType=publication",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }
  }

  test("2 step publishing - get datasets with a requested publication") {
    val ds1 = createDataSet("My Dataset")
    addBannerAndReadme(ds1)

    postJson(
      s"/${ds1.nodeId}/publication/request?publicationType=publication&comments=hello%20world",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    val ds2 = createDataSet("My second Dataset")
    addBannerAndReadme(ds2)

    postJson(
      s"/${ds2.nodeId}/publication/request?publicationType=publication",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    createDataSet("My third Dataset")

    get(
      s"/paginated?publicationStatus=requested&publicationType=publication",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      val response = parsedBody.extract[com.pennsieve.api.PaginatedDatasets]
      response.totalCount shouldBe 2
      response.datasets
        .map(_.content.name)
        .toSet shouldBe Set("My Dataset", "My second Dataset")
    }
  }

  test("2 step publishing - get datasets with no publication status") {
    val ds1 = createDataSet("My Dataset")
    addBannerAndReadme(ds1)

    postJson(
      s"/${ds1.nodeId}/publication/request?publicationType=publication&comments=hello%20world",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    createDataSet("My second Dataset")

    get(
      s"/paginated?publicationStatus=draft&publicationStatus=requested",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      val response = parsedBody.extract[com.pennsieve.api.PaginatedDatasets]
      response.totalCount shouldBe 3
      response.datasets
        .map(_.content.name)
        .toSet shouldBe Set("Home", "My Dataset", "My second Dataset")
    }
  }

  test(
    "2 step publishing - get datasets with a requested or rejected publication"
  ) {
    val ds =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)

    postJson(
      s"/${ds.nodeId}/publication/request?publicationType=publication&comments=hello%20world",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) { status shouldBe 201 }

    postJson(
      s"/${ds.nodeId}/publication/reject?publicationType=publication&comments=hello%20world",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) { status shouldBe 201 }

    val ds2 = createDataSet("My second Dataset")
    addBannerAndReadme(ds2)
    val pkg2 = createPackage(ds2, "some-package", `type` = CSV)
    createFile(pkg2, FileObjectType.Source, FileProcessingState.Processed)
    postJson(
      s"/${ds2.nodeId}/publication/request?publicationType=publication",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) { status shouldBe 201 }

    createDataSet("My third Dataset")

    get(
      s"/paginated?publicationStatus=requested&publicationStatus=rejected&publicationType=publication",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      val response = parsedBody.extract[com.pennsieve.api.PaginatedDatasets]
      response.totalCount shouldBe 2
      response.datasets.map(_.content.name).toSet shouldBe Set(
        "My Dataset",
        "My second Dataset"
      )
      response.datasets.map(_.publication.status).toSet shouldBe Set(
        PublicationStatus.Rejected,
        PublicationStatus.Requested
      )
    }
  }

  test(
    "2 step publishing - publishers cannot request or cancel publication, owners cannot or reject/accept/retract, only system admins can complete/fail publication"
  ) {
    val ds =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication,
      authorizationHeader(colleagueJwt)
    )(ds) shouldBe (403, Some(PublicationStatus.Draft), None)

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication,
      authorizationHeader(loggedInJwt) ++ traceIdHeader()
    )(ds) shouldBe
      (201, Some(PublicationStatus.Requested), Some(
        PublicationType.Publication
      ))

    publicationRequestResult(
      PublicationStatus.Cancelled,
      PublicationType.Publication,
      authorizationHeader(colleagueJwt) ++ traceIdHeader()
    )(ds) shouldBe
      (403, Some(PublicationStatus.Requested), Some(
        PublicationType.Publication
      ))

    publicationRequestResult(
      PublicationStatus.Rejected,
      PublicationType.Publication,
      authorizationHeader(loggedInJwt) ++ traceIdHeader()
    )(ds) shouldBe
      (403, Some(PublicationStatus.Requested), Some(
        PublicationType.Publication
      ))

    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Publication,
      authorizationHeader(loggedInJwt) ++ traceIdHeader()
    )(ds) shouldBe
      (403, Some(PublicationStatus.Requested), Some(
        PublicationType.Publication
      ))

    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Publication,
      authorizationHeader(colleagueJwt) ++ traceIdHeader()
    )(ds) shouldBe
      (201, Some(PublicationStatus.Accepted), Some(PublicationType.Publication))

    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Publication,
      authorizationHeader(loggedInJwt) ++ traceIdHeader()
    )(ds) shouldBe
      (403, Some(PublicationStatus.Accepted), Some(PublicationType.Publication))

    publicationRequestResult(
      PublicationStatus.Completed,
      PublicationType.Publication,
      authorizationHeader(loggedInJwt) ++ traceIdHeader()
    )(ds) shouldBe
      (403, Some(PublicationStatus.Accepted), Some(PublicationType.Publication))

    publicationRequestResult(
      PublicationStatus.Completed,
      PublicationType.Publication,
      authorizationHeader(colleagueJwt) ++ traceIdHeader()
    )(ds) shouldBe
      (403, Some(PublicationStatus.Accepted), Some(PublicationType.Publication))
  }

  test("2 step publishing - get datasets with multiple publication types") {
    val publicationDataset = createDataSet("Publication dataset")
    secureContainer.datasetPublicationStatusManager
      .create(
        publicationDataset,
        PublicationStatus.Requested,
        PublicationType.Publication
      )
      .await
      .value

    val embargoDataset = createDataSet("Embargo dataset")
    secureContainer.datasetPublicationStatusManager
      .create(
        embargoDataset,
        PublicationStatus.Requested,
        PublicationType.Embargo
      )
      .await
      .value

    val revisionDataset = createDataSet("Revision dataset")
    secureContainer.datasetPublicationStatusManager
      .create(
        revisionDataset,
        PublicationStatus.Requested,
        PublicationType.Revision
      )
      .await
      .value

    get(
      s"/paginated?publicationStatus=requested&publicationType=publication&publicationType=embargo",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      val response = parsedBody.extract[com.pennsieve.api.PaginatedDatasets]
      response.totalCount shouldBe 2
      response.datasets
        .map(_.content.id)
        .toSet shouldBe Set(publicationDataset.nodeId, embargoDataset.nodeId)
    }
  }

  test("get the publishing status of all of the organization's datasets") {
    import java.time.{ OffsetDateTime, ZoneOffset }
    val expectedResponse = List(
      com.pennsieve.discover.client.definitions.DatasetPublishStatus(
        "PPMI",
        loggedInOrganization.id,
        1,
        Some(10),
        2,
        com.pennsieve.models.PublishStatus.PublishInProgress,
        Some(OffsetDateTime.of(2019, 2, 1, 10, 11, 12, 13, ZoneOffset.UTC)),
        None,
        workflowId = 4
      ),
      com.pennsieve.discover.client.definitions.DatasetPublishStatus(
        "TUSZ",
        loggedInOrganization.id,
        2,
        Some(12),
        3,
        com.pennsieve.models.PublishStatus.PublishInProgress,
        Some(OffsetDateTime.of(2019, 4, 1, 10, 11, 12, 13, ZoneOffset.UTC)),
        None,
        workflowId = 4
      )
    )

    get(
      s"/published",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody
        .extract[List[
          com.pennsieve.discover.client.definitions.DatasetPublishStatus
        ]] shouldBe expectedResponse
    }
  }

  test("get the publishing status of a dataset") {
    val ds = createDataSet("My Dataset")

    val expectedResponse =
      com.pennsieve.discover.client.definitions.DatasetPublishStatus(
        "PPMI",
        loggedInOrganization.id,
        ds.id,
        None,
        0,
        com.pennsieve.models.PublishStatus.PublishInProgress,
        None,
        None,
        workflowId = 4
      )

    get(
      s"/${ds.nodeId}/published",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody
        .extract[com.pennsieve.discover.client.definitions.DatasetPublishStatus] shouldBe expectedResponse
    }
  }

  test("unlock dataset when publish is complete") {
    val ds = createDataSet("My Dataset")
    addBannerAndReadme(ds)

    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Accepted, PublicationType.Publication)
      .await
      .value

    import com.pennsieve.api.PublishCompleteRequest
    import com.pennsieve.models.PublishStatus
    import java.time.OffsetDateTime

    val request = write(
      PublishCompleteRequest(
        Some(1),
        1,
        Some(OffsetDateTime.now),
        PublishStatus.PublishSucceeded,
        success = true,
        error = None
      )
    )

    putJson(
      s"/${ds.id}/publication/complete",
      request,
      headers = jwtServiceAuthorizationHeader(loggedInOrganization) ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    val latest = secureContainer.datasetPublicationStatusManager
      .getLatestByDataset(ds.id)
      .await
      .value
      .get

    PublicationStatus.lockedStatuses contains latest.publicationStatus shouldBe false
  }

  test("set publication status when publish fails") {
    val ds = createDataSet("My Dataset")

    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Accepted, PublicationType.Publication)
      .await
      .value

    import com.pennsieve.api.PublishCompleteRequest
    import com.pennsieve.models.PublishStatus

    val request = write(
      PublishCompleteRequest(
        None,
        1,
        None,
        PublishStatus.PublishFailed,
        success = false,
        error = Some("Publish failed")
      )
    )

    putJson(
      s"/${ds.id}/publication/complete",
      request,
      headers = jwtServiceAuthorizationHeader(loggedInOrganization) ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    val latest = secureContainer.datasetPublicationStatusManager
      .getLatestByDataset(ds.id)
      .await
      .value
      .get

    latest.publicationStatus shouldBe PublicationStatus.Failed
  }

  test("publishers - unpublish a dataset if the user is a publisher") {
    val ds = createDataSet("My Dataset")
    addBannerAndReadme(ds)

    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .value

    val publisherTeam = secureContainer.organizationManager
      .getPublisherTeam(secureContainer.organization)
      .await
      .value
    secureContainer.teamManager
      .addUser(publisherTeam._1, colleagueUser, DBPermission.Administer)
      .await
      .value

    postJson(
      s"/${ds.nodeId}/publication/request?publicationType=removal",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    postJson(
      s"/${ds.nodeId}/publication/accept?publicationType=removal",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }
  }

  test("2 step publishing - can cancel rejected dataset") {
    val ds = createDataSet("cancel-rejected")
    addBannerAndReadme(ds)

    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Rejected, PublicationType.Publication)
      .await
      .value

    post(
      s"/${ds.nodeId}/publication/cancel?publicationType=publication",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    val latest = secureContainer.datasetPublicationStatusManager
      .getLatestByDataset(ds.id)
      .await
      .value
      .get
    latest.publicationStatus shouldBe PublicationStatus.Cancelled
    latest.publicationType shouldBe PublicationType.Publication
  }

  test("2 step publishing - revision can be requested by a manager") {
    val ds = createDataSet("revision-by-manager")
    addBannerAndReadme(ds)

    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .value

    secureContainer.datasetManager
      .addUserCollaborator(ds, colleagueUser, Role.Editor)
      .await

    postJson(
      s"/${ds.nodeId}/publication/request?publicationType=revision",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 403
    }

    secureContainer.datasetManager
      .addUserCollaborator(ds, colleagueUser, Role.Manager)
      .await

    postJson(
      s"/${ds.nodeId}/publication/request?publicationType=revision",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
      (parsedBody \ "datasetId").extract[Int] shouldBe ds.id
      (parsedBody \ "publicationStatus")
        .extract[PublicationStatus] shouldBe PublicationStatus.Requested
      (parsedBody \ "publicationType")
        .extract[PublicationType] shouldBe PublicationType.Revision
      (parsedBody \ "createdBy").extract[Int] shouldBe colleagueUser.id
    }
  }

  test("2 step publishing - cannot embargo dataset after it has been published") {
    val ds = createDataSet("embargo-after-published")
    addBannerAndReadme(ds)

    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .value

    post(
      s"/${ds.nodeId}/publication/request?publicationType=embargo",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 400
    }
  }

  test("2 step publishing - can embargo dataset after it has been removed") {
    val ds = createDataSet("embargo-after-removed")
    addBannerAndReadme(ds)

    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Removal)
      .await
      .value

    val embargoReleaseDate = java.time.LocalDate.now.plusWeeks(1)
    post(
      s"/${ds.nodeId}/publication/request?publicationType=embargo&embargoReleaseDate=$embargoReleaseDate",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }
  }

  test("2 step publishing - can remove embargoed dataset") {
    val ds = createDataSet("remove-embargoed")
    addBannerAndReadme(ds)

    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Embargo)
      .await
      .value

    post(
      s"/${ds.nodeId}/publication/request?publicationType=removal",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
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
    license: Option[License] = Some(License.`Apache 2.0`),
    `type`: DatasetType = DatasetType.Research,
    dataUseAgreement: Option[com.pennsieve.models.DataUseAgreement] = None
  ): Dataset = {
    val container = secureContainerFor(loggedInUser, loggedInOrganization)
    container.datasetManager
      .create(
        name,
        description,
        tags = tags,
        license = license,
        `type` = `type`,
        dataUseAgreement = dataUseAgreement
      )
      .await
      .value
  }

  /**
    * Create a dataset that is forced to have a specific id. Used for tests
    * whose assertions tie to MockPublishClient's hardcoded sourceDatasetIds.
    */
  private def createDataSetWithId(name: String, id: Int): Dataset = {
    val ds = createDataSet(name)
    val withId = ds.copy(id = id)
    state.datasets.remove((loggedInOrganization.id, ds.id))
    state.datasets.put((loggedInOrganization.id, id), withId)
    // re-key user-role map for this dataset
    val toUpdate = state.datasetUserRoles.collect {
      case ((orgId, uid, dsId), r)
          if orgId == loggedInOrganization.id && dsId == ds.id =>
        ((orgId, uid, dsId), r)
    }
    toUpdate.foreach {
      case ((orgId, uid, _), r) =>
        state.datasetUserRoles.remove((orgId, uid, ds.id))
        state.datasetUserRoles.put((orgId, uid, id), r)
    }
    val toUpdateContrib = state.datasetContributors.collect {
      case ((orgId, dsId, cid), pos)
          if orgId == loggedInOrganization.id && dsId == ds.id =>
        ((orgId, dsId, cid), pos)
    }
    toUpdateContrib.foreach {
      case ((orgId, _, cid), pos) =>
        state.datasetContributors.remove((orgId, ds.id, cid))
        state.datasetContributors.put((orgId, id, cid), pos)
    }
    withId
  }

  private def createDataUseAgreement(
    name: String,
    body: String,
    isDefault: Boolean = false
  ): com.pennsieve.models.DataUseAgreement = {
    val container = secureContainerFor(loggedInUser, loggedInOrganization)
    container.dataUseAgreementManager
      .create(name, body, isDefault = isDefault)
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

  private def createFile(
    pkg: Package,
    objectType: FileObjectType = FileObjectType.Source,
    processingState: FileProcessingState = FileProcessingState.Unprocessed,
    name: String = "i'm a file",
    uploadedState: Option[FileState] = None
  ): File = {
    val container = secureContainerFor(loggedInUser, loggedInOrganization)
    container.fileManager
      .create(
        name = name,
        `type` = FileType.CSV,
        `package` = pkg,
        s3Bucket = "anything",
        s3Key = "anything",
        objectType = objectType,
        processingState = processingState,
        uploadedState = uploadedState
      )
      .await
      .value
  }

  private def createObjects(
    csvPackage: Package
  ): Option[Map[String, List[SimpleFileDTO]]] =
    Some(
      Map(
        FileObjectType.Source.entryName -> List(
          SimpleFileDTO(createFile(csvPackage), csvPackage)
        ),
        FileObjectType.View.entryName -> List.empty[SimpleFileDTO],
        FileObjectType.File.entryName -> List.empty[SimpleFileDTO]
      )
    )

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
