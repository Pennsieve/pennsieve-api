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

import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{ LocalDate, OffsetDateTime, ZoneOffset }

import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import cats.implicits._
import com.pennsieve.auth.middleware.{ Jwt, OrganizationId, UserClaim, UserId }
import com.pennsieve.aws.email.LoggingEmailer
import com.pennsieve.clients.{ MockDatasetAssetClient, MockModelServiceClient }
import com.pennsieve.discover.client.definitions.DatasetPublishStatus
import com.pennsieve.doi.client.definitions._
import com.pennsieve.doi.models.{ DoiDTO, DoiState }
import com.pennsieve.dtos.SimpleFileDTO.TypeToSimpleFile
import com.pennsieve.dtos._
import com.pennsieve.helpers._
import com.pennsieve.managers.{ CollaboratorChanges, TeamManager }
import com.pennsieve.models.FileObjectType.Source
import com.pennsieve.models.PackageType.{
  CSV,
  Collection,
  PDF,
  Slide,
  TimeSeries
}
import com.pennsieve.models._
import com.pennsieve.notifications.{
  DiscoverPublishNotification,
  NotificationMessage
}
import com.pennsieve.traits.PostgresProfile.api._
import io.circe.parser.decode
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils
import org.apache.http.HttpHeaders
import org.json4s._
import org.json4s.jackson.Serialization.{ read, write }
import org.scalatest.EitherValues._
import org.scalatest.OptionValues._
import org.scalatra.test.{ BytesPart, FilePart }
import shapeless.syntax.inject._
import java.time.ZonedDateTime

import scala.concurrent.Future
import scala.concurrent.duration.{ DurationDouble, FiniteDuration }

class TestDataSetsController extends BaseApiTest with DataSetTestMixin {

  implicit val mockDatasetAssetClient: MockDatasetAssetClient =
    new MockDatasetAssetClient()

  val mockAuditLogger = new MockAuditLogger()

  val mockSqsClient = MockSQSClient

  val maxFileUploadSize = 1 * 1024 * 1024

  var mockPublishClient: MockPublishClient = _

  var mockSearchClient: MockSearchClient = _

  implicit val zonedDateTimeOrdering: Ordering[ZonedDateTime] =
    Ordering.by(_.toInstant)

  override def afterStart(): Unit = {
    super.afterStart()

    implicit val httpClient: HttpRequest => Future[HttpResponse] = { _ =>
      Future.successful(HttpResponse())
    }

    mockPublishClient = new MockPublishClient()

    mockSearchClient = new MockSearchClient()

    addServlet(
      new DataSetsController(
        insecureContainer,
        secureContainerBuilder,
        system,
        mockAuditLogger,
        mockSqsClient,
        new MockModelServiceClient(),
        mockPublishClient,
        mockSearchClient,
        new MockDoiClient(),
        mockDatasetAssetClient,
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

  override def afterEach(): Unit = {
    super.afterEach()
    mockSqsClient.sentMessages.clear
    mockPublishClient.clear
    mockSearchClient.clear
  }

  test("swagger") {
    import com.pennsieve.web.ResourcesApp
    addServlet(new ResourcesApp, "/api-docs/*")

    get("/api-docs/swagger.json") {
      status should equal(200)
      println(body)
    }
  }

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
        .filter(_.content.name != dataset.name) // Filter out the "Home" dataset
        .map(_.bannerPresignedUrl.isDefined)
        .foldLeft(true)(_ && _) shouldBe true // all banner URLs should be defined
    }

    get(s"/", headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()) {
      status should equal(200)

      val datasets = parsedBody.extract[List[DataSetDTO]]
      datasets.length shouldBe 4 // 3 + "Home"
      datasets
        .filter(_.content.name != dataset.name) // Filter out the "Home" dataset
        .map(_.bannerPresignedUrl.isDefined)
        .foldLeft(true)(_ && _) shouldBe false // all banner URLs should be omitted
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

      datasets
        .map(_.publication) shouldBe List(
        DatasetPublicationDTO(
          Some(
            DiscoverPublishedDatasetDTO(
              Some(10),
              2,
              Some(
                OffsetDateTime.of(2019, 2, 1, 10, 11, 12, 13, ZoneOffset.UTC)
              )
            )
          ),
          PublicationStatus.Draft,
          None
        ),
        DatasetPublicationDTO(
          Some(
            DiscoverPublishedDatasetDTO(
              Some(12),
              3,
              Some(
                OffsetDateTime.of(2019, 4, 1, 10, 11, 12, 13, ZoneOffset.UTC)
              )
            )
          ),
          PublicationStatus.Draft,
          None
        )
      )
    }

    get(s"/", headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()) {
      status should equal(200)

      val datasets = parsedBody.extract[List[DataSetDTO]]
      datasets
        .map(_.publication) shouldBe List(
        DatasetPublicationDTO(None, PublicationStatus.Draft, None),
        DatasetPublicationDTO(None, PublicationStatus.Draft, None)
      )
    }
  }

  test("get a data set") {
    val dataset = createDataSet("test-dataset")
    addBannerAndReadme(dataset)

    get(
      s"/${dataset.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      response.getHeader(HttpHeaders.ETAG) shouldBe dataset.etag.asHeader

      val dto = parsedBody.extract[DataSetDTO]
      dto.content should equal(
        WrappedDataset(dataset, defaultDatasetStatus)
          .copy(updatedAt = dto.content.updatedAt)
      )
      dto.bannerPresignedUrl.isDefined shouldBe (true)
      dto.status should equal(
        DatasetStatusDTO(defaultDatasetStatus, DatasetStatusInUse(true))
      )
    }
  }

  test("demo user - get data - can get their own dataset") {
    val dataset = createDataSet(
      "test-dataset",
      description = Some("demo user dataset"),
      container = sandboxUserContainer,
      status = Some(sandboxUserDatasetStatus.id)
    )
    addBannerAndReadme(dataset, container = sandboxUserContainer)

    get(
      s"/${dataset.nodeId}",
      headers = authorizationHeader(sandboxUserJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      response.getHeader(HttpHeaders.ETAG) shouldBe dataset.etag.asHeader

      val dto = parsedBody.extract[DataSetDTO]
      dto.content should equal(
        WrappedDataset(dataset, sandboxUserDatasetStatus)
          .copy(updatedAt = dto.content.updatedAt)
      )
      dto.bannerPresignedUrl.isDefined shouldBe (true)
      dto.status should equal(
        DatasetStatusDTO(sandboxUserDatasetStatus, DatasetStatusInUse(true))
      )
    }
  }

  test("demo user - get data - cannot get someone else's demo dataset") {
    val dataset =
      createDataSet(
        "test-private-dataset",
        description = Some("Demo user dataset"),
        container = sandboxUserContainer,
        status = Some(sandboxUserDatasetStatus.id)
      )
    addBannerAndReadme(dataset, container = sandboxUserContainer)

    get(
      s"/${dataset.nodeId}",
      headers = authorizationHeader(loggedInSandboxUserJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }
  }

  test("get a data set with publication info") {
    val ds1 = createDataSet("test-ds1")

    get(
      s"/${ds1.nodeId}?includePublishedDataset=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val dataset = parsedBody.extract[DataSetDTO]

      dataset.publication shouldBe
        DatasetPublicationDTO(
          Some(
            DiscoverPublishedDatasetDTO(
              Some(12),
              3,
              Some(
                OffsetDateTime
                  .of(2019, 4, 1, 10, 11, 12, 13, ZoneOffset.UTC)
              )
            )
          ),
          PublicationStatus.Draft,
          None
        )

    }
    get(
      s"/${ds1.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val dataset = parsedBody.extract[DataSetDTO]
      dataset.publication shouldBe
        DatasetPublicationDTO(None, PublicationStatus.Draft, None)
    }
  }

  test("get a data set for an external file") {
    val description = Some("An external file")
    val externalLocation = Some("https://drive.google.com/external_file")

    val ds1 = createDataSet("test-ds1")
    val externalPackage =
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
    createPackage(
      dataset = ds1,
      "package1",
      ownerId = Some(loggedInUser.id),
      `type` = TimeSeries
    )
    createPackage(
      dataset = ds1,
      "package2",
      ownerId = Some(loggedInUser.id),
      `type` = TimeSeries
    )
    createPackage(
      dataset = ds1,
      "package3",
      ownerId = Some(loggedInUser.id),
      `type` = PDF
    )
    createPackage(
      dataset = ds1,
      "package4",
      ownerId = Some(loggedInUser.id),
      `type` = PDF
    )
    createPackage(
      dataset = ds1,
      "package5",
      ownerId = Some(loggedInUser.id),
      `type` = Slide
    )
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
    createPackage(
      dataset = ds1,
      "package1",
      ownerId = Some(loggedInUser.id),
      `type` = TimeSeries
    )
    createPackage(
      dataset = ds1,
      "package3",
      ownerId = Some(loggedInUser.id),
      `type` = PDF,
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

  test("get all data sets for the logged in user") {
    val dataset1 = createDataSet("test-dataset-1")
    val dataset2 = createDataSet("test-dataset-2")
    val package1 =
      createPackage(
        dataset = dataset1,
        "package1",
        ownerId = Some(loggedInUser.id)
      )
    val package2 =
      createPackage(
        dataset = dataset1,
        "package2",
        ownerId = Some(loggedInUser.id)
      )
    val package3 =
      createPackage(
        dataset = dataset2,
        "package3",
        ownerId = Some(loggedInUser.id)
      )

    // Dataset 1 has one other user
    secureContainer.datasetManager
      .addUserCollaborator(dataset1, colleagueUser, Role.Editor)
      .await

    // Dataset 1 has two teams
    val team1 = createTeam("Team 1")
    val team2 = createTeam("Team 2")
    secureContainer.datasetManager
      .addTeamCollaborator(dataset1, team1, Role.Editor)
      .await
    secureContainer.datasetManager
      .addTeamCollaborator(dataset1, team2, Role.Viewer)
      .await

    // The root dataset is shared with the entire organization
    secureContainer.datasetManager
      .setOrganizationCollaboratorRole(dataset, Some(Role.Viewer))
    // refresh updatedAt timestamp
    val updatedDataset =
      secureContainer.datasetManager.get(dataset.id).await.right.get

    get(s"/", headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()) {
      status should equal(200)

      val response = parsedBody.extract[List[DataSetDTO]]
      // Remove timestamps
      response.map(_.content).sortBy(_.name).map {
        case (ds: WrappedDataset) => (ds.intId, ds.name)
      } shouldBe List(
        (updatedDataset.id, updatedDataset.name),
        (dataset1.id, dataset1.name),
        (dataset2.id, dataset2.name)
      )

      response.sortBy(_.content.name).map(_.collaboratorCounts) shouldBe List(
        CollaboratorCounts(0, 1, 0),
        CollaboratorCounts(1, 0, 2),
        CollaboratorCounts(0, 0, 0)
      )

      response.sortBy(_.content.name).map(_.owner) shouldBe List.fill(3)(
        loggedInUser.nodeId
      )
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

  test("get a data set that is not a data set") {
    val ds = createDataSet("Foo")
    val collection = packageManager
      .create(
        "Foo",
        PackageType.Collection,
        PackageState.READY,
        ds,
        Some(loggedInUser.id),
        None
      )
      .await
      .right
      .value

    get(
      s"/${collection.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  // For backwards compatibility with the frontend (Polymer v.1) application
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

  test("set locked flag on dataset DTO") {
    val dataset: Dataset = initializePublicationTest()

    get(
      s"/${dataset.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody.extract[DataSetDTO].locked shouldBe false
    }

    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Requested, PublicationType.Publication)
      .await
      .right
      .value

    get(
      s"/${dataset.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody.extract[DataSetDTO].locked shouldBe true
    }
  }

  test("set locked flag on dataset DTO - paginated endpoint") {
    val dataset: Dataset = initializePublicationTest()

    get(
      s"/paginated",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody
        .extract[PaginatedDatasets]
        .datasets
        .map(_.locked) shouldBe List(false, false)
    }

    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Requested, PublicationType.Publication)
      .await
      .right
      .value

    get(
      s"/paginated",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody
        .extract[PaginatedDatasets]
        .datasets
        .map(_.locked) shouldBe List(false, true)
    }
  }

  test(
    "get all data sets for a given status for the logged in user - paginated endpoint"
  ) {
    val ds1 = createDataSet("test-ds1")
    val ds2 = createDataSet("test-ds2")
    val ds3 = createDataSet("test-ds3")
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

      get(
        s"/paginated?status=IN_REVIEW",
        headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
      ) {
        status should equal(200)

        val response = parsedBody.extract[PaginatedDatasets]
        response.limit shouldBe 25
        response.offset shouldBe 0
        response.totalCount shouldBe 1
        response.datasets
          .filter(_.content.name == "A New DataSet")
          .size shouldBe 1
      }
    }
  }

  test(
    "get all data sets for the logged in user - paginated endpoint shows published dataset info"
  ) {
    val ds1 = createDataSet("test-ds1")

    get(
      s"/paginated?includePublishedDataset=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val response = parsedBody.extract[PaginatedDatasets]

      response.datasets
        .map(_.publication.publishedDataset.map(_.id)) shouldBe List(
        Some(Some(10)),
        Some(Some(12))
      )
      response.datasets
        .map(
          _.publication.publishedDataset
            .map(_.version)
        ) shouldBe List(Some(2), Some(3))

      response.datasets
        .map(_.publication.publishedDataset.map(_.lastPublishedDate)) shouldBe List(
        Some(
          Some(OffsetDateTime.of(2019, 2, 1, 10, 11, 12, 13, ZoneOffset.UTC))
        ),
        Some(
          Some(OffsetDateTime.of(2019, 4, 1, 10, 11, 12, 13, ZoneOffset.UTC))
        )
      )
    }
  }

  test(
    "get all data sets for the logged in user - paginated endpoint canPublish flag"
  ) {
    val ds1 = createDataSet("canPublish")
    addBannerAndReadme(ds1)

    userManager
      .update(
        loggedInUser.copy(
          orcidAuthorization = Some(
            OrcidAuthorization("foo", "bar", 1, "qux", "fizz", "buzz", "biff")
          )
        )
      )
      .await
      .right
      .value

    // no description
    val ds2 = createDataSet("noDescription", description = None)
    addBannerAndReadme(ds2)

    // no tags
    val ds3 = createDataSet("noTags", tags = List.empty)
    addBannerAndReadme(ds3)

    // no license
    val ds4 = createDataSet("noLicense", license = None)
    addBannerAndReadme(ds4)

    // no banner and readme
    val ds5 = createDataSet("noReadme")

    // locked
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
      .right
      .value

    // no contributors
    val ds7 = createDataSet("noContributors")
    addBannerAndReadme(ds7)
    secureContainer.db
      .run(
        secureContainer.datasetManager.datasetContributor
          .getByDataset(ds7)
          .delete
      )
      .await

    // owner does not have ORCID
    val ds8 = createDataSet("noOwner")
    addBannerAndReadme(ds8)
    secureContainer.datasetManager
      .switchOwner(ds8, loggedInUser, colleagueUser)
      .await
      .right
      .value

    get(
      s"/paginated",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val response = parsedBody.extract[PaginatedDatasets]

      response.datasets
        .map(_.canPublish) shouldBe List(false, true, false, false, false,
        false, false, false, false)
    }

    get(
      s"/paginated?canPublish=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val response = parsedBody.extract[PaginatedDatasets]

      response.datasets.length shouldBe 1
      response.totalCount shouldBe 1
      response.datasets.map(_.content.intId) shouldBe Seq(ds1.id)
    }

    get(
      s"/paginated?canPublish=false",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val response = parsedBody.extract[PaginatedDatasets]

      response.datasets.length shouldBe 8
      response.totalCount shouldBe 8
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
      s"/paginated?includeBannerUrl=true&includeBannerUrl=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val response = parsedBody.extract[PaginatedDatasets]
      response.limit shouldBe 25
      response.offset shouldBe 0
      response.totalCount shouldBe 4 // 3 + "Home"

      response.datasets
        .filter(_.content.name != dataset.name) // Filter out the "Home" dataset
        .map(_.bannerPresignedUrl.isDefined)
        .foldLeft(true)(_ && _) shouldBe true // all banner URLs should be defined
    }

    get(
      s"/paginated",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val response = parsedBody.extract[PaginatedDatasets]
      response.limit shouldBe 25
      response.offset shouldBe 0
      response.totalCount shouldBe 4 // 3 + "Home"

      response.datasets
        .filter(_.content.name != dataset.name) // Filter out the "Home" dataset
        .map(_.bannerPresignedUrl.isDefined)
        .foldLeft(true)(_ && _) shouldBe false // all banner URLs should be omitted
    }
  }

  test(
    "get all data sets for which the logged in user is the owner - paginated endpoint"
  ) {
    val ds1 = createDataSet("test-ds1")
    addBannerAndReadme(ds1)
    val ds2 = createDataSet("test-ds2")
    addBannerAndReadme(ds2)
    val ds3 = createDataSet("test-ds3")
    addBannerAndReadme(ds3)
    val ds4 = createDataSet("dataset4")
    addBannerAndReadme(ds4)
    val ds5 = createDataSet("dataset5")
    addBannerAndReadme(ds5)
    val ds6 = createDataSet("dataset6")
    addBannerAndReadme(ds6)
    val ds7 = createDataSet("dataset7")
    addBannerAndReadme(ds7)
    val ds8 = createDataSet("dataset8")
    addBannerAndReadme(ds8)
    val ds9 = createDataSet("dataset9")
    addBannerAndReadme(ds9)
    val ds10 = createDataSet("dataset10")
    addBannerAndReadme(ds10)

    val request = write(SwitchOwnerRequest(colleagueUser.nodeId))

    putJson(
      s"/${ds3.nodeId}/collaborators/owner",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    get(
      s"/paginated?onlyMyDatasets=true&includeBannerUrl=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val response = parsedBody.extract[PaginatedDatasets]
      response.limit shouldBe 25
      response.offset shouldBe 0
      response.totalCount shouldBe 10
      response.datasets
        .filter(_.content.name != "test-ds3")
        .size shouldBe 10 //test-ds3 is not part of the returned datasets

      response.datasets
        .filter(_.content.name != dataset.name) // Filter out the "Home" dataset
        .map(_.bannerPresignedUrl.isDefined)
        .foldLeft(true)(_ && _) shouldBe true // all banner URLs should be defined
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
      val response = parsedBody.extract[PaginatedDatasets]
      response.totalCount shouldBe 2
      response.datasets
        .filter(_.content.name != dataset.name)
        .map(_.content.name) shouldBe List("test-ds1")
    }

    val request = write(SwitchOwnerRequest(colleagueUser.nodeId))

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
      val response = parsedBody.extract[PaginatedDatasets]
      response.totalCount shouldBe 1
      response.datasets
        .map(_.content.name) shouldBe List("test-ds1")
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
      val response = parsedBody.extract[PaginatedDatasets]
      response.totalCount shouldBe 1
      response.datasets
        .map(_.content.name) shouldBe List("test-ds1")
    }

    get(
      s"/paginated?withRole=Viewer",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      val response = parsedBody.extract[PaginatedDatasets]
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
      val response = parsedBody.extract[PaginatedDatasets]
      response.totalCount shouldBe 1
      response.datasets
        .map(_.content.name) shouldBe List("test-ds1")
    }

    get(
      s"/paginated?withRole=reviewer",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 400
      (parsedBody \ "message")
        .extract[String] shouldBe ("invalid parameter withRole: must be one of Vector(viewer, editor, manager, owner)")
    }

    get(
      s"/paginated?onlyMyDatasets=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      val response = parsedBody.extract[PaginatedDatasets]
      response.totalCount shouldBe 1
      response.datasets
        .map(_.content.name) shouldBe List("Home")
    }
  }

  test(
    "get all data sets for the logged in user for a text search - paginated endpoint"
  ) {

    val ds1 = createDataSet("test-ds1")
    val ds2 = createDataSet("test-ds2")
    val ds3 = createDataSet("test-ds3")
    val ds4 = createDataSet("dataset-4")
    val ds5 = createDataSet("dataset-5")
    val ds6 = createDataSet("dataset-6")
    val ds7 = createDataSet("dataset-7")
    val ds8 = createDataSet("dataset-8")
    val ds9 = createDataSet("dataset-9")
    val ds10 = createDataSet("dataset-10")
    val ds11 = createDataSet("Another Data set")
    val ds12 = createDataSet("A Data set with number 11 in the name")

    get(
      s"/paginated?query=test-ds:*", // match prefix with an explicit operator
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val response = parsedBody.extract[PaginatedDatasets]
      response.limit shouldBe 25
      response.offset shouldBe 0
      response.totalCount shouldBe 3
      response.datasets
        .map(_.content.name)
        .to[Set] shouldBe Set("test-ds1", "test-ds2", "test-ds3")
    }

    // similarly, single word queries should word the same as above, by implicitly appending the ":*" prefix
    // search operator to the search term:
    get(
      s"/paginated?query=test-ds", // match prefix implicitly
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val response = parsedBody.extract[PaginatedDatasets]
      response.limit shouldBe 25
      response.offset shouldBe 0
      response.totalCount shouldBe 3
      response.datasets
        .map(_.content.name)
        .to[Set] shouldBe Set("test-ds1", "test-ds2", "test-ds3")
    }

    // Search "dataset" AND "6" OR "9" should yield four results:
    // ds6 and ds9 are in the results because of their names
    // ds5 and ds8 are in the results because "dataset" is in their name and their int ID is 6 or 9
    get(
      s"/paginated?query=${URLEncoder.encode("dataset & (6 | 9)", UTF_8.toString())}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val response = parsedBody.extract[PaginatedDatasets]

      response.limit shouldBe 25
      response.offset shouldBe 0
      response.totalCount shouldBe 4
      response.datasets
        .map(_.content.name)
        .to[Set] shouldBe Set(
        "dataset-5",
        "dataset-6",
        "dataset-8",
        "dataset-9"
      )
    }

    // Search "datas" AND "6" OR "9" should be nothing as prefix matching on "datas" = "dataset" does not work
    // for non-simple queries:
    get(
      s"/paginated?query=${URLEncoder.encode("datas & (6 | 9)", UTF_8.toString())}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val response = parsedBody.extract[PaginatedDatasets]
      response.limit shouldBe 25
      response.offset shouldBe 0
      response.totalCount shouldBe 0
      response.datasets
        .map(_.content.name)
        .to[Set] shouldBe Set()
    }

    // Search "datazet" AND "6" OR "9" should be nothing
    get(
      s"/paginated?query=${URLEncoder.encode("datazet & (6 | 9)", UTF_8.toString())}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val response = parsedBody.extract[PaginatedDatasets]
      response.limit shouldBe 25
      response.offset shouldBe 0
      response.totalCount shouldBe 0
      response.datasets
        .map(_.content.name)
        .to[Set] shouldBe Set()
    }

    // Multi-term search should work:
    // ds6 is in the results because of its names
    // ds5 is in the results because "dataset" is in its name and its int ID is 6

    get(
      s"/paginated?query=${URLEncoder.encode("dataset 6", UTF_8.toString())}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val response = parsedBody.extract[PaginatedDatasets]
      response.limit shouldBe 25
      response.offset shouldBe 0
      response.totalCount shouldBe 2
      response.datasets
        .map(_.content.name)
        .to[Set] shouldBe Set("dataset-5", "dataset-6")
    }

    //search with just an integer should match on the integer ID and any other field
    get(
      s"/paginated?query=11",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val response = parsedBody.extract[PaginatedDatasets]

      response.limit shouldBe 25
      response.offset shouldBe 0
      response.totalCount shouldBe 2

      //the result contains ds10 and ds12.
      //the intId of ds10 matches 11
      //the name of ds12 matches 11
      response.datasets
        .map(_.content.name)
        .to[Set] shouldBe Set(
        "A Data set with number 11 in the name",
        "dataset-10"
      )
      response.datasets
        .map(_.content.intId)
        .to[Set] shouldBe Set(11, 13)
    }

  }

  test(
    "get all data sets for the logged in user with limit and offset - paginated endpoint"
  ) {
    val ds1 = createDataSet("test-ds1")
    val ds2 = createDataSet("test-ds2")
    val ds3 = createDataSet("test-ds3")

    get(
      s"/paginated?limit=2",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val response = parsedBody.extract[PaginatedDatasets]
      response.limit shouldBe 2
      response.offset shouldBe 0
      response.totalCount shouldBe 4
      response.datasets
        .map(_.content.name)
        .to[Set] shouldBe Set("test-ds1", "Home")
    }

    get(
      s"/paginated?limit=2&offset=2",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val response = parsedBody.extract[PaginatedDatasets]
      response.limit shouldBe 2
      response.offset shouldBe 2
      response.totalCount shouldBe 4
      response.datasets
        .map(_.content.name)
        .to[Set] shouldBe Set("test-ds2", "test-ds3")
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
    }

    get(
      s"/paginated?orderBy=UpdatedAt&orderDirection=Asc&limit=2",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val response = parsedBody.extract[PaginatedDatasets]

      response.limit shouldBe 2
      response.offset shouldBe 0
      response.totalCount shouldBe 5
      response.datasets
        .map(_.content.name)
        .to[Set] shouldBe Set("Home", "Test-ds1")
    }

    get(
      s"/paginated?orderBy=UpdatedAt&orderDirection=Desc&limit=2",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val response = parsedBody.extract[PaginatedDatasets]

      response.limit shouldBe 2
      response.offset shouldBe 0
      response.totalCount shouldBe 5
      response.datasets
        .map(_.content.name)
        .to[Set] shouldBe Set("A New DataSet", "Test-ds3")
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
    }

    get(
      s"/paginated?orderBy=IntId&orderDirection=Asc&limit=5",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val response = parsedBody.extract[PaginatedDatasets]

      response.limit shouldBe 5
      response.offset shouldBe 0
      response.totalCount shouldBe 5
      response.datasets
        .map(_.content.intId)
        .to[Set] shouldBe Set(1, 2, 3, 4, 5)
    }

    get(
      s"/paginated?orderBy=IntId&orderDirection=Desc&limit=2",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val response = parsedBody.extract[PaginatedDatasets]

      response.limit shouldBe 2
      response.offset shouldBe 0
      response.totalCount shouldBe 5
      response.datasets
        .map(_.content.intId)
        .to[Set] shouldBe Set(5, 4)
    }
  }

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

      val result: WrappedDataset = parsedBody
        .extract[DataSetDTO]
        .content
      result.name shouldEqual "A New DataSet"
      result.status shouldEqual "IN_REVIEW"
      result.license shouldEqual Some(License.`GNU General Public License v3.0`)
      result.tags shouldEqual List("tag1", "tag2")
    }
  }

  test("get all data sets from a collection - paginated endpoint") {
    val ds1 = createDataSet("test-ds1")
    val ds2 = createDataSet("test-ds2")
    val ds3 = createDataSet("test-ds3")

    val collection = createCollection("My Very Own New Collection")

    addDatasetToCollection(ds1, collection)
    addDatasetToCollection(ds2, collection)

    get(
      s"/paginated?collectionId=${collection.id}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val response = parsedBody.extract[PaginatedDatasets]
      response.totalCount shouldBe 2
      response.datasets
        .map(_.content.name)
        .to[Set] shouldBe Set("test-ds1", "test-ds2")
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
      (parsedBody \ "message")
        .extract[String] shouldBe ("dataset name must be less than 255 characters")
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

  test("creating a dataset should use the default data use agreement") {
    val createReq = write(CreateDataSetRequest("A New DataSet", None, List()))

    val defaultAgreement = secureContainer.dataUseAgreementManager
      .create(
        "Default data use agreement",
        "Lots of legal text",
        isDefault = true
      )
      .await
      .right
      .value

    val otherAgreement = secureContainer.dataUseAgreementManager
      .create(
        "Another data use agreement",
        "Lots of legal text",
        isDefault = false
      )
      .await
      .right
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

  test("update a dataset with a data use agreement") {
    val dataset = createDataSet("Foo")

    val agreement = secureContainer.dataUseAgreementManager
      .create("Data use agreement", "Lots of legal text")
      .await
      .right
      .value

    val updateReq =
      write(UpdateDataSetRequest(dataUseAgreementId = Some(agreement.id)))

    putJson(
      s"/${dataset.nodeId}",
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
    val collection = packageManager
      .create(
        "Bar",
        Collection,
        PackageState.READY,
        ds,
        Some(loggedInUser.id),
        None
      )
      .await
      .right
      .value
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
      body should not include ("Bar")

      val result: WrappedDataset = parsedBody
        .extract[DataSetDTO]
        .content
      result.name shouldEqual "Boom"
      result.description shouldBe ds.description
      result.status shouldEqual "IN_REVIEW"
      result.license shouldEqual Some(License.`Apache 2.0`)
      result.tags shouldEqual List("tag1", "tag2")
    }
  }

  test("update a data set should fail if name is longer than 255 characters") {
    val ds = createDataSet("Foo")
    val collection = packageManager
      .create(
        "Bar",
        Collection,
        PackageState.READY,
        ds,
        Some(loggedInUser.id),
        None
      )
      .await
      .right
      .value

    val updateReq2 = write(
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
      updateReq2,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
      body should include("dataset name must be less than 255 characters")
    }
  }

  test("remove all tags from a data set") {
    val ds = createDataSet("Foo", tags = List("tag1", "tag2"))
    val updateReq =
      write(
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

      val result: WrappedDataset = parsedBody
        .extract[DataSetDTO]
        .content
      result.tags shouldEqual List.empty[String]
    }
  }

  test("update a data set without editing tags") {
    val ds = createDataSet("Foo", tags = List("tag1", "tag2"))
    val updateReq =
      write(UpdateDataSetRequest(Some(ds.name), ds.description))

    putJson(
      s"/${ds.nodeId}",
      updateReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val result: WrappedDataset = parsedBody
        .extract[DataSetDTO]
        .content
      result.tags shouldEqual List("tag1", "tag2")
    }
  }

  test("demo user - update data - can update their own dataset") {
    val demoContainer = secureContainerBuilder(sandboxUser, sandboxOrganization)
    val sandboxDatasetStatus = demoContainer.db
      .run(demoContainer.datasetStatusManager.getDefaultStatus)
      .await

    val ds = createDataSet(
      "Foo",
      tags = List("tag1", "tag2"),
      container = demoContainer
    )
    val updateReq =
      write(UpdateDataSetRequest(Some(ds.name), ds.description))

    putJson(
      s"/${ds.nodeId}",
      updateReq,
      headers = authorizationHeader(sandboxUserJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val result: WrappedDataset = parsedBody
        .extract[DataSetDTO]
        .content
      result.tags shouldEqual List("tag1", "tag2")
    }
  }

  test(
    "demo user - update data - cannot update dataset of other demo organization users"
  ) {
    val ds = createDataSet("Foo", tags = List("tag1", "tag2"))
    val updateReq =
      write(UpdateDataSetRequest(Some(ds.name), ds.description))

    putJson(
      s"/${ds.nodeId}",
      updateReq,
      headers = authorizationHeader(sandboxUserJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
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
        secureContainer.datasetManager.get(ds.id).await.right.value
      response.getHeader(HttpHeaders.ETAG) shouldBe updatedDs.etag.asHeader
    }
  }

  test("update a data set and touch the updatedAt timestamp") {
    val dataset = createDataSet("Foo")

    putJson(
      s"/${dataset.nodeId}",
      write(UpdateDataSetRequest(Some("Bar"), dataset.description)),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    secureContainer.datasetManager
      .get(dataset.id)
      .value
      .await
      .right
      .get
      .updatedAt should be > dataset.updatedAt
  }

  test(
    "creating a readme should not cause a false If-Match error on the dataset settings"
  ) {
    val dataset = createDataSet("My Dataset")

    val readme = "#Markdown content\nA paragraph!"
    val request = write(DatasetReadmeDTO(readme = readme))

    putJson(
      s"/${dataset.nodeId}/readme",
      request,
      authorizationHeader(loggedInJwt) ++ Map(HttpHeaders.IF_MATCH -> "0")
    ) {
      status shouldBe 200

      val newReadmeAsset = secureContainer.datasetAssetsManager
        .getReadme(dataset)
        .value
        .await
        .right
        .get
        .get

      response.getHeader(HttpHeaders.ETAG) shouldBe newReadmeAsset.etag.asHeader
      response.getHeader(HttpHeaders.ETAG) should not be "0"
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
      s"/${dataset.nodeId}",
      updateReq,
      headers = authorizationHeader(loggedInJwt) ++ Map(
        HttpHeaders.IF_MATCH -> dataset.etag.asHeader
      ) ++ traceIdHeader()
    ) {
      status should equal(200)
    }
  }

  test(
    "modifying a readme should not cause a false If-Match error on the dataset settings"
  ) {
    val dataset = addBannerAndReadme(createDataSet("My Dataset"))

    val readme = "#Markdown content\nA paragraph!"
    val request = write(DatasetReadmeDTO(readme = readme))

    val existingReadme = secureContainer.datasetAssetsManager
      .getReadme(dataset)
      .value
      .await
      .right
      .get
      .get

    val etag = get(
      s"/${dataset.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      response.getHeader(HttpHeaders.ETAG)
    }

    putJson(
      s"/${dataset.nodeId}/readme",
      request,
      authorizationHeader(loggedInJwt) ++ Map(
        HttpHeaders.IF_MATCH -> existingReadme.etag.asHeader
      )
    ) {
      status shouldBe 200

      val newReadmeAsset = secureContainer.datasetAssetsManager
        .getReadme(dataset)
        .value
        .await
        .right
        .get
        .get

      response.getHeader(HttpHeaders.ETAG) shouldBe newReadmeAsset.etag.asHeader
      response.getHeader(HttpHeaders.ETAG) should not be existingReadme.etag.asHeader
    }

    val updateReq =
      write(UpdateDataSetRequest(Some("Boom"), Some("This is a dataset.")))

    putJson(
      s"/${dataset.nodeId}",
      updateReq,
      headers = authorizationHeader(loggedInJwt) ++ Map(
        HttpHeaders.IF_MATCH -> etag
      ) ++ traceIdHeader()
    ) {
      status should equal(200)
    }
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
      val result: WrappedDataset = parsedBody
        .extract[DataSetDTO]
        .content

      status should equal(200)

      result.name shouldEqual ds.name
      result.description shouldBe ds.description
      result.status shouldEqual defaultDatasetStatus.name
      result.automaticallyProcessPackages shouldBe true
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
      val result: WrappedDataset = parsedBody
        .extract[DataSetDTO]
        .content

      status should equal(200)

      result.name shouldEqual ds.name
      result.description shouldBe ds.description
      result.status shouldEqual "IN_REVIEW"
    }

    get(
      s"/${ds.nodeId}/status-log",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val results = parsedBody
        .extract[PaginatedStatusLogEntries]

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
      val result: WrappedDataset = parsedBody
        .extract[DataSetDTO]
        .content

      status should equal(200)

      result.name shouldEqual ds.name
      result.description shouldBe ds.description
      result.status shouldEqual "COMPLETED"
    }

    get(
      s"/${ds.nodeId}/status-log",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val results = parsedBody
        .extract[PaginatedStatusLogEntries]

      results.entries.length should equal(3)
      results.limit should equal(25)
      results.offset should equal(0)
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

  test("update a dataset that is not a dataset") {
    val ds = createDataSet("Foo")
    val collection = packageManager
      .create(
        "Bar",
        Collection,
        PackageState.READY,
        ds,
        Some(loggedInUser.id),
        None
      )
      .await
      .right
      .value
    val req = UpdateDataSetRequest(Some("Boom"), None)
    val createReq = write(req)

    putJson(
      s"/${collection.nodeId}",
      createReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  // delete data set
  test("delete data set") {
    val ds = createDataSet("Foo")

    delete(
      s"/${ds.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      // Maintain backwards compatibility
      response.body should equal("{}")
    }

    mockSqsClient.sentMessages.size should equal(2)
    //1 message to delete, 1 to unpublish

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
      .right
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

    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .right
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

    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .right
      .value

    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Removal)
      .await
      .right
      .value

    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Requested, PublicationType.Publication)
      .await
      .right
      .value

    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Cancelled, PublicationType.Publication)
      .await
      .right
      .value

    delete(
      s"/${ds.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      // Maintain backwards compatibility
      response.body should equal("{}")
    }

    mockSqsClient.sentMessages.size should equal(2)
    //1 message to delete, 1 to unpublish

    get(
      s"/${ds.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }

  }

  test("delete data set fails if dataset was unpublished and published again") {
    val ds = createDataSet("Foo")
    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .right
      .value

    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Removal)
      .await
      .right
      .value

    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .right
      .value

    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Requested, PublicationType.Publication)
      .await
      .right
      .value

    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Cancelled, PublicationType.Publication)
      .await
      .right
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

    // share with colleagueUser
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

  test("create and retrieve a DOI for a dataset") {

    val ds = createDataSet(name = "Foo")

    val reserveDoiRequest =
      CreateDraftDoiRequest(None, None, Some(2019), Some("abc-123"))
    val doiRequest = write(reserveDoiRequest)

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
      None,
      Some(2019),
      Some(DoiState.Draft)
    )

    val json = write(doi)
    json shouldBe """{"organizationId":1,"datasetId":1,"doi":"10.2137/abcd-1234","title":"My Dataset","url":"http://discover.pennsieve.org/datasets/1","publicationYear":2019,"state":"draft"}"""
    read[DoiDTO](json).state shouldBe Some(DoiState.Draft)

  }

  test("fail to create and retrieve DOI without proper permissions") {

    val colleagueUserTwo = userManager
      .create(externalUser.copy(email = "another"))
      .await
      .right
      .value
    organizationManager
      .addUser(loggedInOrganization, colleagueUserTwo, DBPermission.Delete)
      .await
    val colleagueTwoJwt = Authenticator.createUserToken(
      colleagueUserTwo,
      loggedInOrganization
    )(jwtConfig, insecureContainer.db, ec)

    val ds = createDataSet("Foo")

    val reserveDoiRequest =
      CreateDraftDoiRequest(
        Some("testTitle"),
        Some(IndexedSeq(CreatorDTO("Creator M", "Maker"))),
        Some(2019),
        Some("abc-123")
      )
    val doiRequest = write(reserveDoiRequest)

    postJson(
      s"/${ds.nodeId}/doi",
      doiRequest,
      headers = authorizationHeader(colleagueTwoJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }

    get(
      s"/${ds.nodeId}/doi",
      headers = authorizationHeader(colleagueTwoJwt) ++ traceIdHeader()
    ) {
      status should equal(403)

    }
  }

  test("fail to create a new DOI for a locked dataset") {
    val ds = createDataSet(name = "Foo")
    secureContainer.datasetPublicationStatusManager
      .create(ds, PublicationStatus.Requested, PublicationType.Publication)
      .await
      .right
      .value

    val reserveDoiRequest =
      CreateDraftDoiRequest(None, None, Some(2019), Some("abc-123"))
    val doiRequest = write(reserveDoiRequest)

    postJson(
      s"/${ds.nodeId}/doi",
      doiRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(423)
    }
  }

  // SHARING

  // user creates a data set and no one else should have access to it except that user
  test(
    "a user creates a data set and no one else should have access to it except that user"
  ) {
    // create
    createDataSet("My DataSet")

    get("/", headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()) {
      status should equal(200)
      parsedBody.extract[List[String]] should have size 0
    }

  }

  // 1. user creates data set that belongs to org then shares it with that org
  // only creator and users of that org should have access
  test(
    "user creates data set that belongs to org then shares it with that org only creator and users of that org should have access"
  ) {
    // create
    val myDS = createDataSet("My DataSet")

    // share with org
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

    get(s"/", headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()) {
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

  // 2. user creates data set that belongs to org and tries to share it with another org
  // should fail
  test(
    "user creates data set that belongs to org and tries to share it with another org should fail"
  ) {
    // create
    val myDS = createDataSet("My DataSet")

    // share with external org
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

  // 3. user creates data set that belongs to org and shares it with another user who belongs to that org
  // only creator and currently shared user should have access
  test(
    """user creates data set that belongs to org
      |and shares it with another user who belongs to that org then only creator
      |and currently shared user should have access""".stripMargin
  ) {
    // create
    val myDS = createDataSet("My DataSet")

    // share with colleagueUser
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

    // creator should see it
    get("/", headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()) {
      status should equal(200)
      body should include("My DataSet")
    }

    // colleagueUser should see it
    get("/", headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()) {
      status should equal(200)
      body should include("My DataSet")
    }

    get("/", headers = authorizationHeader(externalJwt) ++ traceIdHeader()) {
      status should equal(200)
      parsedBody.extract[List[String]] should have size 0
    }

    // colleague without direct permission should not see it
    val colleagueUserTwo = userManager
      .create(externalUser.copy(email = "another"))
      .await
      .right
      .value

    organizationManager
      .addUser(loggedInOrganization, colleagueUserTwo, DBPermission.Delete)
      .await

    val colleagueTwoJwt = Authenticator.createUserToken(
      colleagueUserTwo,
      loggedInOrganization
    )(jwtConfig, insecureContainer.db, ec)

    get("/", headers = authorizationHeader(colleagueTwoJwt) ++ traceIdHeader()) {
      status should equal(200)
      parsedBody.extract[List[String]] should have size 0
    }
  }

  // 4. user creates data set that belongs to org and tries to share it with a user who belongs to another org
  // should fail
  test("""user creates data set that belongs to org and tries to share it with
      |a user who belong to another org should fail""".stripMargin) {
    // create
    val myDS = createDataSet("My DataSet")

    // share with externalUser
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

  // 5. user creates data set that belongs to org then shares it with a team in that org
  // only creator and members of that team should have access
  test(
    "user creates data set that belongs to org then shares it with a team only creator and users of that team should have access"
  ) {
    // create
    val myDS = createDataSet("My DataSet")

    val myTeam = createTeam("My Team")
    teamManager.addUser(myTeam, loggedInUser, DBPermission.Delete)
    teamManager.addUser(myTeam, colleagueUser, DBPermission.Delete)
    val myOtherTeam = createTeam("My Other Team")
    teamManager.addUser(myOtherTeam, colleagueUser, DBPermission.Delete)

    // share with team
    val ids = write(List(myTeam.nodeId, myOtherTeam.nodeId))
    putJson(
      s"/${myDS.nodeId}/collaborators",
      ids,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val response = parsedBody.extract[CollaboratorChanges]

      response.changes
        .get(myTeam.nodeId)
        .value
        .success should equal(true)

      response.counts.users should equal(0)

      response.counts.teams should equal(2)
    }

    get(
      s"/${myDS.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should include("My DataSet")

      val counts = parsedBody.extract[DataSetDTO].collaboratorCounts

      counts.organizations should equal(0)

      counts.users should equal(0)
      counts.teams should equal(2)
    }

    get(s"/", headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()) {
      status should equal(200)
      body should include("My DataSet")
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

  // contributors
  test("get all contributors of a dataset") {
    val ds = createDataSet("ContributorTest")
    val orgContributorsBefore =
      secureContainer.contributorsManager.getContributors().await.right.value
    val ds1 = createDataSet("ContributorTestAgain")
    val orgContributorsAfter =
      secureContainer.contributorsManager.getContributors().await.right.value

    //Creating a dataset automatically creates the owner as a contributor. But should only add the user in the org's
    // contributors list if he's not already in it. Since we create two datasets, we should not be adding a contributor
    // for the second creation.
    assert(orgContributorsAfter.length == orgContributorsBefore.length)

    val ct1 =
      createContributor(
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
      contributors.map(_.id) shouldBe List(1, ct1.id)
    }
  }

  test("delete a contributor of a dataset") {
    val contributor =
      createContributor(
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
      val contributors = parsedBody.extract[List[ContributorDTO]]
      contributors.map(_.id) shouldBe List(1, contributor.id)
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
      val contributors = parsedBody.extract[List[ContributorDTO]]
      contributors.map(_.id) shouldBe List(1)
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

  test("move a contributor down the contributor list") {
    val ds = createDataSet("ContributorTest")

    val ct1 =
      createContributor(
        "Tester",
        "Contributor",
        "tester-contributor-move@bf.com",
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

    val ct2 =
      createContributor(
        "Tester2",
        "Contributor2",
        "tester2-contributor2-move@bf.com",
        None,
        None
      )

    val request2 = write(AddContributorRequest(ct2.id))

    putJson(
      s"/${ds.nodeId}/contributors",
      request2,
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
      contributors.length should equal(3)
      contributors.map(_.id) shouldBe List(1, ct1.id, ct2.id)

    }
    val request3 = write(SwitchContributorsOrderRequest(ct1.id, ct2.id))

    postJson(
      s"/${ds.nodeId}/contributors/switch",
      request3,
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
      contributors.length should equal(3)
      contributors.map(_.id) shouldBe List(1, ct2.id, ct1.id)

    }
  }

  // collaborators
  test("get a data set with its collaborators") {
    val ds = createDataSet("Foo")

    // share with org
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
      s"/?includeCollaboratorCounts=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      val counts = parsedBody
        .extract[List[DataSetDTO]]
        .filter(dataset => dataset.content.id == ds.nodeId)
        .head
        .collaboratorCounts

      counts.organizations should equal(1)
      counts.users should equal(1)
    }

    get(
      s"/${ds.nodeId}/collaborators",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      (parsedBody \ "organizations" \ "id").extract[List[String]] should equal(
        List(loggedInOrganization.nodeId)
      )
      (parsedBody \ "users" \ "id").extract[List[String]] should contain(
        colleagueUser.nodeId
      )
    }
  }

  test("""PUT collaborators/users does not allow owner change""".stripMargin) {
    // create
    val myDS = createDataSet("My DataSet")

    // PUT collaborators/users cannot be used to change the owner's role
    val roleChangeRequest =
      write(CollaboratorRoleDTO(loggedInUser.nodeId, Role.Editor))

    putJson(
      s"/${myDS.nodeId}/collaborators/users",
      roleChangeRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
      (parsedBody \ "message")
        .extract[String] shouldBe "To relinquish ownership of a dataset, please use the PUT /collaborators/owner endpoint."
    }

    //Let's add another user as a manager
    val roleChangeRequest3 =
      write(CollaboratorRoleDTO(colleagueUser.nodeId, Role.Manager))

    putJson(
      s"/${myDS.nodeId}/collaborators/users",
      roleChangeRequest3,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    // PUT collaborators/users cannot be used to promote a user to owner
    val roleChangeRequest2 =
      write(CollaboratorRoleDTO(colleagueUser.nodeId, Role.Owner))

    putJson(
      s"/${myDS.nodeId}/collaborators/users",
      roleChangeRequest2,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
      (parsedBody \ "message")
        .extract[String] shouldBe "Another person already owns this dataset. Please contact your organization admin for help."
    }

    // PUT collaborators/users cannot be used to change the owner's role regardless of who does it
    val roleChangeRequest4 =
      write(CollaboratorRoleDTO(loggedInUser.nodeId, Role.Editor))

    putJson(
      s"/${myDS.nodeId}/collaborators/users",
      roleChangeRequest4,
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
      (parsedBody \ "message")
        .extract[String] shouldBe "To relinquish ownership of a dataset, please use the PUT /collaborators/owner endpoint."
    }

  }

  test(
    """PUT collaborators/users is allowed during publication lockdown""".stripMargin
  ) {
    // create
    val myDS = createDataSet("My DataSet")

    addBannerAndReadme(myDS)

    val pkg =
      createPackage(myDS, "some-package", `type` = CSV)

    createFile(pkg, FileObjectType.Source, FileProcessingState.Processed)

    val orcidAuth = OrcidAuthorization(
      name = "John Doe",
      accessToken = "64918a80-dd0c-dd0c-dd0c-dd0c64918a80",
      expiresIn = 631138518,
      tokenType = "bearer",
      orcid = "0000-0012-3456-7890",
      scope = "/authenticate",
      refreshToken = "64918a80-dd0c-dd0c-dd0c-dd0c64918a80"
    )

    val updatedUser = loggedInUser.copy(orcidAuthorization = Some(orcidAuth))
    secureContainer.userManager.update(updatedUser).await

    postJson(
      s"/${myDS.nodeId}/publication/request?publicationType=publication&comments=hello%20world",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
      (parsedBody \ "datasetId")
        .extract[Int] shouldBe myDS.id
      (parsedBody \ "publicationStatus")
        .extract[PublicationStatus] shouldBe PublicationStatus.Requested
      (parsedBody \ "createdBy").extract[Int] shouldBe loggedInUser.id
      (parsedBody \ "comments").extract[String] shouldBe "hello world"

    }

    //Let's add another user as a manager
    val roleChangeRequest =
      write(CollaboratorRoleDTO(colleagueUser.nodeId, Role.Manager))

    putJson(
      s"/${myDS.nodeId}/collaborators/users",
      roleChangeRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }
  }

  test("""user1 creates data set that belongs to org
      |and switches ownership to user2, belonging to the same org
      |user1 should be made manager and user2 be made owner""".stripMargin) {
    // create
    val myDS = createDataSet("My DataSet")

    val request = write(SwitchOwnerRequest(colleagueUser.nodeId))

    putJson(
      s"/${myDS.nodeId}/collaborators/owner",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    val owner =
      secureContainer.datasetManager
        .getOwner(myDS)
        .await
        .right
        .get
    val colleagueUserRole =
      secureContainer.datasetManager
        .maxRole(myDS, colleagueUser)
        .await
        .right
        .get
    val loggedInUserRole =
      secureContainer.datasetManager
        .maxRole(myDS, loggedInUser)
        .await
        .right
        .get

    owner.nodeId should equal(colleagueUser.nodeId)
    colleagueUserRole should equal(Role.Owner)
    loggedInUserRole should equal(Role.Manager)
  }

  test("""user1 creates data set that belongs to org
         |and switches ownership to user2, not belonging to the same org
         |we should get a 404""".stripMargin) {
    // create
    val myDS = createDataSet("My DataSet")

    val request = write(SwitchOwnerRequest(externalUser.nodeId))

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
    // create
    val myDS = createDataSet("My DataSet")

    val request = write(SwitchOwnerRequest(colleagueUser.nodeId))

    putJson(
      s"/${myDS.nodeId}/collaborators/owner",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    val request2 = write(SwitchOwnerRequest(loggedInUser.nodeId))

    putJson(
      s"/${myDS.nodeId}/collaborators/owner",
      request2,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }

  }

  // UNSHARE

  // only admin user can share or unshare
  test("only admins can share and unshare") {
    val myDS = createDataSet("My DataSet")

    // share with colleagueUser
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

  // user
  test("unshare a user") {
    val myDS = createDataSet("My DataSet")

    // share with colleagueUser
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

    // colleagueUser should see it
    get(
      s"/${myDS.nodeId}",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should include("My DataSet")
    }

    deleteJson(
      s"/${myDS.nodeId}/collaborators",
      ids,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val response = parsedBody.extract[CollaboratorChanges]

      response.changes
        .get(colleagueUser.nodeId)
        .value
        .success should equal(true)

      response.counts.users should equal(0)
    }

    get(
      s"/${myDS.nodeId}",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }
  }

  // org
  test("unshare an organization") {
    val myDS = createDataSet("My DataSet")

    // share with org
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

    // colleagueUser should see it
    get(
      s"/${myDS.nodeId}",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should include("My DataSet")
    }

    deleteJson(
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
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }
  }

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

  // NEW ROLE-BASED PERMISSIONS

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

      val users = parsedBody
        .extract[List[CollaboratorRoleDTO]]

      users should contain(
        CollaboratorRoleDTO(colleagueUser.nodeId, Role.Editor)
      )
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

  // 1. user creates data set that belongs to org then shares it with that org
  // only creator and users of that org should have access
  test("""user creates data set that belongs to org
      |then shares it with that org using roles
      |then only creator and users of that org should have access""") {
    // create
    val myDS = createDataSet("My DataSet")

    // colleague cannot see unshared dataset
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

    // share with org
    val request = write(OrganizationRoleDTO(Some(Role.Viewer)))
    putJson(
      s"/${myDS.nodeId}/collaborators/organizations",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    // now colleague can access dataset
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

  test("get role of shared organization") {
    // create
    val myDS = createDataSet("My DataSet")

    get(
      s"/${myDS.nodeId}/collaborators/organizations",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[OrganizationRoleDTO].role shouldBe None
    }

    // share with org
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

  // 3. user creates data set that belongs to org and shares it with another user who belongs to that org
  // only creator and currently shared user should have access
  test(
    """user creates data set that belongs to org
      |and shares it using roles with another user who belongs to that org then only creator
      |and currently shared user should have access""".stripMargin
  ) {
    // create
    val myDS = createDataSet("My DataSet")

    // share with colleagueUser
    val request = write(CollaboratorRoleDTO(colleagueUser.nodeId, Role.Editor))
    putJson(
      s"/${myDS.nodeId}/collaborators/users",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    // creator should see it
    get(s"/", headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()) {
      status should equal(200)
      body should include("My DataSet")
    }

    // colleagueUser should see it
    get(s"/", headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()) {
      status should equal(200)
      body should include("My DataSet")
    }

    // external should not see it
    get(s"/", headers = authorizationHeader(externalJwt) ++ traceIdHeader()) {
      status should equal(200)
      parsedBody.extract[List[String]] should have size 0
    }

    // colleague without direct permission should not see it
    val colleagueUserTwo = userManager
      .create(externalUser.copy(email = "another"))
      .await
      .right
      .value
    organizationManager
      .addUser(loggedInOrganization, colleagueUserTwo, DBPermission.Delete)
      .await
    val colleagueTwoJwt = Authenticator.createUserToken(
      colleagueUserTwo,
      loggedInOrganization
    )(jwtConfig, insecureContainer.db, ec)

    get("/", headers = authorizationHeader(colleagueTwoJwt) ++ traceIdHeader()) {
      status should equal(200)
      parsedBody.extract[List[String]] should have size 0
    }
  }

  // 4. user creates data set that belongs to org and tries to share it with a user who belongs to another org
  // should fail
  test("""user creates data set that belongs to org
      |and tries to share it using roles
      |with a user who belong to another org should fail""".stripMargin) {
    // create
    val myDS = createDataSet("My DataSet")

    // share with externalUser
    val request = write(CollaboratorRoleDTO(externalUser.nodeId, Role.Editor))
    putJson(
      s"/${myDS.nodeId}/collaborators/users",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  test("unshare an organization using role endpoints") {
    val myDS = createDataSet("My DataSet")

    // share with org
    val putRequest =
      write(OrganizationRoleDTO(Some(Role.Viewer)))
    putJson(
      s"/${myDS.nodeId}/collaborators/organizations",
      putRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    // colleagueUser should see it
    get(
      s"/${myDS.nodeId}",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should include("My DataSet")
    }

    delete(
      s"/${myDS.nodeId}/collaborators/organizations",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    // cannot see it
    get(
      s"/${myDS.nodeId}",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }
  }

  // 5. user creates data set that belongs to org then shares it with a team in that org
  // only creator and members of that team should have access
  test("""user creates data set that belongs to org
      |then shares it with a team using roles
      |only creator and users of that team should have access""") {
    // create
    val myDS = createDataSet("My DataSet")

    val myTeam = createTeam("My Team")
    teamManager.addUser(myTeam, loggedInUser, DBPermission.Delete)
    teamManager.addUser(myTeam, colleagueUser, DBPermission.Delete)
    val myOtherTeam = createTeam("My Other Team")
    teamManager.addUser(myOtherTeam, colleagueUser, DBPermission.Delete)

    // share with team
    val ids = write(CollaboratorRoleDTO(myTeam.nodeId, Role.Editor)) //myOtherTeam.nodeId))
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
      body should include("My DataSet")

      val counts = parsedBody.extract[DataSetDTO].collaboratorCounts
      counts.organizations should equal(0)
      counts.users should equal(0)
      counts.teams should equal(1)
    }

    // get shared teams
    get(
      s"/${myDS.nodeId}/collaborators/teams",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody.extract[List[TeamCollaboratorRoleDTO]] should contain(
        TeamCollaboratorRoleDTO(myTeam.nodeId, myTeam.name, Role.Editor)
      )
    }

    // team mate should have access
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

  // 6. user creates data set that belongs to org then attempts to share it with a system team
  // system teams cannot be added by users
  test("""system team user creates data set that belongs to org
         |then shares it with a system team
         |users cannot perform this action""") {
    // create
    val myDS = createDataSet("My DataSet")

    val publisherTeam = organizationManager
      .getPublisherTeam(loggedInOrganization)
      .await
      .right
      .value

    // share with team
    val ids = write(CollaboratorRoleDTO(publisherTeam._1.nodeId, Role.Editor)) //myOtherTeam.nodeId))
    putJson(
      s"/${myDS.nodeId}/collaborators/teams",
      ids,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
    }

    get(
      s"/${myDS.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should include("My DataSet")

      val counts = parsedBody.extract[DataSetDTO].collaboratorCounts
      counts.organizations should equal(0)
      counts.users should equal(0)
      counts.teams should equal(0)
    }
  }

  test("cannot share with a team in another organization") {
    val myDS = createDataSet("My DataSet")

    // need a super-admin manager to create teams in an external organization
    val externalTeam = TeamManager(organizationManager)
      .create("External team", externalOrganization)
      .await
      .right
      .value
    teamManager.addUser(externalTeam, externalUser, DBPermission.Delete)

    // share with team
    val request = write(CollaboratorRoleDTO(externalTeam.nodeId, Role.Editor))
    putJson(
      s"/${myDS.nodeId}/collaborators/teams",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }

    // no shared teams
    get(
      s"/${myDS.nodeId}/collaborators/teams",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody.extract[List[TeamCollaboratorRoleDTO]].length shouldBe 0
    }
  }

  test("user can unshare with a team") {
    val ds = createDataSet("My Dataset")

    val myTeam = createTeam("My Team")
    teamManager.addUser(myTeam, loggedInUser, DBPermission.Delete)
    teamManager.addUser(myTeam, colleagueUser, DBPermission.Delete)

    val addRequest =
      write(CollaboratorRoleDTO(myTeam.nodeId, Role.Viewer))
    putJson(
      s"/${ds.nodeId}/collaborators/teams",
      addRequest,
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
        .extract[List[TeamCollaboratorRoleDTO]] should contain(
        TeamCollaboratorRoleDTO(myTeam.nodeId, myTeam.name, Role.Viewer)
      )
    }

    val removeRequest = write(RemoveCollaboratorRequest(myTeam.nodeId))
    deleteJson(
      s"/${ds.nodeId}/collaborators/teams",
      removeRequest,
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
        .extract[List[TeamCollaboratorRoleDTO]] shouldBe empty
    }

    // colleague should no longer have access
    get(
      s"/${ds.nodeId}",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }
  }

  test("user cannot unshare with a system team") {
    val ds = createDataSet("My Dataset")

    val publisherTeam = organizationManager
      .getPublisherTeam(loggedInOrganization)
      .await
      .right
      .value

    secureDataSetManager
      .addTeamCollaborator(ds, publisherTeam._1, Role.Viewer)
      .await
      .right
      .value

    get(
      s"/${ds.nodeId}/collaborators/teams",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody
        .extract[List[TeamCollaboratorRoleDTO]] should contain(
        TeamCollaboratorRoleDTO(
          publisherTeam._1.nodeId,
          publisherTeam._1.name,
          Role.Viewer
        )
      )
    }

    val removeRequest =
      write(RemoveCollaboratorRequest(publisherTeam._1.nodeId))
    deleteJson(
      s"/${ds.nodeId}/collaborators/teams",
      removeRequest,
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
        .extract[List[TeamCollaboratorRoleDTO]] should contain(
        TeamCollaboratorRoleDTO(
          publisherTeam._1.nodeId,
          publisherTeam._1.name,
          Role.Viewer
        )
      )
    }
  }

  test("get user's effective dataset role") {
    val myDS = createDataSet("My Dataset")

    get(
      s"/${myDS.nodeId}/role",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val response = parsedBody.extract[DatasetRoleResponse]
      response.userId should equal(loggedInUser.id)
      response.datasetId should equal(myDS.id)
      response.role should equal(Role.Owner)
    }

    get(s"/${myDS.nodeId}/role", headers = authorizationHeader(colleagueJwt)) {
      status should equal(403)
    }
  }

  test("get a dataset with properties and deserialize with circe") {
    val ds1 = createDataSet("test-ds1")

    createPackage(dataset = ds1, "package1", ownerId = Some(loggedInUser.id))

    createPackage(dataset = ds1, "package2", ownerId = Some(loggedInUser.id))

    get(
      s"/${ds1.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody.extract[DataSetDTO].content should equal(
        WrappedDataset(ds1, defaultDatasetStatus)
      )
    }
  }

  // GET paginated dataset packages

  test("return a page of packages for a dataset") {
    val datasetWithAPackage = createDataSet("dataset-with-a-package")
    val packageInPage = createPackage(datasetWithAPackage, "some-package")

    val expectedPackagesPage: PackagesPage =
      PackagesPage(
        packages =
          List(ExtendedPackageDTO.simple(packageInPage, datasetWithAPackage)),
        cursor = None
      )

    get(
      s"/${datasetWithAPackage.nodeId}/packages",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody.extract[PackagesPage] shouldBe expectedPackagesPage
    }
  }

  test("return a cursor to the next page of packages") {
    val datasetWithAPackage = createDataSet("dataset-with-a-bunch-of-packages")
    val packageInPage =
      createPackage(datasetWithAPackage, "some-package", ownerId = None)

    val nextPagePackage =
      createPackage(datasetWithAPackage, "next-page-package", ownerId = None)

    val packagesPageWithCursor: PackagesPage =
      PackagesPage(
        packages =
          List(ExtendedPackageDTO.simple(packageInPage, datasetWithAPackage)),
        cursor = Some(s"package:${nextPagePackage.id}")
      )

    get(
      s"/${datasetWithAPackage.nodeId}/packages",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody.extract[PackagesPage] shouldBe packagesPageWithCursor
    }
  }

  test("return the next page for a cursor") {
    val datasetWithPackages = createDataSet("dataset-with-a-bunch-of-packages")
    val packageInPage =
      createPackage(datasetWithPackages, "some-package", ownerId = None)

    val nextPagePackage =
      createPackage(datasetWithPackages, "next-page-package", ownerId = None)

    val packagesPageWithCursor: PackagesPage =
      PackagesPage(
        packages =
          List(ExtendedPackageDTO.simple(packageInPage, datasetWithPackages)),
        cursor = Some(s"package:${nextPagePackage.id}")
      )

    val nextPage =
      PackagesPage(
        packages =
          Seq(ExtendedPackageDTO.simple(nextPagePackage, datasetWithPackages)),
        None
      )

    val datasetId = datasetWithPackages.nodeId
    get(
      s"/$datasetId/packages",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[PackagesPage] shouldBe packagesPageWithCursor
    }

    val nextPageCursor = packagesPageWithCursor.cursor.get

    get(
      s"/$datasetId/packages?cursor=$nextPageCursor",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[PackagesPage] shouldBe nextPage
    }
  }

  test("return the next page for a cursor with files") {
    val datasetWithPackages = createDataSet("dataset-with-a-bunch-of-packages")
    val packageInPage =
      createPackage(datasetWithPackages, "some-package")

    val nextPagePackage =
      createPackage(datasetWithPackages, "next-page-package")

    val packagesPageWithCursor: PackagesPage =
      PackagesPage(
        packages = List(
          ExtendedPackageDTO.simple(
            packageInPage,
            datasetWithPackages,
            objects = createObjects(packageInPage)
          )
        ),
        cursor = Some(s"package:${nextPagePackage.id}")
      )

    val nextPage =
      PackagesPage(
        packages = Seq(
          ExtendedPackageDTO.simple(
            nextPagePackage,
            datasetWithPackages,
            objects = createObjects(nextPagePackage)
          )
        ),
        None
      )

    val datasetId = datasetWithPackages.nodeId
    get(
      s"/$datasetId/packages?includeSourceFiles=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[PackagesPage] shouldBe packagesPageWithCursor
    }

    val nextPageCursor = packagesPageWithCursor.cursor.get

    get(
      s"/$datasetId/packages?cursor=$nextPageCursor&includeSourceFiles=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[PackagesPage] shouldBe nextPage
    }
  }

  test("return a user specified page size") {
    val datasetUserPage = createDataSet("dataset-one-max-size-page")

    val packagesInDataset =
      (1 to 10)
        .map { i =>
          ExtendedPackageDTO.simple(
            createPackage(datasetUserPage, i.toString),
            datasetUserPage
          )
        }

    val userSpecifiedSizePage =
      PackagesPage(packagesInDataset, None)

    get(
      s"/${datasetUserPage.nodeId}/packages?pageSize=10",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[PackagesPage] shouldBe userSpecifiedSizePage
    }
  }

  test("return the requested page size for a user with a cursor") {
    val datasetUserPage = createDataSet("dataset-one-max-size-page")

    val packagesInDataset =
      (1 to 10)
        .map { i =>
          val p = createPackage(datasetUserPage, i.toString)
          ExtendedPackageDTO.simple(
            p,
            datasetUserPage,
            objects = createObjects(p)
          )
        }

    val userSpecifiedSizePage =
      PackagesPage(
        Seq(packagesInDataset.head),
        Some(s"package:${packagesInDataset(1).content.id}")
      )

    get(
      s"/${datasetUserPage.nodeId}/packages?pageSize=1&includeSourceFiles=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[PackagesPage] shouldBe userSpecifiedSizePage
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

  test("return file sources if includeSourceFiles flag is set") {
    val datasetWithAPackage = createDataSet("dataset-with-a-package")
    val packageInPage =
      createPackage(datasetWithAPackage, "some-package", `type` = CSV)

    val objects = createObjects(packageInPage)

    val expectedPackage = ExtendedPackageDTO.simple(
      packageInPage,
      datasetWithAPackage,
      objects = objects
    )

    val expectedPackagesPage: PackagesPage =
      PackagesPage(packages = List(expectedPackage), cursor = None)

    get(
      s"/${datasetWithAPackage.nodeId}/packages?includeSourceFiles=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[PackagesPage] shouldBe expectedPackagesPage
    }
  }

  test(
    "return no files if includeSourceFiles flag is set and there are only views"
  ) {
    val datasetWithAPackage =
      createDataSet("dataset-with-a-package-with-only-views")
    val packageInPage =
      createPackage(datasetWithAPackage, "some-package", `type` = CSV)

    // Create view file that should not be present in returned page
    createFile(
      packageInPage,
      FileObjectType.View,
      FileProcessingState.NotProcessable
    )

    val expectedPackage = ExtendedPackageDTO.simple(
      packageInPage,
      datasetWithAPackage,
      objects = None
    )

    val expectedPackagesPage: PackagesPage =
      PackagesPage(packages = List(expectedPackage), cursor = None)

    get(
      s"/${datasetWithAPackage.nodeId}/packages?includeSourceFiles=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[PackagesPage] shouldBe expectedPackagesPage
    }
  }

  test(
    "return packages without files if includeSourceFiles flag is set and there are no files"
  ) {
    val datasetWithAPackage =
      createDataSet("dataset-with-a-package-with-no-files")
    val packageInPage =
      createPackage(datasetWithAPackage, "some-package", `type` = CSV)

    val expectedPackage = ExtendedPackageDTO.simple(
      packageInPage,
      datasetWithAPackage,
      objects = None
    )

    val expectedPackagesPage: PackagesPage =
      PackagesPage(packages = List(expectedPackage), cursor = None)

    get(
      s"/${datasetWithAPackage.nodeId}/packages?includeSourceFiles=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[PackagesPage] shouldBe expectedPackagesPage
    }
  }

  test(
    "return up to a maximum number of files per package and include isTruncated if more remain"
  ) {
    val datasetWithAPackage =
      createDataSet("dataset-with-a-package-with-more-than-100-files")
    val packageInPage =
      createPackage(datasetWithAPackage, "some-package", `type` = CSV)

    val files = (1 to 102).map(_ => createFile(packageInPage))

    val objects: Option[TypeToSimpleFile] =
      Some(
        Map(
          FileObjectType.Source.entryName -> files
            .dropRight(2)
            .toList
            .map(SimpleFileDTO(_, packageInPage)),
          FileObjectType.View.entryName -> List.empty[SimpleFileDTO],
          FileObjectType.File.entryName -> List.empty[SimpleFileDTO]
        )
      )
    val expectedPackage =
      ExtendedPackageDTO.simple(
        packageInPage,
        datasetWithAPackage,
        objects = objects,
        isTruncated = Some(true)
      )

    val expectedPackagesPage: PackagesPage =
      PackagesPage(packages = List(expectedPackage), cursor = None)

    get(
      s"/${datasetWithAPackage.nodeId}/packages?includeSourceFiles=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[PackagesPage] shouldBe expectedPackagesPage
    }
  }

  test("return packages matching a file name in their sources") {
    val dataset =
      createDataSet("dataset-with-packages-and-files")
    val packageInPage =
      createPackage(dataset, "some-package", `type` = CSV)
    val file1 = createFile(packageInPage, name = "plop")
    val file2 = createFile(packageInPage, name = "plip")

    val otherPackage =
      createPackage(dataset, "some-other-package", `type` = CSV)
    val otherFile = createFile(otherPackage, name = "plap")

    val objects: Option[TypeToSimpleFile] =
      Some(
        Map(
          FileObjectType.Source.entryName -> List(
            SimpleFileDTO(file1, packageInPage)
          ),
          FileObjectType.View.entryName -> List.empty[SimpleFileDTO],
          FileObjectType.File.entryName -> List.empty[SimpleFileDTO]
        )
      )
    val expectedPackage =
      ExtendedPackageDTO.simple(packageInPage, dataset, objects = objects)

    val expectedPackagesPage: PackagesPage =
      PackagesPage(packages = List(expectedPackage), cursor = None)

    get(
      s"/${dataset.nodeId}/packages?filename=plop&includeSourceFiles=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[PackagesPage] shouldBe expectedPackagesPage
    }

    get(
      s"/${dataset.nodeId}/packages?filename=plap&includeSourceFiles=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[PackagesPage].packages.length shouldBe 1
    }
  }

  test("return a filtered list of packages based on type") {
    val datasetWithAPackage = createDataSet("dataset-with-a-CSV-package")
    val packageInPage =
      createPackage(datasetWithAPackage, "some-package", `type` = CSV)

    // Create package that should not be in page
    createPackage(datasetWithAPackage, "some-package")

    val expectedPackage = ExtendedPackageDTO.simple(
      packageInPage,
      datasetWithAPackage,
      objects = None
    )

    val expectedPackagesPage: PackagesPage =
      PackagesPage(packages = List(expectedPackage), cursor = None)

    get(
      s"/${datasetWithAPackage.nodeId}/packages?types=CSV",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[PackagesPage] shouldBe expectedPackagesPage
    }
  }

  test("return a filtered list of packages based on type ignoring case of type") {
    val datasetWithAPackage = createDataSet("dataset-with-a-CSV-package")
    val packageInPage =
      createPackage(datasetWithAPackage, "some-package", `type` = CSV)

    // Create package that should not be in page
    createPackage(datasetWithAPackage, "some-package")

    val expectedPackage = ExtendedPackageDTO.simple(
      packageInPage,
      datasetWithAPackage,
      objects = None
    )

    val expectedPackagesPage: PackagesPage =
      PackagesPage(packages = List(expectedPackage), cursor = None)

    get(
      s"/${datasetWithAPackage.nodeId}/packages?types=csv",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[PackagesPage] shouldBe expectedPackagesPage
    }
  }

  test("return a filtered list of packages based on type including files") {
    val datasetWithAPackage =
      createDataSet("dataset-with-a-csv-package-and-source")
    val packageInPage =
      createPackage(datasetWithAPackage, "some-package", `type` = CSV)

    // Create package that should not be in page
    createPackage(datasetWithAPackage, "some-package")

    val expectedPackage = ExtendedPackageDTO.simple(
      packageInPage,
      datasetWithAPackage,
      objects = createObjects(packageInPage)
    )

    val expectedPackagesPage: PackagesPage =
      PackagesPage(packages = List(expectedPackage), cursor = None)

    get(
      s"/${datasetWithAPackage.nodeId}/packages?types=CSV&includeSourceFiles=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[PackagesPage] shouldBe expectedPackagesPage
    }
  }

  test("return packages for a set of package types with files") {
    val datasetWithAPackage =
      createDataSet("dataset-with-a-CSV-and-PDF-package")
    val pdfPackage =
      ExtendedPackageDTO.simple(
        createPackage(datasetWithAPackage, "some-pdf", `type` = PDF),
        datasetWithAPackage,
        objects = None
      )

    // Create package that should not be in page
    createPackage(datasetWithAPackage, "some-collection")

    val csvPackage =
      createPackage(datasetWithAPackage, "some-csv", `type` = CSV)

    val objects = createObjects(csvPackage)

    val csvPackageDTO = ExtendedPackageDTO.simple(
      csvPackage,
      datasetWithAPackage,
      objects = objects
    )

    val expectedPackagesPage: PackagesPage =
      PackagesPage(packages = List(pdfPackage, csvPackageDTO), cursor = None)

    get(
      s"/${datasetWithAPackage.nodeId}/packages?types=CSV:pdf&includeSourceFiles=true&pageSize=5",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[PackagesPage] shouldBe expectedPackagesPage
    }
  }

  test("return packages for a set of package types") {
    val datasetWithAPackage =
      createDataSet("dataset-with-a-CSV-and-PDF-package")
    val pdfPackage =
      ExtendedPackageDTO.simple(
        createPackage(datasetWithAPackage, "some-pdf", `type` = PDF),
        datasetWithAPackage,
        objects = None
      )

    // Create package that should not be in page
    createPackage(datasetWithAPackage, "some-collection")

    val csvPackage =
      createPackage(datasetWithAPackage, "some-csv", `type` = CSV)

    val csvPackageDTO =
      ExtendedPackageDTO.simple(csvPackage, datasetWithAPackage)

    val expectedPackagesPage: PackagesPage =
      PackagesPage(packages = List(pdfPackage, csvPackageDTO), cursor = None)

    get(
      s"/${datasetWithAPackage.nodeId}/packages?types=CSV:pdf&pageSize=5",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      parsedBody.extract[PackagesPage] shouldBe expectedPackagesPage
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

  test("get multiple packages by id and node id") {
    val dataset = createDataSet("My Dataset")

    val pkg1 = packageManager
      .create(
        "Foo14",
        PackageType.PDF,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .right
      .value

    val pkg2 = packageManager
      .create(
        "Foo15",
        PackageType.PDF,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .right
      .value

    get(
      s"/${dataset.nodeId}/packages/batch?packageId=${pkg1.id}&packageId=${pkg2.nodeId}",
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
    val dataset = createDataSet("My Dataset")

    val pkg = packageManager
      .create(
        "Foo14",
        PackageType.PDF,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .right
      .value

    get(
      s"/${dataset.nodeId}/packages/batch?packageId=${pkg.id}&packageId=34839524",
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
    val dataset = createDataSet("My Dataset")

    val pkg = packageManager
      .create(
        "Foo14",
        PackageType.PDF,
        PackageState.DELETING,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .right
      .value

    get(
      s"/${dataset.nodeId}/packages/batch?packageId=${pkg.id}",
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

  def currentPublicationStatus(
  )(implicit
    dataset: Dataset
  ): Option[PublicationStatus] = {
    get(
      dataset.nodeId,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      val dto = parsedBody.extract[DataSetDTO]
      Some(dto.publication.status)
    }
  }

  def currentPublicationType(
  )(implicit
    dataset: Dataset
  ): Option[PublicationType] = {
    get(
      dataset.nodeId,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      val dto = parsedBody.extract[DataSetDTO]
      dto.publication.`type`
    }
  }

  def initializePublicationTest(
    assignPublisherUserDirectlyToDataset: Boolean = true
  ): Dataset = {
    val dataset = createDataSet("My Dataset")
    addBannerAndReadme(dataset)

    val pkg =
      createPackage(dataset, "some-package", `type` = CSV)

    createFile(pkg, FileObjectType.Source, FileProcessingState.Processed)

    val orcidAuth = OrcidAuthorization(
      name = "John Doe",
      accessToken = "64918a80-dd0c-dd0c-dd0c-dd0c64918a80",
      expiresIn = 631138518,
      tokenType = "bearer",
      orcid = "0000-0012-3456-7890",
      scope = "/authenticate",
      refreshToken = "64918a80-dd0c-dd0c-dd0c-dd0c64918a80"
    )

    val updatedUser = loggedInUser.copy(orcidAuthorization = Some(orcidAuth))
    secureContainer.userManager.update(updatedUser).await

    val publisherTeam = secureContainer.organizationManager
      .getPublisherTeam(secureContainer.organization)
      .await
      .right
      .value
    secureContainer.teamManager
      .addUser(publisherTeam._1, colleagueUser, DBPermission.Administer)
      .await
      .right
      .value

    if (assignPublisherUserDirectlyToDataset) {
      secureContainer.datasetManager.addUserCollaborator(
        dataset,
        colleagueUser,
        Role.Manager
      )
    }

    dataset
  }

  def publisherTeamStateCorrect(
    publicationStatus: Option[PublicationStatus]
  )(implicit
    dataset: Dataset
  ): Boolean = {
    val publisherTeam = organizationManager
      .getPublisherTeam(loggedInOrganization)
      .await
      .right
      .value
    val hasPublisherTeam = secureDataSetManager
      .getTeamCollaborators(dataset)
      .await
      .right
      .value
      .map(_._1) contains publisherTeam._1

    if (publicationStatus.isDefined && (PublicationStatus.lockedStatuses contains publicationStatus.get)) {
      hasPublisherTeam
    } else {
      !hasPublisherTeam
    }
  }

  def publicationRequestResult(
    publicationStatus: PublicationStatus,
    publicationType: PublicationType,
    headers: Map[String, String] = Map.empty
  )(implicit
    dataset: Dataset
  ): (Int, Option[PublicationStatus], Option[PublicationType], Boolean) = {
    val urlPrefix = s"/${dataset.nodeId}/publication/"
    val urlSuffix = s"?publicationType=${publicationType.entryName}"
    val url = publicationStatus match {
      case PublicationStatus.Requested => urlPrefix + "request" + urlSuffix
      case PublicationStatus.Cancelled => urlPrefix + "cancel" + urlSuffix
      case PublicationStatus.Rejected => urlPrefix + "reject" + urlSuffix
      case PublicationStatus.Accepted => urlPrefix + "accept" + urlSuffix
      case _ => s"/${dataset.id}/publication/complete"
    }

    val requestHeaders = if (headers == Map.empty) {
      if (PublicationStatus.systemStatuses contains publicationStatus) {
        jwtServiceAuthorizationHeader(loggedInOrganization)
      } else {
        authorizationHeader(
          if (PublicationStatus.publisherStatuses contains publicationStatus)
            colleagueJwt
          else loggedInJwt
        )
      }
    } else headers

    publicationStatus match {
      case PublicationStatus.Completed | PublicationStatus.Failed =>
        val requestBody = write(
          PublishCompleteRequest(
            Some(1),
            1,
            Some(OffsetDateTime.now),
            if (publicationStatus == PublicationStatus.Completed)
              PublishStatus.PublishSucceeded
            else PublishStatus.PublishFailed,
            success = publicationStatus == PublicationStatus.Completed,
            error = None
          )
        )
        putJson(url, requestBody, requestHeaders) {
          val currentStatus = currentPublicationStatus
          (
            status,
            currentStatus,
            currentPublicationType,
            publisherTeamStateCorrect(currentStatus)
          )
        }
      case _ =>
        postJson(url, "", requestHeaders) {
          val currentStatus = currentPublicationStatus
          (
            status,
            currentStatus,
            currentPublicationType,
            publisherTeamStateCorrect(currentStatus)
          )
        }
    }

  }

  test(
    "2 step publishing - publication - request > cancel > request > reject > request > accept > complete > unpublish"
  ) {

    implicit val dataset: Dataset =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)

    currentPublicationStatus shouldBe Some(PublicationStatus.Draft)
    currentPublicationType shouldBe None

    val alreadySentMessagesSubjectList = insecureContainer.emailer
      .asInstanceOf[LoggingEmailer]
      .sentEmails
      .map(_.subject)
      .toList

    postJson(
      s"/${dataset.nodeId}/publication/request?publicationType=publication&comments=hello%20world",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
      (parsedBody \ "datasetId")
        .extract[Int] shouldBe dataset.id
      (parsedBody \ "publicationStatus")
        .extract[PublicationStatus] shouldBe PublicationStatus.Requested
      (parsedBody \ "createdBy").extract[Int] shouldBe loggedInUser.id
      (parsedBody \ "comments").extract[String] shouldBe "hello world"

    }

    currentPublicationStatus shouldBe Some(PublicationStatus.Requested)
    currentPublicationType shouldBe Some(PublicationType.Publication)

    postJson(
      s"/${dataset.nodeId}/publication/reject?publicationType=publication&comments=hello%20world",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
      (parsedBody \ "datasetId")
        .extract[Int] shouldBe dataset.id
      (parsedBody \ "publicationStatus")
        .extract[PublicationStatus] shouldBe PublicationStatus.Rejected
      (parsedBody \ "publicationType")
        .extract[PublicationType] shouldBe PublicationType.Publication
      (parsedBody \ "createdBy").extract[Int] shouldBe colleagueUser.id
      (parsedBody \ "comments").extract[String] shouldBe "hello world"

    }

    currentPublicationStatus shouldBe Some(PublicationStatus.Rejected)
    currentPublicationType shouldBe Some(PublicationType.Publication)

    postJson(
      s"/${dataset.nodeId}/publication/request?publicationType=publication",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
      (parsedBody \ "datasetId")
        .extract[Int] shouldBe dataset.id
      (parsedBody \ "publicationStatus")
        .extract[PublicationStatus] shouldBe PublicationStatus.Requested
      (parsedBody \ "publicationType")
        .extract[PublicationType] shouldBe PublicationType.Publication
      (parsedBody \ "createdBy").extract[Int] shouldBe loggedInUser.id
      (parsedBody \ "comments").extract[Option[String]] shouldBe None

    }

    currentPublicationStatus shouldBe Some(PublicationStatus.Requested)
    currentPublicationType shouldBe Some(PublicationType.Publication)

    postJson(
      s"/${dataset.nodeId}/publication/cancel?publicationType=publication",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
      (parsedBody \ "datasetId")
        .extract[Int] shouldBe dataset.id
      (parsedBody \ "publicationStatus")
        .extract[PublicationStatus] shouldBe PublicationStatus.Cancelled
      (parsedBody \ "publicationType")
        .extract[PublicationType] shouldBe PublicationType.Publication
      (parsedBody \ "createdBy").extract[Int] shouldBe loggedInUser.id
      (parsedBody \ "comments").extract[Option[String]] shouldBe None

    }

    currentPublicationStatus shouldBe Some(PublicationStatus.Cancelled)
    currentPublicationType shouldBe Some(PublicationType.Publication)

    postJson(
      s"/${dataset.nodeId}/publication/request?publicationType=publication",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
      (parsedBody \ "datasetId")
        .extract[Int] shouldBe dataset.id
      (parsedBody \ "publicationStatus")
        .extract[PublicationStatus] shouldBe PublicationStatus.Requested
      (parsedBody \ "publicationType")
        .extract[PublicationType] shouldBe PublicationType.Publication
      (parsedBody \ "createdBy").extract[Int] shouldBe loggedInUser.id
      (parsedBody \ "comments").extract[Option[String]] shouldBe None

    }

    currentPublicationStatus shouldBe Some(PublicationStatus.Requested)
    currentPublicationType shouldBe Some(PublicationType.Publication)

    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Publication
    ) shouldBe (201, Some(PublicationStatus.Accepted), Some(
      PublicationType.Publication
    ), true)

    publicationRequestResult(
      PublicationStatus.Completed,
      PublicationType.Publication
    ) shouldBe (200, Some(PublicationStatus.Completed), Some(
      PublicationType.Publication
    ), true)

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Removal
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Removal
    ), true)

    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Removal
    ) shouldBe (201, Some(PublicationStatus.Completed), Some(
      PublicationType.Removal
    ), true)

    val sentMessages = insecureContainer.emailer
      .asInstanceOf[LoggingEmailer]
      .sentEmails

    sentMessages.map(_.subject).toList shouldBe List.concat(
      alreadySentMessagesSubjectList,
      List(
        "Dataset Submitted for Review", //submitted
        "Dataset Revision needed", //rejected
        "Dataset Submitted for Review", //submitted
        "Dataset Submitted for Review", //submitted again after cancel (cancel doe snot send email for now)
        "Dataset Accepted", //accepted
        "Dataset Published to Pennsieve Discover" //published
      )
    )
  }

  test(
    "2 step publishing - publication - request > modify dataset is forbidden"
  ) {

    implicit val dataset: Dataset =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)

    currentPublicationStatus shouldBe Some(PublicationStatus.Draft)
    currentPublicationType shouldBe None

    postJson(
      s"/${dataset.nodeId}/publication/request?publicationType=publication&comments=hello%20world",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
      (parsedBody \ "datasetId")
        .extract[Int] shouldBe dataset.id
      (parsedBody \ "publicationStatus")
        .extract[PublicationStatus] shouldBe PublicationStatus.Requested
      (parsedBody \ "createdBy").extract[Int] shouldBe loggedInUser.id
      (parsedBody \ "comments").extract[String] shouldBe "hello world"

    }

    currentPublicationStatus shouldBe Some(PublicationStatus.Requested)
    currentPublicationType shouldBe Some(PublicationType.Publication)

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
      s"/${dataset.nodeId}",
      updateReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 423
    }

    delete(
      s"/${dataset.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 423
    }

  }

  test(
    "2 step publishing - revision - request > cancel > request > reject > request > accept > complete"
  ) {

    val alreadySentMessagesSubjectList = insecureContainer.emailer
      .asInstanceOf[LoggingEmailer]
      .sentEmails
      .map(_.subject)
      .toList

    implicit val dataset: Dataset =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)

    // initialize an already published dataset
    secureContainer.datasetPublicationStatusManager
      .create(
        dataset,
        PublicationStatus.Completed,
        PublicationType.Publication,
        None
      )
      .await
      .right
      .value

    postJson(
      s"/${dataset.nodeId}/publication/request?publicationType=revision&comments=hello%20world",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
      (parsedBody \ "datasetId")
        .extract[Int] shouldBe dataset.id
      (parsedBody \ "publicationStatus")
        .extract[PublicationStatus] shouldBe PublicationStatus.Requested
      (parsedBody \ "publicationType")
        .extract[PublicationType] shouldBe PublicationType.Revision
      (parsedBody \ "createdBy").extract[Int] shouldBe loggedInUser.id
      (parsedBody \ "comments").extract[String] shouldBe "hello world"

    }

    currentPublicationStatus shouldBe Some(PublicationStatus.Requested)
    currentPublicationType shouldBe Some(PublicationType.Revision)

    postJson(
      s"/${dataset.nodeId}/publication/reject?publicationType=revision&comments=hello%20world",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
      (parsedBody \ "datasetId")
        .extract[Int] shouldBe dataset.id
      (parsedBody \ "publicationStatus")
        .extract[PublicationStatus] shouldBe PublicationStatus.Rejected
      (parsedBody \ "publicationType")
        .extract[PublicationType] shouldBe PublicationType.Revision
      (parsedBody \ "createdBy").extract[Int] shouldBe colleagueUser.id
      (parsedBody \ "comments").extract[String] shouldBe "hello world"

    }

    currentPublicationStatus shouldBe Some(PublicationStatus.Rejected)
    currentPublicationType shouldBe Some(PublicationType.Revision)

    postJson(
      s"/${dataset.nodeId}/publication/request?publicationType=revision",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
      (parsedBody \ "datasetId")
        .extract[Int] shouldBe dataset.id
      (parsedBody \ "publicationStatus")
        .extract[PublicationStatus] shouldBe PublicationStatus.Requested
      (parsedBody \ "publicationType")
        .extract[PublicationType] shouldBe PublicationType.Revision
      (parsedBody \ "createdBy").extract[Int] shouldBe loggedInUser.id
      (parsedBody \ "comments").extract[Option[String]] shouldBe None

    }

    currentPublicationStatus shouldBe Some(PublicationStatus.Requested)
    currentPublicationType shouldBe Some(PublicationType.Revision)

    postJson(
      s"/${dataset.nodeId}/publication/cancel?publicationType=revision",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
      (parsedBody \ "datasetId")
        .extract[Int] shouldBe dataset.id
      (parsedBody \ "publicationStatus")
        .extract[PublicationStatus] shouldBe PublicationStatus.Cancelled
      (parsedBody \ "createdBy").extract[Int] shouldBe loggedInUser.id
      (parsedBody \ "comments").extract[Option[String]] shouldBe None

    }

    currentPublicationStatus shouldBe Some(PublicationStatus.Cancelled)
    currentPublicationType shouldBe Some(PublicationType.Revision)

    postJson(
      s"/${dataset.nodeId}/publication/request?publicationType=revision",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
      (parsedBody \ "datasetId")
        .extract[Int] shouldBe dataset.id
      (parsedBody \ "publicationStatus")
        .extract[PublicationStatus] shouldBe PublicationStatus.Requested
      (parsedBody \ "publicationType")
        .extract[PublicationType] shouldBe PublicationType.Revision
      (parsedBody \ "createdBy").extract[Int] shouldBe loggedInUser.id
      (parsedBody \ "comments").extract[Option[String]] shouldBe None

    }

    currentPublicationStatus shouldBe Some(PublicationStatus.Requested)
    currentPublicationType shouldBe Some(PublicationType.Revision)

    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Revision,
      authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) shouldBe (201, Some(PublicationStatus.Completed), Some(
      PublicationType.Revision
    ), true)

    val sentMessages = insecureContainer.emailer
      .asInstanceOf[LoggingEmailer]
      .sentEmails

    sentMessages.map(_.subject).toList shouldBe List.concat(
      alreadySentMessagesSubjectList,
      List("Dataset Revision")
    )
  }

  test("2 step publishing - revision can be requested by a manager") {
    implicit val dataset: Dataset =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)

    // initialize an already published dataset
    secureContainer.datasetPublicationStatusManager
      .create(
        dataset,
        PublicationStatus.Completed,
        PublicationType.Publication,
        None
      )
      .await
      .right
      .value

    secureContainer.datasetManager
      .addUserCollaborator(dataset, colleagueUser, Role.Editor)
      .await

    postJson(
      s"/${dataset.nodeId}/publication/request?publicationType=revision",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 403
    }

    secureContainer.datasetManager
      .addUserCollaborator(dataset, colleagueUser, Role.Manager)
      .await

    postJson(
      s"/${dataset.nodeId}/publication/request?publicationType=revision",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
      (parsedBody \ "datasetId")
        .extract[Int] shouldBe dataset.id
      (parsedBody \ "publicationStatus")
        .extract[PublicationStatus] shouldBe PublicationStatus.Requested
      (parsedBody \ "publicationType")
        .extract[PublicationType] shouldBe PublicationType.Revision
      (parsedBody \ "createdBy").extract[Int] shouldBe colleagueUser.id
    }

    currentPublicationStatus shouldBe Some(PublicationStatus.Requested)
    currentPublicationType shouldBe Some(PublicationType.Revision)

  }

  test("2 step publishing - can cancel rejected dataset") {

    implicit val dataset: Dataset =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)

    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Rejected, PublicationType.Publication)
      .await
      .right
      .value

    post(
      s"/${dataset.nodeId}/publication/cancel?publicationType=publication",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    currentPublicationStatus shouldBe Some(PublicationStatus.Cancelled)
    currentPublicationType shouldBe Some(PublicationType.Publication)
  }

  test("2 step publishing - fail to embargo dataset without release date") {
    val dataset: Dataset =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)

    post(
      s"/${dataset.nodeId}/publication/request?publicationType=embargo",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 400
    }
  }

  test("2 step publishing - invalid embargo release date") {
    // Greater than 1 year in the future
    postJson(
      s"/${dataset.nodeId}/publication/request?publicationType=embargo&embargoReleaseDate=2040-01-01",
      "",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status shouldBe 400
    }

    // In the past
    postJson(
      s"/${dataset.nodeId}/publication/request?publicationType=embargo&embargoReleaseDate=2020-01-01",
      "",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status shouldBe 400
    }
  }

  test(
    "2 step publishing - embargo - request > accept > publication successful"
  ) {
    val alreadySentMessagesSubjectList = insecureContainer.emailer
      .asInstanceOf[LoggingEmailer]
      .sentEmails
      .map(_.subject)
      .toList

    implicit val dataset: Dataset =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)

    val embargoReleaseDate = LocalDate.now.plusWeeks(1)

    currentPublicationStatus shouldBe Some(PublicationStatus.Draft)
    currentPublicationType shouldBe None

    postJson(
      s"/${dataset.nodeId}/publication/request?publicationType=embargo&comments=requested&embargoReleaseDate=$embargoReleaseDate",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    currentPublicationStatus shouldBe Some(PublicationStatus.Requested)
    currentPublicationType shouldBe Some(PublicationType.Embargo)

    postJson(
      s"/${dataset.nodeId}/publication/accept?publicationType=embargo&comments=accepted",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    currentPublicationStatus shouldBe Some(PublicationStatus.Accepted)
    currentPublicationType shouldBe Some(PublicationType.Embargo)

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
      s"/${dataset.id}/publication/complete",
      request,
      headers = jwtServiceAuthorizationHeader(loggedInOrganization) ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    currentPublicationStatus shouldBe Some(PublicationStatus.Completed)
    currentPublicationType shouldBe Some(PublicationType.Embargo)

    // The embargo release date should be propagated through the workflow
    get(
      s"/${dataset.nodeId}",
      headers = jwtServiceAuthorizationHeader(loggedInOrganization) ++ traceIdHeader()
    ) {

      status shouldBe 200

      parsedBody
        .extract[DataSetDTO]
        .publication
        .embargoReleaseDate shouldBe Some(embargoReleaseDate)
    }
    val sentMessages = insecureContainer.emailer
      .asInstanceOf[LoggingEmailer]
      .sentEmails

    sentMessages.map(_.subject).toList shouldBe List.concat(
      alreadySentMessagesSubjectList,
      List("Dataset Accepted", "Dataset Under Embargo")
    )

  }

  test("2 step publishing - cannot embargo dataset after it has been published") {

    implicit val dataset: Dataset =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)

    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .right
      .value

    post(
      s"/${dataset.nodeId}/publication/request?publicationType=embargo",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 400
    }
  }

  test("2 step publishing - can embargo dataset after it has been removed") {

    implicit val dataset: Dataset =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)

    val embargoReleaseDate = LocalDate.now.plusWeeks(1)

    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Completed, PublicationType.Removal)
      .await
      .right
      .value

    post(
      s"/${dataset.nodeId}/publication/request?publicationType=embargo&embargoReleaseDate=$embargoReleaseDate",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }
  }

  test("2 step publishing - can remove embargoed dataset") {

    val dataset: Dataset =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)

    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Completed, PublicationType.Embargo)
      .await
      .right
      .value

    post(
      s"/${dataset.nodeId}/publication/request?publicationType=removal",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }
  }

  test("2 step publishing - release - request > accept > release successful") {
    val alreadySentMessagesSubjectList = insecureContainer.emailer
      .asInstanceOf[LoggingEmailer]
      .sentEmails
      .map(_.subject)
      .toList

    implicit val dataset: Dataset =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)

    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Completed, PublicationType.Embargo)
      .await
      .right
      .value

    post(
      s"/${dataset.nodeId}/publication/request?publicationType=release&comments=releasing-early",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    currentPublicationStatus shouldBe Some(PublicationStatus.Requested)
    currentPublicationType shouldBe Some(PublicationType.Release)

    post(
      s"/${dataset.nodeId}/publication/accept?publicationType=release&comments=ok",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    currentPublicationStatus shouldBe Some(PublicationStatus.Accepted)
    currentPublicationType shouldBe Some(PublicationType.Release)

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
      s"/${dataset.id}/publication/complete",
      request,
      headers = jwtServiceAuthorizationHeader(loggedInOrganization) ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    currentPublicationStatus shouldBe Some(PublicationStatus.Completed)
    currentPublicationType shouldBe Some(PublicationType.Release)

    val sentMessages = insecureContainer.emailer
      .asInstanceOf[LoggingEmailer]
      .sentEmails

    sentMessages.map(_.subject).toList shouldBe List.concat(
      alreadySentMessagesSubjectList,
      List("Dataset Release Accepted", "Dataset Released")
    )
  }

  test(
    "2 step publishing - release rejected dataset - request > accept > release successful"
  ) {

    implicit val dataset: Dataset =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)

    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Rejected, PublicationType.Release)
      .await
      .right
      .value

    post(
      s"/${dataset.nodeId}/publication/request?publicationType=release&comments=releasing-early",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }
  }

  test(
    "2 step publishing - release cancelled dataset - request > accept > release successful"
  ) {

    implicit val dataset: Dataset =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)

    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Cancelled, PublicationType.Release)
      .await
      .right
      .value

    post(
      s"/${dataset.nodeId}/publication/request?publicationType=release&comments=releasing-early",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }
  }

  test("2 step publishing - can only release embargoed datasets") {

    implicit val dataset: Dataset =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)

    for {
      publicationType <- Seq(
        PublicationType.Publication,
        PublicationType.Revision,
        PublicationType.Removal,
        PublicationType.Release
      )
    } yield {
      secureContainer.datasetPublicationStatusManager
        .create(dataset, PublicationStatus.Completed, publicationType)
        .await
        .right
        .value

      post(
        s"/${dataset.nodeId}/publication/request?publicationType=release&comments=releasing-early",
        headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
      ) {
        status shouldBe 400
      }
    }
  }

  test("2 step publishing - can remove released dataset") {

    val dataset: Dataset =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)

    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Completed, PublicationType.Release)
      .await
      .right
      .value

    post(
      s"/${dataset.nodeId}/publication/request?publicationType=removal",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }
  }

  test("2 step publishing - service user can release dataset with one request") {

    implicit val dataset: Dataset =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)

    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Completed, PublicationType.Embargo)
      .await
      .right
      .value

    post(
      s"/${dataset.id}/publication/release",
      headers = jwtServiceAuthorizationHeader(loggedInOrganization)
    ) {
      status shouldBe 201
    }

    secureContainer.datasetPublicationStatusManager
      .getLogByDataset(dataset.id, sortAscending = true)
      .await
      .right
      .value
      .toList
      .map(s => (s.publicationType, s.publicationStatus)) shouldBe List(
      (PublicationType.Embargo, PublicationStatus.Completed),
      (PublicationType.Release, PublicationStatus.Requested),
      (PublicationType.Release, PublicationStatus.Accepted)
    )

    // Should notify Discover
    mockPublishClient.releaseRequests should contain(
      loggedInOrganization.id,
      dataset.id
    )
  }

  test("2 step publishing - normal user cannot release dataset in one step") {

    implicit val dataset: Dataset =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)

    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Completed, PublicationType.Embargo)
      .await
      .right
      .value

    post(
      s"/${dataset.id}/publication/release",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 403
    }

    currentPublicationStatus shouldBe Some(PublicationStatus.Completed)
    currentPublicationType shouldBe Some(PublicationType.Embargo)
  }

  test("grant preview access to embargoed dataset") {
    implicit val dataset: Dataset =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)

    // The endpoint should work with integer IDs as well as node IDs:
    get(
      s"/${dataset.id}/publication/preview",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody.extract[List[DatasetPreviewerDTO]] shouldBe empty
    }

    postJson(
      s"/${dataset.nodeId}/publication/preview",
      write(GrantPreviewAccessRequest(colleagueUser.id)),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    get(
      s"/${dataset.nodeId}/publication/preview",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody
        .extract[List[DatasetPreviewerDTO]]
        .map(dto => (dto.user.email, dto.embargoAccess)) shouldBe List(
        (colleagueUser.email, EmbargoAccess.Granted)
      )
    }

    deleteJson(
      s"/${dataset.id}/publication/preview",
      write(RemovePreviewAccessRequest(colleagueUser.id)),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    get(
      s"/${dataset.nodeId}/publication/preview",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody.extract[List[DatasetPreviewerDTO]] shouldBe empty
    }
  }

  test(
    "request and accept preview access to embargoed dataset with a data use agreement"
  ) {

    val agreement: DataUseAgreement =
      createDataUseAgreement("AGREEMENT-1", "some text")

    val dataset: Dataset =
      createDataSet("Embargoed dataset", dataUseAgreement = Some(agreement))

    val alreadySent = insecureContainer.emailer
      .asInstanceOf[LoggingEmailer]
      .sentEmails
      .map(e => (e.subject, e.to.address))
      .toList

    val serviceHeader =
      jwtServiceAuthorizationHeader(loggedInOrganization, Some(dataset))

    get(
      s"/${dataset.nodeId}/publication/preview",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody
        .extract[List[DatasetPreviewerDTO]]
        .map(dto => (dto.user.email, dto.embargoAccess)) shouldBe empty
    }

    // Fail if not a service request:
    postJson(
      s"/publication/preview/request",
      write(PreviewAccessRequest(dataset.id, loggedInUser.id)),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 403
    }

    // If the dataset isn't embargoed, the preview request will fail:
    postJson(
      s"/publication/preview/request",
      write(PreviewAccessRequest(dataset.id, loggedInUser.id)),
      headers = serviceHeader ++ traceIdHeader()
    ) {
      status shouldBe 400
      body should include("must be under embargo")
    }

    // Set status as embargoed:
    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Completed, PublicationType.Embargo)
      .await
      .right
      .value

    // Fail if the wrong agreement is signed
    postJson(
      s"/publication/preview/request",
      write(PreviewAccessRequest(dataset.id, loggedInUser.id, Some(9999999))),
      headers = serviceHeader ++ traceIdHeader()
    ) {
      status shouldBe 400
    }

    // Fail if no agreement is signed
    postJson(
      s"/publication/preview/request",
      write(PreviewAccessRequest(dataset.id, loggedInUser.id, None)),
      headers = serviceHeader ++ traceIdHeader()
    ) {
      status shouldBe 400
    }

    postJson(
      s"/publication/preview/request",
      write(
        PreviewAccessRequest(dataset.id, colleagueUser.id, Some(agreement.id))
      ),
      headers = serviceHeader ++ traceIdHeader()
    ) {
      println(body)
      status shouldBe 200
    }

    get(
      s"/${dataset.nodeId}/publication/preview",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody
        .extract[List[DatasetPreviewerDTO]]
        .map(dto => (dto.user.email, dto.embargoAccess)) shouldBe List(
        (colleagueUser.email, EmbargoAccess.Requested)
      )
    }

    postJson(
      s"/${dataset.nodeId}/publication/preview",
      write(GrantPreviewAccessRequest(colleagueUser.id)),
      headers = serviceHeader ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    get(
      s"/${dataset.nodeId}/publication/preview",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody
        .extract[List[DatasetPreviewerDTO]]
        .map(dto => (dto.user.email, dto.embargoAccess)) shouldBe List(
        (colleagueUser.email, EmbargoAccess.Granted)
      )
    }

    insecureContainer.emailer
      .asInstanceOf[LoggingEmailer]
      .sentEmails
      .map(e => (e.subject, e.to.address))
      .toList shouldBe alreadySent ++ List(
      ("Request to Access Data", loggedInUser.email),
      ("Request Accepted", colleagueUser.email)
    )
  }
  test(
    "request and accept preview access to embargoed dataset without a data user agreement"
  ) {

    implicit val dataset: Dataset =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)

    get(
      s"/${dataset.nodeId}/publication/preview",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody
        .extract[List[DatasetPreviewerDTO]]
        .map(dto => (dto.user.email, dto.embargoAccess)) shouldBe empty
    }

    val serviceHeader =
      jwtServiceAuthorizationHeader(loggedInOrganization, Some(dataset))

    // If the dataset isn't embargoed, the preview request will fail:
    postJson(
      s"/publication/preview/request",
      write(PreviewAccessRequest(dataset.id, colleagueUser.id)),
      headers = serviceHeader ++ traceIdHeader()
    ) {
      status shouldBe 400
      body should include("must be under embargo")
    }

    // Set status as embargoed:
    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Completed, PublicationType.Embargo)
      .await
      .right
      .value

    postJson(
      s"/publication/preview/request",
      write(PreviewAccessRequest(dataset.id, colleagueUser.id)),
      headers = serviceHeader ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    get(
      s"/${dataset.nodeId}/publication/preview",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody
        .extract[List[DatasetPreviewerDTO]]
        .map(dto => (dto.user.email, dto.embargoAccess)) shouldBe List(
        (colleagueUser.email, EmbargoAccess.Requested)
      )
    }

    postJson(
      s"/${dataset.nodeId}/publication/preview",
      write(GrantPreviewAccessRequest(colleagueUser.id)),
      headers = serviceHeader ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    get(
      s"/${dataset.nodeId}/publication/preview",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody
        .extract[List[DatasetPreviewerDTO]]
        .map(dto => (dto.user.email, dto.embargoAccess)) shouldBe List(
        (colleagueUser.email, EmbargoAccess.Granted)
      )
    }
  }

  test(
    "cannot request access to embargoed dataset that user can already preview"
  ) {
    implicit val dataset: Dataset =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)

    // Set status as embargoed:
    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Completed, PublicationType.Embargo)
      .await
      .right
      .value

    val agreement: DataUseAgreement =
      createDataUseAgreement("AGREEMENT-1", "some text")

    val serviceHeader =
      jwtServiceAuthorizationHeader(loggedInOrganization, Some(dataset))

    postJson(
      s"/${dataset.nodeId}/publication/preview",
      write(GrantPreviewAccessRequest(colleagueUser.id)),
      headers = serviceHeader ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    postJson(
      s"/publication/preview/request",
      write(
        PreviewAccessRequest(dataset.id, colleagueUser.id, Some(agreement.id))
      ),
      headers = serviceHeader ++ traceIdHeader()
    ) {
      status shouldBe 400
      (parsedBody \ "message")
        .extract[String] should equal("Access has already been granted")
    }
  }

  test("cannot grant preview access to users in different organization") {
    implicit val dataset: Dataset =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)

    postJson(
      s"/${dataset.nodeId}/publication/preview",
      write(GrantPreviewAccessRequest(externalUser.id)),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 404
    }
  }

  test("return data use agreement for embargoed dataset") {

    val agreement: DataUseAgreement =
      createDataUseAgreement("AGREEMENT-1", "some text")

    val dataset: Dataset =
      createDataSet("Embargoed dataset", dataUseAgreement = Some(agreement))

    get(
      s"/${dataset.nodeId}/publication/data-use-agreement",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody
        .extract[DataUseAgreementDTO] shouldBe DataUseAgreementDTO(agreement)
    }
  }

  test("return data use agreement for embargoed dataset integer ID") {

    val agreement: DataUseAgreement =
      createDataUseAgreement("AGREEMENT-1", "some text")

    val dataset: Dataset =
      createDataSet("Embargoed dataset", dataUseAgreement = Some(agreement))

    get(
      s"/${dataset.id}/publication/data-use-agreement",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody
        .extract[DataUseAgreementDTO] shouldBe DataUseAgreementDTO(agreement)
    }
  }

  test("return 204 when embargoed dataset does not have data use agreement") {
    val dataset: Dataset =
      createDataSet("Embargoed dataset", dataUseAgreement = None)

    get(
      s"/${dataset.nodeId}/publication/data-use-agreement",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 204
    }
  }

  test("2 step publishing - get datasets with a requested publication") {

    val dataset =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)

    postJson(
      s"/${dataset.nodeId}/publication/request?publicationType=publication&comments=hello%20world",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    val dataset2 = createDataSet("My second Dataset")
    addBannerAndReadme(dataset2)

    val pkg2 =
      createPackage(dataset2, "some-package", `type` = CSV)

    createFile(pkg2, FileObjectType.Source, FileProcessingState.Processed)

    postJson(
      s"/${dataset2.nodeId}/publication/request?publicationType=publication",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    val dataset3 = createDataSet("My third Dataset")

    get(
      s"/paginated?publicationStatus=requested&publicationType=publication",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      val response = parsedBody.extract[PaginatedDatasets]
      response.totalCount shouldBe 2
      response.datasets
        .map(_.content.name)
        .to[Set] shouldBe Set("My Dataset", "My second Dataset")
    }
  }

  test(
    "2 step publishing - get datasets with a requested or rejected publication"
  ) {

    val dataset =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)

    postJson(
      s"/${dataset.nodeId}/publication/request?publicationType=publication&comments=hello%20world",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    postJson(
      s"/${dataset.nodeId}/publication/reject?publicationType=publication&comments=hello%20world",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    val dataset2 = createDataSet("My second Dataset")
    addBannerAndReadme(dataset2)

    val pkg2 =
      createPackage(dataset2, "some-package", `type` = CSV)

    createFile(pkg2, FileObjectType.Source, FileProcessingState.Processed)

    postJson(
      s"/${dataset2.nodeId}/publication/request?publicationType=publication",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    val dataset3 = createDataSet("My third Dataset")

    get(
      s"/paginated?publicationStatus=requested&publicationStatus=rejected&publicationType=publication",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      val response = parsedBody.extract[PaginatedDatasets]
      response.totalCount shouldBe 2
      response.datasets
        .map(_.content.name)
        .to[Set] shouldBe Set("My Dataset", "My second Dataset")
      response.datasets
        .map(_.publication.status)
        .to[Set] shouldBe Set(
        PublicationStatus.Rejected,
        PublicationStatus.Requested
      )
    }
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
      .right
      .value

    val embargoDataset = createDataSet("Embargo dataset")
    secureContainer.datasetPublicationStatusManager
      .create(
        embargoDataset,
        PublicationStatus.Requested,
        PublicationType.Embargo
      )
      .await
      .right
      .value

    val revisionDataset = createDataSet("Revision dataset")
    secureContainer.datasetPublicationStatusManager
      .create(
        revisionDataset,
        PublicationStatus.Requested,
        PublicationType.Revision
      )
      .await
      .right
      .value

    get(
      s"/paginated?publicationStatus=requested&publicationType=publication&publicationType=embargo",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      val response = parsedBody.extract[PaginatedDatasets]
      response.totalCount shouldBe 2
      response.datasets
        .map(dataset => dataset.content.id)
        .to[Set] shouldBe Set(publicationDataset.nodeId, embargoDataset.nodeId)
    }
  }

  test("2 step publishing - get datasets with no publication status") {

    val dataset =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)

    postJson(
      s"/${dataset.nodeId}/publication/request?publicationType=publication&comments=hello%20world",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    val dataset2 = createDataSet("My second Dataset")

    get(
      s"/paginated?publicationStatus=draft&publicationStatus=requested",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      val response = parsedBody.extract[PaginatedDatasets]
      response.totalCount shouldBe 3
      response.datasets
        .map(_.content.name)
        .to[Set] shouldBe Set("Home", "My Dataset", "My second Dataset")

      response.datasets
        .map(_.publication) shouldBe
        List(
          DatasetPublicationDTO(None, PublicationStatus.Draft, None),
          DatasetPublicationDTO(
            None,
            PublicationStatus.Requested,
            Some(PublicationType.Publication)
          ),
          DatasetPublicationDTO(None, PublicationStatus.Draft, None)
        )

    }

  }

  test(
    "2 step publishing - publishers cannot request or cancel publication, owners cannot or reject/accept/retract, only system admins can complete/fail publication"
  ) {

    implicit val dataset: Dataset =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication,
      authorizationHeader(colleagueJwt)
    ) shouldBe (403, Some(PublicationStatus.Draft), None, true)

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication,
      authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Publication
    ), true)

    publicationRequestResult(
      PublicationStatus.Cancelled,
      PublicationType.Publication,
      authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) shouldBe (403, Some(PublicationStatus.Requested), Some(
      PublicationType.Publication
    ), true)

    publicationRequestResult(
      PublicationStatus.Rejected,
      PublicationType.Publication,
      authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) shouldBe (403, Some(PublicationStatus.Requested), Some(
      PublicationType.Publication
    ), true)

    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Publication,
      authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) shouldBe (403, Some(PublicationStatus.Requested), Some(
      PublicationType.Publication
    ), true)

    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Publication,
      authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) shouldBe (201, Some(PublicationStatus.Accepted), Some(
      PublicationType.Publication
    ), true)

    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Publication,
      authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) shouldBe (403, Some(PublicationStatus.Accepted), Some(
      PublicationType.Publication
    ), true)

    publicationRequestResult(
      PublicationStatus.Completed,
      PublicationType.Publication,
      authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) shouldBe (403, Some(PublicationStatus.Accepted), Some(
      PublicationType.Publication
    ), true)

    publicationRequestResult(
      PublicationStatus.Completed,
      PublicationType.Publication,
      authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) shouldBe (403, Some(PublicationStatus.Accepted), Some(
      PublicationType.Publication
    ), true)

  }

  test("2 step publishing - state machine - current status None") {

    implicit val dataset: Dataset = initializePublicationTest()

    // request revision
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Revision,
      authorizationHeader(loggedInJwt)
    ) shouldBe (400, Some(PublicationStatus.Draft), None, true)

    // request withdrawal
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Removal,
      authorizationHeader(loggedInJwt)
    ) shouldBe (400, Some(PublicationStatus.Draft), None, true)

    Seq(
      PublicationStatus.Cancelled,
      PublicationStatus.Rejected,
      PublicationStatus.Accepted
    ).foreach(s => {

      publicationRequestResult(s, PublicationType.Publication) shouldBe (400, Some(
        PublicationStatus.Draft
      ), None, true)

      publicationRequestResult(s, PublicationType.Revision) shouldBe (400, Some(
        PublicationStatus.Draft
      ), None, true)

    })
  }

  test(
    "2 step publishing - state machine - current status Publication Requested"
  ) {

    implicit val dataset: Dataset = initializePublicationTest()

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Publication
    ), true)

    // revision
    Seq(
      PublicationStatus.Requested,
      PublicationStatus.Cancelled,
      PublicationStatus.Rejected,
      PublicationStatus.Accepted
    ).foreach(
      s =>
        publicationRequestResult(s, PublicationType.Revision) shouldBe (400, Some(
          PublicationStatus.Requested
        ), Some(PublicationType.Publication), true)
    )

    // re-request
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication
    ) shouldBe (400, Some(PublicationStatus.Requested), Some(
      PublicationType.Publication
    ), true)

    // cancel and re-request
    publicationRequestResult(
      PublicationStatus.Cancelled,
      PublicationType.Publication
    ) shouldBe (201, Some(PublicationStatus.Cancelled), Some(
      PublicationType.Publication
    ), true)
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Publication
    ), true)

    // reject and re-request
    publicationRequestResult(
      PublicationStatus.Rejected,
      PublicationType.Publication
    ) shouldBe (201, Some(PublicationStatus.Rejected), Some(
      PublicationType.Publication
    ), true)
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Publication
    ), true)

    // accept and complete with failure
    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Publication
    ) shouldBe (201, Some(PublicationStatus.Accepted), Some(
      PublicationType.Publication
    ), true)
    publicationRequestResult(
      PublicationStatus.Failed,
      PublicationType.Publication
    ) shouldBe (200, Some(PublicationStatus.Failed), Some(
      PublicationType.Publication
    ), true)

  }

  test(
    "2 step publishing - state machine - current status Publication Cancelled"
  ) {

    implicit val dataset: Dataset = initializePublicationTest()

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Publication
    ), true)
    publicationRequestResult(
      PublicationStatus.Cancelled,
      PublicationType.Publication
    ) shouldBe (201, Some(PublicationStatus.Cancelled), Some(
      PublicationType.Publication
    ), true)

    // revision
    Seq(
      PublicationStatus.Requested,
      PublicationStatus.Cancelled,
      PublicationStatus.Rejected,
      PublicationStatus.Accepted
    ).foreach(
      s =>
        publicationRequestResult(s, PublicationType.Revision) shouldBe (400, Some(
          PublicationStatus.Cancelled
        ), Some(PublicationType.Publication), true)
    )

    // re-cancel
    publicationRequestResult(
      PublicationStatus.Cancelled,
      PublicationType.Publication
    ) shouldBe (400, Some(PublicationStatus.Cancelled), Some(
      PublicationType.Publication
    ), true)

    // reject, accept
    Seq(PublicationStatus.Rejected, PublicationStatus.Accepted).foreach(
      s =>
        publicationRequestResult(s, PublicationType.Publication) shouldBe (400, Some(
          PublicationStatus.Cancelled
        ), Some(PublicationType.Publication), true)
    )

  }

  test(
    "2 step publishing - state machine - current status Publication Rejected"
  ) {

    implicit val dataset: Dataset = initializePublicationTest()

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Publication
    ), true)
    publicationRequestResult(
      PublicationStatus.Rejected,
      PublicationType.Publication
    ) shouldBe (201, Some(PublicationStatus.Rejected), Some(
      PublicationType.Publication
    ), true)

    // revision
    Seq(
      PublicationStatus.Requested,
      PublicationStatus.Cancelled,
      PublicationStatus.Rejected,
      PublicationStatus.Accepted
    ).foreach(
      s =>
        publicationRequestResult(s, PublicationType.Revision) shouldBe (400, Some(
          PublicationStatus.Rejected
        ), Some(PublicationType.Publication), true)
    )

    // reject, accept
    Seq(PublicationStatus.Rejected, PublicationStatus.Accepted).foreach(
      s =>
        publicationRequestResult(s, PublicationType.Publication) shouldBe (400, Some(
          PublicationStatus.Rejected
        ), Some(PublicationType.Publication), true)
    )

    // cancel
    publicationRequestResult(
      PublicationStatus.Cancelled,
      PublicationType.Publication
    ) shouldBe (201, Some(PublicationStatus.Cancelled), Some(
      PublicationType.Publication
    ), true)
  }

  test(
    "2 step publishing - state machine - current status Publication Accepted"
  ) {

    implicit val dataset: Dataset = initializePublicationTest()

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Publication
    ), true)
    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Publication
    ) shouldBe (201, Some(PublicationStatus.Accepted), Some(
      PublicationType.Publication
    ), true)

    // revision
    Seq(
      PublicationStatus.Requested,
      PublicationStatus.Cancelled,
      PublicationStatus.Rejected,
      PublicationStatus.Accepted
    ).foreach(
      s =>
        publicationRequestResult(s, PublicationType.Revision) shouldBe (400, Some(
          PublicationStatus.Accepted
        ), Some(PublicationType.Publication), true)
    )

    // request, cancel, reject, accept
    Seq(
      PublicationStatus.Requested,
      PublicationStatus.Cancelled,
      PublicationStatus.Rejected,
      PublicationStatus.Accepted
    ).foreach(
      s =>
        publicationRequestResult(s, PublicationType.Publication) shouldBe (400, Some(
          PublicationStatus.Accepted
        ), Some(PublicationType.Publication), true)
    )

  }

  test("2 step publishing - state machine - current status Publication Failed") {

    implicit val dataset: Dataset = initializePublicationTest()

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Publication
    ), true)
    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Publication
    ) shouldBe (201, Some(PublicationStatus.Accepted), Some(
      PublicationType.Publication
    ), true)
    publicationRequestResult(
      PublicationStatus.Failed,
      PublicationType.Publication
    ) shouldBe (200, Some(PublicationStatus.Failed), Some(
      PublicationType.Publication
    ), true)

    // revision
    Seq(
      PublicationStatus.Requested,
      PublicationStatus.Cancelled,
      PublicationStatus.Rejected,
      PublicationStatus.Accepted
    ).foreach(
      s =>
        publicationRequestResult(s, PublicationType.Revision) shouldBe (400, Some(
          PublicationStatus.Failed
        ), Some(PublicationType.Publication), true)
    )

    // request, cancel
    Seq(PublicationStatus.Requested, PublicationStatus.Cancelled).foreach(
      s =>
        publicationRequestResult(s, PublicationType.Publication) shouldBe (400, Some(
          PublicationStatus.Failed
        ), Some(PublicationType.Publication), true)
    )

    // accept and fail again
    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Publication
    ) shouldBe (201, Some(PublicationStatus.Accepted), Some(
      PublicationType.Publication
    ), true)
    publicationRequestResult(
      PublicationStatus.Failed,
      PublicationType.Publication
    ) shouldBe (200, Some(PublicationStatus.Failed), Some(
      PublicationType.Publication
    ), true)

    // reject
    publicationRequestResult(
      PublicationStatus.Rejected,
      PublicationType.Publication
    ) shouldBe (201, Some(PublicationStatus.Rejected), Some(
      PublicationType.Publication
    ), true)
  }

  test(
    "2 step publishing - state machine - able to revise or withdraw a published dataset even if a subsequent version failed to publish"
  ) {

    implicit val dataset: Dataset = initializePublicationTest()

    // original publication
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Publication
    ), true)
    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Publication
    ) shouldBe (201, Some(PublicationStatus.Accepted), Some(
      PublicationType.Publication
    ), true)
    publicationRequestResult(
      PublicationStatus.Completed,
      PublicationType.Publication
    ) shouldBe (200, Some(PublicationStatus.Completed), Some(
      PublicationType.Publication
    ), true)

    // failed subsequent version
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Publication
    ), true)
    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Publication
    ) shouldBe (201, Some(PublicationStatus.Accepted), Some(
      PublicationType.Publication
    ), true)
    publicationRequestResult(
      PublicationStatus.Failed,
      PublicationType.Publication
    ) shouldBe (200, Some(PublicationStatus.Failed), Some(
      PublicationType.Publication
    ), true)
    publicationRequestResult(
      PublicationStatus.Rejected,
      PublicationType.Publication
    ) shouldBe (201, Some(PublicationStatus.Rejected), Some(
      PublicationType.Publication
    ), true)

    // revision
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Revision
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Revision
    ), true)
    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Revision
    ) shouldBe (201, Some(PublicationStatus.Completed), Some(
      PublicationType.Revision
    ), true)

    // another failed version
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Publication
    ), true)
    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Publication
    ) shouldBe (201, Some(PublicationStatus.Accepted), Some(
      PublicationType.Publication
    ), true)
    publicationRequestResult(
      PublicationStatus.Failed,
      PublicationType.Publication
    ) shouldBe (200, Some(PublicationStatus.Failed), Some(
      PublicationType.Publication
    ), true)
    publicationRequestResult(
      PublicationStatus.Rejected,
      PublicationType.Publication
    ) shouldBe (201, Some(PublicationStatus.Rejected), Some(
      PublicationType.Publication
    ), true)

    // removal
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Removal
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Removal
    ), true)
    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Removal
    ) shouldBe (201, Some(PublicationStatus.Completed), Some(
      PublicationType.Removal
    ), true)
  }

  test(
    "2 step publishing - state machine - current status Publication Completed"
  ) {

    implicit val dataset = initializePublicationTest()

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Publication
    ), true)
    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Publication
    ) shouldBe (201, Some(PublicationStatus.Accepted), Some(
      PublicationType.Publication
    ), true)
    publicationRequestResult(
      PublicationStatus.Completed,
      PublicationType.Publication
    ) shouldBe (200, Some(PublicationStatus.Completed), Some(
      PublicationType.Publication
    ), true)

    // cancel, reject, accept
    Seq(
      PublicationStatus.Cancelled,
      PublicationStatus.Rejected,
      PublicationStatus.Accepted
    ).foreach(
      s =>
        publicationRequestResult(s, PublicationType.Publication) shouldBe (400, Some(
          PublicationStatus.Completed
        ), Some(PublicationType.Publication), true)
    )

    // put the database back in Complete so we can test requesting again
    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .right
      .value

    // request a new version
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Publication
    ), true)

    // put the database back in Complete so we can test requesting revision
    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .right
      .value

    // revision
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Revision
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Revision
    ), true)

    // put the database back in Complete so we can test requesting withdrawal
    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .right
      .value

    // withdrawal
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Removal
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Removal
    ), true)

  }

  test("2 step publishing - state machine - current status Revision Requested") {

    implicit val dataset: Dataset = initializePublicationTest()
    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .right
      .value

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Revision
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Revision
    ), true)

    // publication
    Seq(
      PublicationStatus.Requested,
      PublicationStatus.Cancelled,
      PublicationStatus.Rejected,
      PublicationStatus.Accepted
    ).foreach(
      s =>
        publicationRequestResult(s, PublicationType.Publication) shouldBe (400, Some(
          PublicationStatus.Requested
        ), Some(PublicationType.Revision), true)
    )

    // re-request
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Revision
    ) shouldBe (400, Some(PublicationStatus.Requested), Some(
      PublicationType.Revision
    ), true)

    // cancel and re-request
    publicationRequestResult(
      PublicationStatus.Cancelled,
      PublicationType.Revision
    ) shouldBe (201, Some(PublicationStatus.Cancelled), Some(
      PublicationType.Revision
    ), true)

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Revision
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Revision
    ), true)

    // reject and re-request
    publicationRequestResult(
      PublicationStatus.Rejected,
      PublicationType.Revision
    ) shouldBe (201, Some(PublicationStatus.Rejected), Some(
      PublicationType.Revision
    ), true)

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Revision
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Revision
    ), true)

    // accept and complete
    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Revision
    ) shouldBe (201, Some(PublicationStatus.Completed), Some(
      PublicationType.Revision
    ), true)

  }

  test("2 step publishing - state machine - current status Revision Cancelled") {

    implicit val dataset: Dataset = initializePublicationTest()

    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .right
      .value

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Revision
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Revision
    ), true)
    publicationRequestResult(
      PublicationStatus.Cancelled,
      PublicationType.Revision
    ) shouldBe (201, Some(PublicationStatus.Cancelled), Some(
      PublicationType.Revision
    ), true)

    // publication
    Seq(
      PublicationStatus.Cancelled,
      PublicationStatus.Rejected,
      PublicationStatus.Accepted
    ).foreach(
      s =>
        publicationRequestResult(s, PublicationType.Publication) shouldBe (400, Some(
          PublicationStatus.Cancelled
        ), Some(PublicationType.Revision), true)
    )

    // re-cancel
    publicationRequestResult(
      PublicationStatus.Cancelled,
      PublicationType.Revision
    ) shouldBe (400, Some(PublicationStatus.Cancelled), Some(
      PublicationType.Revision
    ), true)

    // reject, accept
    Seq(PublicationStatus.Rejected, PublicationStatus.Accepted).foreach(
      s =>
        publicationRequestResult(s, PublicationType.Revision) shouldBe (400, Some(
          PublicationStatus.Cancelled
        ), Some(PublicationType.Revision), true)
    )

  }

  test("2 step publishing - state machine - current status Revision Rejected") {

    implicit val dataset: Dataset = initializePublicationTest()

    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .right
      .value

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Revision
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Revision
    ), true)
    publicationRequestResult(
      PublicationStatus.Rejected,
      PublicationType.Revision
    ) shouldBe (201, Some(PublicationStatus.Rejected), Some(
      PublicationType.Revision
    ), true)

    // publication
    Seq(PublicationStatus.Rejected, PublicationStatus.Accepted).foreach(
      s =>
        publicationRequestResult(s, PublicationType.Publication) shouldBe (400, Some(
          PublicationStatus.Rejected
        ), Some(PublicationType.Revision), true)
    )

    // reject, accept
    Seq(PublicationStatus.Rejected, PublicationStatus.Accepted).foreach(
      s =>
        publicationRequestResult(s, PublicationType.Revision) shouldBe (400, Some(
          PublicationStatus.Rejected
        ), Some(PublicationType.Revision), true)
    )

    // cancel
    publicationRequestResult(
      PublicationStatus.Cancelled,
      PublicationType.Revision
    ) shouldBe (201, Some(PublicationStatus.Cancelled), Some(
      PublicationType.Revision
    ), true)
  }

  test("2 step publishing - state machine - current status Revision Completed") {
    // note that we should never end up in a state of Revision Accepted -
    // we created both Accepted and Completed records on revision acceptance

    implicit val dataset: Dataset = initializePublicationTest()

    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .right
      .value

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Revision
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Revision
    ), true)
    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Revision
    ) shouldBe (201, Some(PublicationStatus.Completed), Some(
      PublicationType.Revision
    ), true)

    // publication
    Seq(
      PublicationStatus.Cancelled,
      PublicationStatus.Rejected,
      PublicationStatus.Accepted
    ).foreach(
      s =>
        publicationRequestResult(s, PublicationType.Publication) shouldBe (400, Some(
          PublicationStatus.Completed
        ), Some(PublicationType.Revision), true)
    )

    // cancel
    publicationRequestResult(
      PublicationStatus.Cancelled,
      PublicationType.Revision
    ) shouldBe (400, Some(PublicationStatus.Completed), Some(
      PublicationType.Revision
    ), true)

    // reject, accept
    Seq(PublicationStatus.Rejected, PublicationStatus.Accepted).foreach(
      s =>
        publicationRequestResult(s, PublicationType.Revision) shouldBe (400, Some(
          PublicationStatus.Completed
        ), Some(PublicationType.Revision), true)
    )

    // publish a new version
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Publication
    ), true)

    // set back to Revision Complete so we can re-revise
    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Completed, PublicationType.Revision)
      .await
      .right
      .value

    // re-revise
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Revision
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Revision
    ), true)

  }

  test(
    "2 step publishing - state machine - current status Withdrawal Requested"
  ) {

    implicit val dataset: Dataset = initializePublicationTest()
    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .right
      .value

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Removal
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Removal
    ), true)

    // publication
    Seq(
      PublicationStatus.Requested,
      PublicationStatus.Cancelled,
      PublicationStatus.Rejected,
      PublicationStatus.Accepted
    ).foreach(
      s =>
        publicationRequestResult(s, PublicationType.Publication) shouldBe (400, Some(
          PublicationStatus.Requested
        ), Some(PublicationType.Removal), true)
    )

    // re-request
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Removal
    ) shouldBe (400, Some(PublicationStatus.Requested), Some(
      PublicationType.Removal
    ), true)

    // cancel and re-request
    publicationRequestResult(
      PublicationStatus.Cancelled,
      PublicationType.Removal
    ) shouldBe (201, Some(PublicationStatus.Cancelled), Some(
      PublicationType.Removal
    ), true)

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Removal
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Removal
    ), true)

    // reject and re-request
    publicationRequestResult(
      PublicationStatus.Rejected,
      PublicationType.Removal
    ) shouldBe (201, Some(PublicationStatus.Rejected), Some(
      PublicationType.Removal
    ), true)

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Removal
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Removal
    ), true)

    // accept and complete
    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Removal
    ) shouldBe (201, Some(PublicationStatus.Completed), Some(
      PublicationType.Removal
    ), true)

  }

  test(
    "2 step publishing - state machine - current status Withdrawal Cancelled"
  ) {

    implicit val dataset: Dataset = initializePublicationTest()

    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .right
      .value

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Removal
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Removal
    ), true)
    publicationRequestResult(
      PublicationStatus.Cancelled,
      PublicationType.Removal
    ) shouldBe (201, Some(PublicationStatus.Cancelled), Some(
      PublicationType.Removal
    ), true)

    // publication
    Seq(
      PublicationStatus.Cancelled,
      PublicationStatus.Rejected,
      PublicationStatus.Accepted
    ).foreach(
      s =>
        publicationRequestResult(s, PublicationType.Publication) shouldBe (400, Some(
          PublicationStatus.Cancelled
        ), Some(PublicationType.Removal), true)
    )

    // re-cancel
    publicationRequestResult(
      PublicationStatus.Cancelled,
      PublicationType.Removal
    ) shouldBe (400, Some(PublicationStatus.Cancelled), Some(
      PublicationType.Removal
    ), true)

    // reject, accept
    Seq(PublicationStatus.Rejected, PublicationStatus.Accepted).foreach(
      s =>
        publicationRequestResult(s, PublicationType.Removal) shouldBe (400, Some(
          PublicationStatus.Cancelled
        ), Some(PublicationType.Removal), true)
    )
  }

  test("2 step publishing - state machine - current status Withdrawal Rejected") {

    implicit val dataset: Dataset = initializePublicationTest()

    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .right
      .value

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Removal
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Removal
    ), true)
    publicationRequestResult(
      PublicationStatus.Rejected,
      PublicationType.Removal
    ) shouldBe (201, Some(PublicationStatus.Rejected), Some(
      PublicationType.Removal
    ), true)

    // publication
    Seq(PublicationStatus.Rejected, PublicationStatus.Accepted).foreach(
      s =>
        publicationRequestResult(s, PublicationType.Publication) shouldBe (400, Some(
          PublicationStatus.Rejected
        ), Some(PublicationType.Removal), true)
    )

    // reject, accept
    Seq(PublicationStatus.Rejected, PublicationStatus.Accepted).foreach(
      s =>
        publicationRequestResult(s, PublicationType.Removal) shouldBe (400, Some(
          PublicationStatus.Rejected
        ), Some(PublicationType.Removal), true)
    )

    // cancel
    publicationRequestResult(
      PublicationStatus.Cancelled,
      PublicationType.Removal
    ) shouldBe (201, Some(PublicationStatus.Cancelled), Some(
      PublicationType.Removal
    ), true)
  }

  test(
    "2 step publishing - state machine - current status Withdrawal Completed"
  ) {
    // note that we should never end up in a state of Withdrawal Accepted -
    // we created both Accepted and Completed records on withdrawal acceptance

    implicit val dataset: Dataset = initializePublicationTest()

    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .right
      .value

    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Removal
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Removal
    ), true)
    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Removal
    ) shouldBe (201, Some(PublicationStatus.Completed), Some(
      PublicationType.Removal
    ), true)

    // publication
    Seq(
      PublicationStatus.Cancelled,
      PublicationStatus.Rejected,
      PublicationStatus.Accepted
    ).foreach(
      s =>
        publicationRequestResult(s, PublicationType.Removal) shouldBe (400, Some(
          PublicationStatus.Completed
        ), Some(PublicationType.Removal), true)
    )

    // cancel
    publicationRequestResult(
      PublicationStatus.Cancelled,
      PublicationType.Removal
    ) shouldBe (400, Some(PublicationStatus.Completed), Some(
      PublicationType.Removal
    ), true)

    // reject, accept
    Seq(PublicationStatus.Rejected, PublicationStatus.Accepted).foreach(
      s =>
        publicationRequestResult(s, PublicationType.Removal) shouldBe (400, Some(
          PublicationStatus.Completed
        ), Some(PublicationType.Removal), true)
    )

    // publish a new version
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Publication
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Publication
    ), true)
    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Publication
    ) shouldBe (201, Some(PublicationStatus.Accepted), Some(
      PublicationType.Publication
    ), true)
    publicationRequestResult(
      PublicationStatus.Completed,
      PublicationType.Publication
    ) shouldBe (200, Some(PublicationStatus.Completed), Some(
      PublicationType.Publication
    ), true)

    // withdraw again
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Removal
    ) shouldBe (201, Some(PublicationStatus.Requested), Some(
      PublicationType.Removal
    ), true)
    publicationRequestResult(
      PublicationStatus.Accepted,
      PublicationType.Removal
    ) shouldBe (201, Some(PublicationStatus.Completed), Some(
      PublicationType.Removal
    ), true)

    // re-withdraw
    publicationRequestResult(
      PublicationStatus.Requested,
      PublicationType.Removal
    ) shouldBe (400, Some(PublicationStatus.Completed), Some(
      PublicationType.Removal
    ), true)

  }

  test("notify the Discover service to publish a dataset") {

    val dataset = initializePublicationTest()

    // initialize a publish request
    secureContainer.datasetPublicationStatusManager
      .create(
        dataset,
        PublicationStatus.Requested,
        PublicationType.Publication,
        None
      )
      .await
      .right
      .value

    postJson(
      s"/${dataset.nodeId}/publication/accept?publicationType=publication",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
      val result = parsedBody.extract[DatasetPublicationStatus]
      result.datasetId shouldBe dataset.id
      result.publicationStatus shouldBe PublicationStatus.Accepted
      result.publicationType shouldBe PublicationType.Publication
    }

    // Should send embargo=false query param
    mockPublishClient.publishRequests
      .get((loggedInOrganization.id, dataset.id))
      .get
      ._1 shouldBe Some(false)

    decode[NotificationMessage](
      mockSqsClient.sentMessages("http://localhost/queue/notifications").head
    ).right.get shouldBe an[DiscoverPublishNotification]
  }

  test("notify the Discover service to embargo a dataset") {

    val dataset = initializePublicationTest()

    val embargoReleaseDate = LocalDate.now.plusWeeks(1)

    secureContainer.datasetPublicationStatusManager
      .create(
        dataset,
        PublicationStatus.Requested,
        PublicationType.Embargo,
        None,
        Some(embargoReleaseDate)
      )
      .await
      .right
      .value

    postJson(
      s"/${dataset.nodeId}/publication/accept?publicationType=embargo",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
      val result = parsedBody.extract[DatasetPublicationStatus]
      result.datasetId shouldBe dataset.id
      result.publicationStatus shouldBe PublicationStatus.Accepted
      result.publicationType shouldBe PublicationType.Embargo
    }

    // Should send embargo=true query param
    val (embargo, sentEmbargoReleaseDate, _) = mockPublishClient.publishRequests
      .get((loggedInOrganization.id, dataset.id))
      .get

    embargo shouldBe Some(true)
    sentEmbargoReleaseDate shouldBe Some(embargoReleaseDate)

    decode[NotificationMessage](
      mockSqsClient.sentMessages("http://localhost/queue/notifications").head
    ).right.get shouldBe an[DiscoverPublishNotification]
  }

  test("notify the Discover service to release an embargoed dataset") {

    val dataset = initializePublicationTest()

    secureContainer.datasetPublicationStatusManager
      .create(
        dataset,
        PublicationStatus.Requested,
        PublicationType.Release,
        None,
        Some(LocalDate.now)
      )
      .await
      .right
      .value

    post(
      s"/${dataset.nodeId}/publication/accept?publicationType=release",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    mockPublishClient.releaseRequests should contain(
      loggedInOrganization.id,
      dataset.id
    )

    decode[NotificationMessage](
      mockSqsClient.sentMessages("http://localhost/queue/notifications").head
    ).right.get shouldBe an[DiscoverPublishNotification]
  }

  test(
    "publishers - fail to publish a dataset if the requesting user is not the owner and not a publisher"
  ) {

    val dataset = createDataSet("My Dataset")
    addBannerAndReadme(dataset)

    val pkg =
      createPackage(dataset, "some-package", `type` = CSV)

    createFile(pkg, FileObjectType.Source, FileProcessingState.Processed)

    val orcidAuth = OrcidAuthorization(
      name = "John Doe",
      accessToken = "64918a80-dd0c-dd0c-dd0c-dd0c64918a80",
      expiresIn = 631138518,
      tokenType = "bearer",
      orcid = "0000-0012-3456-7890",
      scope = "/autheticate",
      refreshToken = "64918a80-dd0c-dd0c-dd0c-dd0c64918a80"
    )

    val updatedUser = loggedInUser.copy(orcidAuthorization = Some(orcidAuth))
    secureContainer.userManager.update(updatedUser).await

    val publisherTeam = secureContainer.organizationManager
      .getPublisherTeam(secureContainer.organization)
      .await
      .right
      .value
    secureContainer.teamManager
      .addUser(publisherTeam._1, colleagueUser, DBPermission.Administer)
      .await
      .right
      .value

    // initialize a publish request
    secureContainer.datasetPublicationStatusManager
      .create(
        dataset,
        PublicationStatus.Requested,
        PublicationType.Publication,
        None
      )
      .await
      .right
      .value

    postJson(
      s"/${dataset.nodeId}/publication/accept?publicationType=publication",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 403
    }
  }

  test("publishers - publish a dataset if the requesting user is a publisher") {

    val dataset = createDataSet("My Dataset")
    addBannerAndReadme(dataset)

    val pkg =
      createPackage(dataset, "some-package", `type` = CSV)

    createFile(pkg, FileObjectType.Source, FileProcessingState.Processed)

    val orcidAuth = OrcidAuthorization(
      name = "John Doe",
      accessToken = "64918a80-dd0c-dd0c-dd0c-dd0c64918a80",
      expiresIn = 631138518,
      tokenType = "bearer",
      orcid = "0000-0012-3456-7890",
      scope = "/autheticate",
      refreshToken = "64918a80-dd0c-dd0c-dd0c-dd0c64918a80"
    )

    val updatedUser = loggedInUser.copy(orcidAuthorization = Some(orcidAuth))
    secureContainer.userManager.update(updatedUser).await

    val publisherTeam = secureContainer.organizationManager
      .getPublisherTeam(secureContainer.organization)
      .await
      .right
      .value
    secureContainer.teamManager
      .addUser(publisherTeam._1, colleagueUser, DBPermission.Administer)
      .await
      .right
      .value

    // initialize a publish request
    postJson(
      s"/${dataset.nodeId}/publication/request?publicationType=publication",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    postJson(
      s"/${dataset.nodeId}/publication/accept?publicationType=publication",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }
  }

  test(
    "publishers - fail to unpublish a dataset if the user is not a publisher"
  ) {

    val dataset =
      createDataSet("My Dataset")
    addBannerAndReadme(dataset)

    val pkg =
      createPackage(dataset, "some-package", `type` = CSV)

    createFile(pkg, FileObjectType.Source, FileProcessingState.Processed)

    val orcidAuth = OrcidAuthorization(
      name = "John Doe",
      accessToken = "64918a80-dd0c-dd0c-dd0c-dd0c64918a80",
      expiresIn = 631138518,
      tokenType = "bearer",
      orcid = "0000-0012-3456-7890",
      scope = "/autheticate",
      refreshToken = "64918a80-dd0c-dd0c-dd0c-dd0c64918a80"
    )

    val updatedUser = loggedInUser.copy(orcidAuthorization = Some(orcidAuth))
    secureContainer.userManager.update(updatedUser).await

    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .right
      .value

    postJson(
      s"/${dataset.nodeId}/publication/request?publicationType=removal",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    postJson(
      s"/${dataset.nodeId}/publication/accept?publicationType=removal",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 403
    }
  }

  test("publishers - unpublish a dataset if the user is a publisher") {

    val dataset =
      createDataSet("My Dataset")
    addBannerAndReadme(dataset)

    val pkg =
      createPackage(dataset, "some-package", `type` = CSV)

    createFile(pkg, FileObjectType.Source, FileProcessingState.Processed)

    val orcidAuth = OrcidAuthorization(
      name = "John Doe",
      accessToken = "64918a80-dd0c-dd0c-dd0c-dd0c64918a80",
      expiresIn = 631138518,
      tokenType = "bearer",
      orcid = "0000-0012-3456-7890",
      scope = "/autheticate",
      refreshToken = "64918a80-dd0c-dd0c-dd0c-dd0c64918a80"
    )

    val updatedUser = loggedInUser.copy(orcidAuthorization = Some(orcidAuth))
    secureContainer.userManager.update(updatedUser).await

    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .right
      .value

    val publisherTeam = secureContainer.organizationManager
      .getPublisherTeam(secureContainer.organization)
      .await
      .right
      .value
    secureContainer.teamManager
      .addUser(publisherTeam._1, colleagueUser, DBPermission.Administer)
      .await
      .right
      .value

    postJson(
      s"/${dataset.nodeId}/publication/request?publicationType=removal",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    postJson(
      s"/${dataset.nodeId}/publication/accept?publicationType=removal",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }
  }

  test(
    "publishers - fail to unpublish a dataset if the user is NOT a publisher"
  ) {

    val dataset =
      createDataSet("My Dataset")
    addBannerAndReadme(dataset)

    val pkg =
      createPackage(dataset, "some-package", `type` = CSV)

    createFile(pkg, FileObjectType.Source, FileProcessingState.Processed)

    val orcidAuth = OrcidAuthorization(
      name = "John Doe",
      accessToken = "64918a80-dd0c-dd0c-dd0c-dd0c64918a80",
      expiresIn = 631138518,
      tokenType = "bearer",
      orcid = "0000-0012-3456-7890",
      scope = "/autheticate",
      refreshToken = "64918a80-dd0c-dd0c-dd0c-dd0c64918a80"
    )

    val updatedUser = loggedInUser.copy(orcidAuthorization = Some(orcidAuth))
    secureContainer.userManager.update(updatedUser).await

    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .right
      .value

    postJson(
      s"/${dataset.nodeId}/publication/request?publicationType=removal",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    postJson(
      s"/${dataset.nodeId}/publication/accept?publicationType=removal",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 403
    }
  }

  test("publishers - dataset owners cannot revise a dataset, publishers can") {

    val dataset = initializePublicationTest()

    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .right
      .value

    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Requested, PublicationType.Revision)
      .await
      .right
      .value

    post(
      s"/${dataset.nodeId}/publication/accept?publicationType=revision",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 403
    }

    post(
      s"/${dataset.nodeId}/publication/accept?publicationType=revision",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
      val response = parsedBody.extract[DatasetPublicationStatus]
      response.datasetId shouldBe dataset.id
      response.publicationStatus shouldBe PublicationStatus.Completed
      response.publicationType shouldBe PublicationType.Revision
    }

  }

  test("fail to publish a dataset without a license") {

    val dataset = initializePublicationTest()

    postJson(
      s"/${dataset.nodeId}/publication/accept?publicationType=publication",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 400
    }
  }

  test("fail to publish a dataset without tags") {

    val dataset = initializePublicationTest()

    postJson(
      s"/${dataset.nodeId}/publication/accept?publicationType=publication",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 400
    }
  }

  test("fail to publish a dataset without contributors") {

    val dataset = initializePublicationTest()

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
      s"/${dataset.nodeId}",
      updateReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    //creating a dataset adds the owner as contributor
    //it's contributor #1 in this case
    secureContainer.datasetManager
      .removeContributor(dataset, 1)
      .await

    postJson(
      s"/${dataset.nodeId}/publication/accept?publicationType=publication",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 400
    }
  }

  test("cannot start multiple concurrent publish jobs for the same dataset") {

    val dataset = initializePublicationTest()

    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Requested, PublicationType.Publication)
      .await
      .right
      .value

    postJson(
      s"/${dataset.nodeId}/publication/accept?publicationType=publication",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    postJson(
      s"/${dataset.nodeId}/publication/accept?publicationType=publication",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      // Dataset locked
      status shouldBe 403
    }
  }

  test("unlock the dataset if publish request fails") {

    val dataset = createDataSet("MOCK ERROR")
    addBannerAndReadme(dataset)

    val pkg =
      createPackage(dataset, "some-package", `type` = CSV)

    createFile(pkg, FileObjectType.Source, FileProcessingState.Processed)

    val orcidAuth = OrcidAuthorization(
      name = "John Doe",
      accessToken = "64918a80-dd0c-dd0c-dd0c-dd0c64918a80",
      expiresIn = 631138518,
      tokenType = "bearer",
      orcid = "0000-0012-3456-7890",
      scope = "/autheticate",
      refreshToken = "64918a80-dd0c-dd0c-dd0c-dd0c64918a80"
    )

    val updatedUser = loggedInUser.copy(orcidAuthorization = Some(orcidAuth))
    secureContainer.userManager.update(updatedUser).await

    val publisherTeam = secureContainer.organizationManager
      .getPublisherTeam(secureContainer.organization)
      .await
      .right
      .value
    secureContainer.teamManager
      .addUser(publisherTeam._1, colleagueUser, DBPermission.Administer)
      .await
      .right
      .value

    var publicationStatusId: Option[Int] = None
    postJson(
      s"/${dataset.nodeId}/publication/request?publicationType=publication",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
      publicationStatusId = secureDataSetManager
        .get(dataset.id)
        .await
        .right
        .value
        .publicationStatusId
    }

    postJson(
      s"/${dataset.nodeId}/publication/accept?publicationType=publication",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 500
      // publicationStatusId should not change in case of failure
      secureDataSetManager
        .get(dataset.id)
        .await
        .right
        .value
        .publicationStatusId shouldBe publicationStatusId
    }
  }

  test("state of pending files is changed to uploaded when publish is complete") {

    val dataset = createDataSet("My Dataset")
    addBannerAndReadme(dataset)

    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Accepted, PublicationType.Publication)
      .await
      .right
      .value

    val pkg =
      createPackage(dataset, "some-package", `type` = CSV)
    val pendingFile = createFile(pkg, uploadedState = Some(FileState.PENDING))

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
      s"/${dataset.id}/publication/complete",
      request,
      headers = jwtServiceAuthorizationHeader(loggedInOrganization) ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    val updatedFile = fileManager.get(pendingFile.id, pkg).await.right.value

    updatedFile.uploadedState shouldBe Some(FileState.UPLOADED)
  }

  test(
    "state of pending files is changed to uploaded when publish is cancelled"
  ) {

    val dataset = createDataSet("My Dataset")
    addBannerAndReadme(dataset)

    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Requested, PublicationType.Publication)
      .await
      .right
      .value

    val pkg =
      createPackage(dataset, "some-package", `type` = CSV)
    val pendingFile = createFile(pkg, uploadedState = Some(FileState.PENDING))

    postJson(
      s"/${dataset.nodeId}/publication/cancel?publicationType=publication",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    val updatedFile = fileManager.get(pendingFile.id, pkg).await.right.value

    updatedFile.uploadedState shouldBe Some(FileState.UPLOADED)
  }

  test("state of pending files is changed to uploaded when publish is rejected") {
    val dataset =
      initializePublicationTest(assignPublisherUserDirectlyToDataset = false)

    postJson(
      s"/${dataset.nodeId}/publication/request?publicationType=publication",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    val pkg =
      createPackage(dataset, "some-new-package", `type` = CSV)
    val pendingFile = createFile(pkg, uploadedState = Some(FileState.PENDING))

    postJson(
      s"/${dataset.nodeId}/publication/reject?publicationType=publication",
      "",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    val updatedFile = fileManager.get(pendingFile.id, pkg).await.right.value

    updatedFile.uploadedState shouldBe Some(FileState.UPLOADED)
  }

  test("unlock dataset when publish is complete") {

    val dataset = createDataSet("My Dataset")
    addBannerAndReadme(dataset)

    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Accepted, PublicationType.Publication)
      .await
      .right
      .value

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
      s"/${dataset.id}/publication/complete",
      request,
      headers = jwtServiceAuthorizationHeader(loggedInOrganization) ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    val publicationStatusId = secureDataSetManager
      .get(dataset.id)
      .await
      .right
      .value
      .publicationStatusId
      .get

    PublicationStatus.lockedStatuses contains secureContainer.datasetPublicationStatusManager
      .get(publicationStatusId)
      .await
      .right
      .value
      .publicationStatus shouldBe false

    decode[NotificationMessage](
      mockSqsClient.sentMessages("http://localhost/queue/notifications").head
    ).right.get shouldBe an[DiscoverPublishNotification]
  }

  test("set publication status when publish fails") {
    val dataset = createDataSet("My Dataset")

    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Accepted, PublicationType.Publication)
      .await
      .right
      .value

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
      s"/${dataset.id}/publication/complete",
      request,
      headers = jwtServiceAuthorizationHeader(loggedInOrganization) ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    val publicationStatusId = secureDataSetManager
      .get(dataset.id)
      .await
      .right
      .value
      .publicationStatusId
      .get

    secureContainer.datasetPublicationStatusManager
      .get(publicationStatusId)
      .await
      .right
      .value
      .publicationStatus shouldBe PublicationStatus.Failed

    decode[NotificationMessage](
      mockSqsClient.sentMessages("http://localhost/queue/notifications").head
    ).right.get shouldBe an[DiscoverPublishNotification]
  }

  test("deserialize publish-complete message correctly") {
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

  test("notify the Discover service to revise a dataset") {

    val dataset = initializePublicationTest()

    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .right
      .value

    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Requested, PublicationType.Revision)
      .await
      .right
      .value

    post(
      s"/${dataset.nodeId}/publication/accept?publicationType=revision",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
      val response = parsedBody.extract[DatasetPublicationStatus]
      response.datasetId shouldBe dataset.id
      response.publicationStatus shouldBe PublicationStatus.Completed
      response.publicationType shouldBe PublicationType.Revision
    }

    decode[NotificationMessage](
      mockSqsClient.sentMessages("http://localhost/queue/notifications").head
    ).right.get shouldBe an[DiscoverPublishNotification]
  }

  test("notify the Discover service to unpublish a dataset") {

    val dataset = initializePublicationTest()

    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .right
      .value

    secureContainer.datasetPublicationStatusManager
      .create(dataset, PublicationStatus.Requested, PublicationType.Removal)
      .await
      .right
      .value

    post(
      s"/${dataset.nodeId}/publication/accept?publicationType=removal",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status shouldBe 201
    }

    decode[NotificationMessage](
      mockSqsClient.sentMessages("http://localhost/queue/notifications").head
    ).right.get shouldBe an[DiscoverPublishNotification]
  }

  test("get the publishing status of a dataset") {
    val dataset =
      createDataSet("My Dataset")

    val expectedResponse = DatasetPublishStatus(
      "PPMI",
      loggedInOrganization.id,
      dataset.id,
      None,
      0,
      PublishStatus.PublishInProgress,
      None,
      None
    )

    get(
      s"/${dataset.nodeId}/published",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody.extract[DatasetPublishStatus] shouldBe expectedResponse
    }
  }

  test("get the publishing status of all of the organization's datasets") {
    val expectedResponse = List(
      DatasetPublishStatus(
        "PPMI",
        loggedInOrganization.id,
        1,
        Some(10),
        2,
        PublishStatus.PublishInProgress,
        Some(OffsetDateTime.of(2019, 2, 1, 10, 11, 12, 13, ZoneOffset.UTC)),
        None
      ),
      DatasetPublishStatus(
        "TUSZ",
        loggedInOrganization.id,
        2,
        Some(12),
        3,
        PublishStatus.PublishInProgress,
        Some(OffsetDateTime.of(2019, 4, 1, 10, 11, 12, 13, ZoneOffset.UTC)),
        None
      )
    )

    get(
      s"/published",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      parsedBody
        .extract[List[DatasetPublishStatus]] shouldBe expectedResponse
    }
  }

  test("upload dataset banner") {
    val dataset = createDataSet("My Dataset")

    val bannerFile = new File("src/test/resources/test-assets/banner.jpg")
    val fileUploads =
      Map("banner" -> FilePart(bannerFile, contentType = "image/jpeg"))

    put(
      s"/${dataset.nodeId}/banner",
      Map(),
      fileUploads,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200

      val dto = parsedBody.extract[DatasetBannerDTO]
      dto.banner.get.toString should include("?presigned=true")

      val bannerAsset = secureContainer.datasetAssetsManager
        .getBanner(dataset)
        .value
        .await
        .right
        .get
        .get

      val expectedKey =
        s"${loggedInOrganization.id}/${dataset.id}/${bannerAsset.id}/banner.jpg"

      bannerAsset.name shouldBe "banner.jpg"
      bannerAsset.s3Bucket shouldBe mockDatasetAssetClient.bucket
      bannerAsset.s3Key shouldBe expectedKey
      bannerAsset.datasetId shouldBe dataset.id

      val (content, metadata) = mockDatasetAssetClient
        .assets(bannerAsset.id)

      content.stripLineEnd shouldBe FileUtils.readFileToString(
        bannerFile,
        "utf-8"
      )
      metadata.getContentType() shouldBe "image/jpeg"
    }
  }

  test("replace a dataset banner") {
    val dataset = createDataSet("My Dataset")

    val originalBannerFile =
      new File("src/test/resources/test-assets/banner.jpg")
    val fileUploads =
      Map("banner" -> FilePart(originalBannerFile, contentType = "image/jpeg"))

    put(
      s"/${dataset.nodeId}/banner",
      Map(),
      fileUploads,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {

      val originalBannerAsset = secureContainer.datasetAssetsManager
        .getBanner(dataset)
        .value
        .await
        .right
        .get
        .get

      // replace the previous banner
      val updatedBannerFile =
        new File("src/test/resources/test-assets/newBanner.jpg")
      val updatedFileUploads =
        Map("banner" -> FilePart(updatedBannerFile, contentType = "image/jpeg"))

      put(
        s"/${dataset.nodeId}/banner",
        Map(),
        updatedFileUploads,
        headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
      ) {
        status shouldBe 200

        val updatedBannerAsset = secureContainer.datasetAssetsManager
          .getBanner(dataset)
          .value
          .await
          .right
          .get
          .get

        val updatedExpectedKey =
          s"${loggedInOrganization.id}/${dataset.id}/${updatedBannerAsset.id}/newBanner.jpg"

        updatedBannerAsset.id shouldNot be(originalBannerAsset.id)
        updatedBannerAsset.name shouldBe "newBanner.jpg"
        updatedBannerAsset.s3Key shouldBe updatedExpectedKey

        val (content, _) = mockDatasetAssetClient
          .assets(updatedBannerAsset.id)

        content.stripLineEnd shouldBe FileUtils.readFileToString(
          updatedBannerFile,
          "utf-8"
        )

        // validate that the previous banner has been deleted
        assert(
          mockDatasetAssetClient.assets.get(originalBannerAsset.id).isEmpty
        )
        assert(
          secureContainer.db
            .run(
              secureContainer.datasetAssetsManager.datasetAssetsMapper
                .filter(_.id === originalBannerAsset.id)
                .result
                .headOption
            )
            .await
            .isEmpty
        )
      }
    }
  }

  test("cannot upload too-large banner") {
    val dataset = createDataSet("My Dataset")

    val fileUploads =
      Map(
        "banner" -> BytesPart(
          "big.jpg",
          Array.fill(maxFileUploadSize + 10)(1.toByte),
          "image/jpeg"
        )
      )

    put(
      s"/${dataset.nodeId}/banner",
      Map(),
      fileUploads,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 413
    }
  }

  test("get a presigned banner url") {
    val dataset = createDataSet("My Dataset")

    get(
      s"/${dataset.nodeId}/banner",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      val dto = parsedBody.extract[DatasetBannerDTO]
      dto.banner shouldBe None
    }

    addBannerAndReadme(dataset)

    get(
      s"/${dataset.nodeId}/banner",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      val dto = parsedBody.extract[DatasetBannerDTO]
      dto.banner.get.toString should include("?presigned=true")
    }
  }

  test("upload dataset readme") {
    val dataset = createDataSet("My Dataset")

    val readme = "#Markdown content\nA paragraph!"
    val request = write(DatasetReadmeDTO(readme = readme))

    putJson(
      s"/${dataset.nodeId}/readme",
      request,
      authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200

      val readmeAsset = secureContainer.datasetAssetsManager
        .getReadme(dataset)
        .value
        .await
        .right
        .get
        .get

      response.getHeader(HttpHeaders.ETAG) shouldBe readmeAsset.etag.asHeader

      val expectedKey =
        s"${loggedInOrganization.id}/${dataset.id}/${readmeAsset.id}/readme.md"

      readmeAsset.name shouldBe "readme.md"
      readmeAsset.s3Bucket shouldBe mockDatasetAssetClient.bucket
      readmeAsset.s3Key shouldBe expectedKey
      readmeAsset.datasetId shouldBe dataset.id

      val (content, metadata) = mockDatasetAssetClient
        .assets(readmeAsset.id)

      content.stripLineEnd shouldBe readme
      metadata.getContentType() shouldBe "text/plain"
      metadata.getContentLength() shouldBe 30
    }
  }

  /**
    * The content-length of unicode data must be computed using byte-length, not
    * string-length (code points).
    */
  test("upload dataset readme with unicode") {
    val dataset = createDataSet("My Dataset")

    val readme = "#Markdown content\nÚПicুdЄ too!"
    val request = write(DatasetReadmeDTO(readme = readme))

    putJson(
      s"/${dataset.nodeId}/readme",
      request,
      authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200

      val readmeAsset = secureContainer.datasetAssetsManager
        .getReadme(dataset)
        .value
        .await
        .right
        .get
        .get

      val (content, metadata) = mockDatasetAssetClient
        .assets(readmeAsset.id)

      content.stripLineEnd shouldBe readme
      // Byte length, not Scala string length:
      metadata.getContentLength() shouldBe 35
    }
  }

  test("update existing dataset readme") {

    // Start with an existing README
    val dataset = addBannerAndReadme(createDataSet("My Dataset"))

    val readme = "#Markdown content\nA paragraph!"
    val request = write(DatasetReadmeDTO(readme = readme))

    putJson(
      s"/${dataset.nodeId}/readme",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200

      val readmeAsset = secureContainer.datasetAssetsManager
        .getReadme(dataset)
        .value
        .await
        .right
        .get
        .get

      val expectedKey =
        s"${loggedInOrganization.id}/${dataset.id}/${readmeAsset.id}/readme.md"

      readmeAsset.name shouldBe "readme.md"
      readmeAsset.s3Bucket shouldBe mockDatasetAssetClient.bucket
      readmeAsset.s3Key shouldBe expectedKey
      readmeAsset.datasetId shouldBe dataset.id

      val (content, metadata) = mockDatasetAssetClient
        .assets(readmeAsset.id)

      content.stripLineEnd shouldBe readme
      metadata.getContentType() shouldBe "text/plain"
    }
  }

  test("create and modify dataset readme with If-Match header") {
    val dataset = createDataSet("My Dataset")

    val readme = "#Markdown content\nA paragraph!"
    val request = write(DatasetReadmeDTO(readme = readme))

    putJson(
      s"/${dataset.nodeId}/readme",
      request,
      authorizationHeader(loggedInJwt) ++ Map(HttpHeaders.IF_MATCH -> "0")
    ) {
      status shouldBe 200

      val newReadmeAsset = secureContainer.datasetAssetsManager
        .getReadme(dataset)
        .value
        .await
        .right
        .get
        .get

      response.getHeader(HttpHeaders.ETAG) shouldBe newReadmeAsset.etag.asHeader
      response.getHeader(HttpHeaders.ETAG) should not be "0"
    }

    val updatedReadme = "#Markdown content\nA paragraph!\nSome more!"
    val updateRequest = write(DatasetReadmeDTO(readme = updatedReadme))

    val existingReadmeAsset = (for {
      updatedDataset <- secureContainer.datasetManager.get(dataset.id)
      readmeAsset <- secureContainer.datasetAssetsManager
        .getReadme(dataset)
    } yield readmeAsset).value.await.right.get.get

    putJson(
      s"/${dataset.nodeId}/readme",
      updateRequest,
      authorizationHeader(loggedInJwt) ++ traceIdHeader() ++ Map(
        HttpHeaders.IF_MATCH -> existingReadmeAsset.etag.asHeader
      )
    ) {
      status shouldBe 200

      val updatedReadmeAsset = secureContainer.datasetAssetsManager
        .getReadme(dataset)
        .value
        .await
        .right
        .get
        .get

      response.getHeader(HttpHeaders.ETAG) shouldBe updatedReadmeAsset.etag.asHeader
      response.getHeader(HttpHeaders.ETAG) should not be existingReadmeAsset.etag.asHeader
    }
  }

  test(
    "fail to update an existing dataset readme if the If-Match header indicates a stale version"
  ) {

    // Start with an existing README
    val dataset = addBannerAndReadme(createDataSet("My Dataset"))

    val readme = "#Markdown content\nA paragraph!"
    val request = write(DatasetReadmeDTO(readme = readme))

    putJson(
      s"/${dataset.nodeId}/readme",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader() ++ Map(
        HttpHeaders.IF_MATCH ->
          "12345"
      )
    ) {
      status shouldBe 412
    }
  }

  test("fail to update readme if the If-Match header indicates new readme") {

    // Start with an existing README
    val dataset = addBannerAndReadme(createDataSet("My Dataset"))

    val readme = "#Markdown content\nA paragraph!"
    val request = write(DatasetReadmeDTO(readme = readme))

    putJson(
      s"/${dataset.nodeId}/readme",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader() ++ Map(
        HttpHeaders.IF_MATCH ->
          "0"
      )
    ) {
      status shouldBe 412
    }
  }

  test(
    "fail to create readme if the If-Match header indicates one already exists"
  ) {

    // Start with an existing README
    val dataset = createDataSet("My Dataset")

    val readme = "#Markdown content\nA paragraph!"
    val request = write(DatasetReadmeDTO(readme = readme))

    putJson(
      s"/${dataset.nodeId}/readme",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader() ++ Map(
        HttpHeaders.IF_MATCH ->
          "12345"
      )
    ) {
      status shouldBe 412
    }
  }

  test("get a dataset readme") {
    val dataset = createDataSet("My Dataset")

    val content = "#Markdown content\nA paragraph!"
    val request = write(DatasetReadmeDTO(readme = content))

    putJson(
      s"/${dataset.nodeId}/readme",
      request,
      authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
    }

    get(
      s"/${dataset.nodeId}/readme",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      val readme = parsedBody.extract[DatasetReadmeDTO]
      readme.readme shouldBe content

      val readmeAsset = secureContainer.datasetAssetsManager
        .getReadme(dataset)
        .value
        .await
        .right
        .get
        .get

      response.getHeader(HttpHeaders.ETAG) shouldBe readmeAsset.etag.asHeader
    }
  }

  test("get a dataset readme that does not exist") {
    val dataset = createDataSet("My Dataset")

    get(
      s"/${dataset.nodeId}/readme",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200
      val readme = parsedBody.extract[DatasetReadmeDTO]
      readme.readme shouldBe ""

      response.getHeader(HttpHeaders.ETAG) shouldBe "0"
    }
  }

  private def createObjects(csvPackage: Package) =
    Some(
      Map(
        FileObjectType.Source.entryName -> List(
          SimpleFileDTO(createFile(csvPackage), csvPackage)
        ),
        FileObjectType.View.entryName -> List.empty[SimpleFileDTO],
        FileObjectType.File.entryName -> List.empty[SimpleFileDTO]
      )
    )

  private def createFile(
    packageInPage: Package,
    objectType: FileObjectType = Source,
    processingState: FileProcessingState = FileProcessingState.Unprocessed,
    name: String = "i'm a file",
    uploadedState: Option[FileState] = None
  ) = {
    fileManager
      .create(
        name = name,
        `type` = FileType.CSV,
        `package` = packageInPage,
        s3Bucket = "anything",
        s3Key = "anything",
        objectType = objectType,
        processingState = processingState,
        uploadedState = uploadedState
      )
      .await
      .right
      .get
  }

  test("get dataset ignore files that do not exist") {
    val dataset = createDataSet("dataset-ignore-files")

    get(
      s"/${dataset.nodeId}/ignore-files",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200

      val dto = parsedBody.extract[DatasetIgnoreFilesDTO]
      dto.ignoreFiles.length shouldBe 0
      dto.ignoreFiles shouldBe Seq()
      dto.datasetId shouldBe dataset.id
    }
  }

  test("set ignore files for a dataset") {
    val dataset = createDataSet("dataset-ignore-files")

    val ignoreFiles = Seq(
      DatasetIgnoreFileDTO("file1.py"),
      DatasetIgnoreFileDTO("file2.png"),
      DatasetIgnoreFileDTO("file3.txt")
    )
    val expectedIgnoreFiles = Seq(
      DatasetIgnoreFile(dataset.id, "file1.py", 1),
      DatasetIgnoreFile(dataset.id, "file2.png", 2),
      DatasetIgnoreFile(dataset.id, "file3.txt", 3)
    )
    val request = write(ignoreFiles)
    putJson(
      s"/${dataset.nodeId}/ignore-files",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200

      val dto = parsedBody.extract[DatasetIgnoreFilesDTO]
      dto.ignoreFiles.length shouldBe 3
      dto.ignoreFiles shouldBe expectedIgnoreFiles
      dto.datasetId shouldBe dataset.id
    }

    get(
      s"/${dataset.nodeId}/ignore-files",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200

      val dto = parsedBody.extract[DatasetIgnoreFilesDTO]
      dto.ignoreFiles.length shouldBe 3
      dto.ignoreFiles shouldBe expectedIgnoreFiles
      dto.datasetId shouldBe dataset.id
    }
  }

  test("update ignore files for a dataset") {
    val dataset = createDataSet("dataset-ignore-files")

    val ignoreFiles = Seq(
      DatasetIgnoreFileDTO("file1.py"),
      DatasetIgnoreFileDTO("file2.png"),
      DatasetIgnoreFileDTO("file3.txt")
    )
    val request = write(ignoreFiles)
    putJson(
      s"/${dataset.nodeId}/ignore-files",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) { status shouldBe 200 }

    val updatedIgnoreFiles = Seq(
      DatasetIgnoreFileDTO("file4.py"),
      DatasetIgnoreFileDTO("file5.png"),
      DatasetIgnoreFileDTO("file3.txt"),
      DatasetIgnoreFileDTO("file6.jpeg")
    )
    val updateRequest = write(updatedIgnoreFiles)
    putJson(
      s"/${dataset.nodeId}/ignore-files",
      updateRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200

      val dto = parsedBody.extract[DatasetIgnoreFilesDTO]
      dto.ignoreFiles.map(f => (f.datasetId, f.fileName)) shouldBe Seq(
        (dataset.id, "file4.py"),
        (dataset.id, "file5.png"),
        (dataset.id, "file3.txt"),
        (dataset.id, "file6.jpeg")
      )
      dto.datasetId shouldBe dataset.id
    }
  }

  test("delete ignore files for a dataset") {
    val dataset = createDataSet("dataset-ignore-files")

    val ignoreFiles = Seq(
      DatasetIgnoreFileDTO("file1.py"),
      DatasetIgnoreFileDTO("file2.png"),
      DatasetIgnoreFileDTO("file3.txt")
    )
    val request = write(ignoreFiles)
    putJson(
      s"/${dataset.nodeId}/ignore-files",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) { status shouldBe 200 }

    val deleteRequest = write(Seq())
    putJson(
      s"/${dataset.nodeId}/ignore-files",
      deleteRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status shouldBe 200

      val dto = parsedBody.extract[DatasetIgnoreFilesDTO]
      dto.ignoreFiles.length shouldBe 0
      dto.ignoreFiles shouldBe Seq()
      dto.datasetId shouldBe dataset.id
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

      val publishedDatasets = parsedBody.extract[PaginatedPublishedDatasets]
      publishedDatasets.datasets.length shouldBe 3
      publishedDatasets.datasets.map(_.dataset.get.content.id) shouldBe List(
        dataset5.nodeId,
        dataset4.nodeId,
        dataset1.nodeId
      )
      publishedDatasets.datasets.map(_.publishedDataset.sourceDatasetId.get) shouldBe List(
        dataset5.id,
        dataset4.id,
        dataset1.id
      )
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

      val publishedDatasets = parsedBody.extract[PaginatedPublishedDatasets]
      publishedDatasets.datasets.length shouldBe 3
      publishedDatasets.datasets.map(_.dataset) shouldBe List(None, None, None)
      publishedDatasets.datasets.map(_.publishedDataset.sourceDatasetId.get) shouldBe List(
        dataset1.id,
        dataset4.id,
        dataset5.id
      )
    }
  }

  test("include embargo access status for published datasets") {
    val dataset1 = createDataSet("test-dataset1")
    val dataset2 = createDataSet("test-dataset2")
    val dataset3 = createDataSet("test-dataset3")

    mockSearchClient.publishedDatasets ++= List(dataset1, dataset2, dataset3)
      .map(
        mockSearchClient
          .toMockPublicDatasetDTO(_, loggedInOrganization, loggedInUser)
      )

    secureContainer.datasetPreviewManager
      .requestAccess(dataset1, colleagueUser, None)
      .await
      .right
      .get

    secureContainer.datasetPreviewManager
      .grantAccess(dataset2, colleagueUser)
      .await
      .right
      .get

    get(
      s"/published/paginated?orderBy=name&orderDirection=Asc",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(200)

      parsedBody
        .extract[PaginatedPublishedDatasets]
        .datasets
        .map(d => (d.publishedDataset.sourceDatasetId.get, d.embargoAccess)) shouldBe List(
        dataset1.id -> Some(EmbargoAccess.Requested),
        dataset2.id -> Some(EmbargoAccess.Granted),
        dataset3.id -> None
      )
    }
  }

  test("touch updatedAt timestamp with a service claim") {
    val dataset = createDataSet("dataset")

    post(
      s"/internal/${dataset.id}/touch",
      headers = jwtServiceAuthorizationHeader(loggedInOrganization)
    ) {
      status should equal(200)
    }

    secureContainer.datasetManager
      .get(dataset.id)
      .await
      .right
      .get
      .updatedAt should be > dataset.updatedAt
  }

  test("cannot touch updatedAt timestamp with a user claim") {
    val dataset = createDataSet("dataset")

    post(
      s"/internal/${dataset.id}/touch",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(403)
    }
  }

  test("cannot touch updatedAt timestamp without authorization") {
    val dataset = createDataSet("dataset")

    post(s"/internal/${dataset.id}/touch") {
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

  test("add a Collection to a dataset") {
    val dataset = createDataSet("DatasetWithCollection")

    val collectionListAtTheBeginning =
      secureContainer.collectionManager
        .getCollections()
        .await
        .right
        .value

    val collection = createCollection("My Own Collection")

    val addCollectionRequest =
      write(AddCollectionRequest(collectionId = collection.id))

    putJson(
      s"/${dataset.nodeId}/collections",
      addCollectionRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    val collectionListatTheEnd =
      secureContainer.collectionManager
        .getCollections()
        .await
        .right
        .value

    collectionListatTheEnd shouldBe collectionListAtTheBeginning :+ collection
  }

  test(
    "deleting a dataset should remove the Collection from the organization if the dataset was the last member of the collection"
  ) {
    val dataset = createDataSet("DatasetWithCollection")
    val dataset2 = createDataSet("AnotherDatasetWithCollection")

    val collectionListAtTheBeginning =
      secureContainer.collectionManager
        .getCollections()
        .await
        .right
        .value

    val collection = createCollection("My Super New Collection")

    val addCollectionRequest =
      write(AddCollectionRequest(collectionId = collection.id))

    putJson(
      s"/${dataset.nodeId}/collections",
      addCollectionRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    val collectionListBeforeDeletion = secureContainer.collectionManager
      .getCollections()
      .await
      .right
      .value

    collectionListBeforeDeletion shouldBe collectionListAtTheBeginning :+ collection

    delete(
      s"/${dataset.nodeId}/collections/${collection.id}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    val collectionListAfterDeletion =
      secureContainer.collectionManager
        .getCollections()
        .await
        .right
        .value

    collectionListAfterDeletion shouldBe collectionListAtTheBeginning
  }

  test("get changelog timeline") {

    val dataset = createDataSet("timeline-dataset")

    secureContainer.changelogManager
      .logEvent(
        dataset,
        ChangelogEventDetail.CreatePackage(234, None, None, None),
        ZonedDateTime.now().minusDays(2)
      )
      .await
      .right
      .get

    secureContainer.changelogManager
      .logEvent(
        dataset,
        ChangelogEventDetail.RenamePackage(234, None, "old", "new", None),
        ZonedDateTime.now().minusDays(1)
      )
      .await
      .right
      .get

    secureContainer.changelogManager
      .logEvent(
        dataset,
        ChangelogEventDetail.UpdateOwner(loggedInUser.id, colleagueUser.id),
        ZonedDateTime.now()
      )
      .await
      .right
      .get

    val nextCursor = get(
      s"/${dataset.nodeId}/changelog/timeline?limit=2",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val dto = parsedBody.extract[ChangelogPage]

      dto.eventGroups.map(eg => eg.eventType) shouldBe List(
        ChangelogEventName.UPDATE_OWNER,
        ChangelogEventName.RENAME_PACKAGE
      )
      dto.cursor shouldBe defined
      dto.cursor.get
    }

    get(
      s"/${dataset.nodeId}/changelog/timeline?limit=2&cursor=$nextCursor",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val dto = parsedBody.extract[ChangelogPage]
      dto.eventGroups.map(eg => eg.eventType) shouldBe List(
        ChangelogEventName.CREATE_PACKAGE
      )
      dto.cursor shouldBe None
    }
  }
  test("load events from event group in changelog timeline") {

    val dataset = createDataSet("timeline-dataset")

    secureContainer.changelogManager
      .logEvent(
        dataset,
        ChangelogEventDetail.CreatePackage(234, None, None, None),
        ZonedDateTime.now().minusDays(2)
      )
      .await
      .right
      .get

    secureContainer.changelogManager
      .logEvent(
        dataset,
        ChangelogEventDetail.CreatePackage(233, None, None, None),
        ZonedDateTime.now().minusDays(2).minusHours(1)
      )
      .await
      .right
      .get

    secureContainer.changelogManager
      .logEvent(
        dataset,
        ChangelogEventDetail.RenamePackage(234, None, "old", "new", None),
        ZonedDateTime.now().minusDays(1)
      )
      .await
      .right
      .get

    secureContainer.changelogManager
      .logEvent(
        dataset,
        ChangelogEventDetail.UpdateOwner(loggedInUser.id, colleagueUser.id),
        ZonedDateTime.now()
      )
      .await
      .right
      .get

    val eventCursor = get(
      s"/${dataset.nodeId}/changelog/timeline",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val dto = parsedBody.extract[ChangelogPage]
      dto.eventGroups.map(eg => eg.eventType) shouldBe List(
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
      s"/${dataset.nodeId}/changelog/events?cursor=$eventCursor",
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
}
