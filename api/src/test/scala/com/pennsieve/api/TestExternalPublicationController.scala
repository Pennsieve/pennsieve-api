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
import com.pennsieve.helpers.MockDoiClient
import com.pennsieve.models.{
  CognitoId,
  DBPermission,
  Dataset,
  Doi,
  NodeCodes,
  Organization,
  RelationshipType,
  User
}
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.scalatest.EitherValues._
import org.scalatest.OptionValues._

import java.net.URLEncoder
import java.util.UUID
import scala.concurrent.Future

class TestExternalPublicationController extends BaseApiUnitTest {

  var loggedInUser: User = _
  var loggedInOrganization: Organization = _
  var loggedInJwt: String = _

  override def beforeAll(): Unit = {
    super.beforeAll()

    implicit val httpClient: HttpRequest => Future[HttpResponse] = { _ =>
      Future.successful(HttpResponse())
    }

    addFilter(
      new ExternalPublicationController(
        insecureContainer,
        secureContainerBuilder,
        new MockDoiClient(),
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

    val userId = state.newId()
    loggedInUser = User(
      nodeId = NodeCodes.generateId(NodeCodes.userCode),
      email = "test@test.com",
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
      id = userId
    )
    state.users.put(userId, loggedInUser)

    state.orgUserPermissions
      .put((loggedInOrganization.id, loggedInUser.id), DBPermission.Administer)
    state.orgUsers.put(
      (loggedInOrganization.id, loggedInUser.id),
      com.pennsieve.models.OrganizationUser(
        loggedInOrganization.id,
        loggedInUser.id,
        DBPermission.Administer
      )
    )

    loggedInJwt = mintUserJwt(loggedInUser, loggedInOrganization)

    val sc = secureContainerBuilder(loggedInUser, loggedInOrganization)
    sc.datasetStatusManager.resetDefaultStatusOptions.await.value
  }

  private def createDataSet(name: String): Dataset = {
    val sc = secureContainerBuilder(loggedInUser, loggedInOrganization)
    sc.datasetManager.create(name, Some("desc")).await.value
  }

  private def encode(q: String): String =
    URLEncoder.encode(q, "utf-8")

  private val ValidDoi = Doi("10.21397/jili-ef5r")
  private val InvalidDoi = Doi("10.21397/adbb-6903")

  private def createExternalPublication(
    dataset: Dataset,
    doi: Doi
  ): com.pennsieve.api.ExternalPublicationDTO =
    put(
      s"/datasets/${dataset.nodeId}/external-publications?doi=${doi.value}&relationshipType=IsReferencedBy",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      parsedBody.extract[ExternalPublicationDTO]
    }

  test("link external publications with valid DOIs to a dataset") {
    val dataset = createDataSet("dataset")

    Seq(
      ValidDoi.value,
      "10.1093/brain/aww045",
      "10.1002/(sici)1099-1409(199908/10)3:6/7<672::aid-jpp192>3.0.co;2-8"
    ).foreach { doi =>
      put(
        s"/datasets/${dataset.nodeId}/external-publications?doi=${encode(doi)}&relationshipType=IsDerivedFrom",
        headers = authorizationHeader(loggedInJwt)
      ) {
        status should equal(200)
        parsedBody.extract[ExternalPublicationDTO].doi.value shouldBe doi
        parsedBody
          .extract[ExternalPublicationDTO]
          .relationshipType shouldBe RelationshipType.IsDerivedFrom
      }
    }
  }

  test("require a DOI query parameter to link external publications") {
    val dataset = createDataSet("dataset")
    put(
      s"/datasets/${dataset.nodeId}/external-publications?relationshipType=IsSourceOf",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(400)
    }
  }

  test("reject malformed DOIs") {
    val dataset = createDataSet("dataset")

    Seq("10.134", "10.134/", "10.abc/1234").foreach(
      doi =>
        put(
          s"/datasets/${dataset.nodeId}/external-publications?doi=${encode(doi)}&relationshipType=IsSourceOf",
          headers = authorizationHeader(loggedInJwt)
        ) {
          status should equal(400)
        }
    )
  }

  test("reject incorrect relationshipType") {
    val dataset = createDataSet("dataset")

    Seq("AnIncorrectRelationshipType", "asdfghjkl;").foreach(
      relationshipType =>
        put(
          s"/datasets/${dataset.nodeId}/external-publications?doi=10.21397/jili-ef5d&relationshipType=$relationshipType",
          headers = authorizationHeader(loggedInJwt)
        ) {
          status should equal(400)
        }
    )
  }

  test("lowercase DOI when linking to dataset") {
    val dataset = createDataSet("dataset")
    val uppercaseDoi = Doi("10.21397/JILI-EF5T")

    put(
      s"/datasets/${dataset.nodeId}/external-publications?doi=${uppercaseDoi.value}&relationshipType=IsSourceOf",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      parsedBody.extract[ExternalPublicationDTO].doi shouldBe Doi(
        "10.21397/jili-ef5t"
      )
    }
  }

  test("upsert duplicate external publications") {
    val dataset = createDataSet("dataset")

    put(
      s"/datasets/${dataset.nodeId}/external-publications?doi=${ValidDoi.value}&relationshipType=IsDescribedBy",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
    }

    put(
      s"/datasets/${dataset.nodeId}/external-publications?doi=${ValidDoi.value}&relationshipType=IsDescribedBy",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
    }

    get(
      s"/datasets/${dataset.nodeId}/external-publications",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      parsedBody.extract[List[ExternalPublicationDTO]].length shouldBe 1
    }
  }

  test("get external publications for a dataset") {
    val dataset = createDataSet("dataset")

    createExternalPublication(dataset, ValidDoi)
    createExternalPublication(dataset, InvalidDoi)

    get(
      s"/datasets/${dataset.nodeId}/external-publications",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      parsedBody
        .extract[List[ExternalPublicationDTO]]
        .map(p => (p.doi, p.notFound, p.citation)) shouldBe List(
        (ValidDoi, false, Some("A citation")),
        (InvalidDoi, true, None)
      )
    }
  }

  test("get a citation for external publications for a dataset") {
    get(
      s"/datasets/external-publications/citation?doi=${ValidDoi.value}",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      parsedBody.extract[CitationResult].citation shouldBe "A citation"
    }

    get(
      s"/datasets/external-publications/citation?doi=${InvalidDoi.value}",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(404)
    }
  }

  test("remove external publication from a dataset") {
    val dataset = createDataSet("dataset")

    createExternalPublication(dataset, ValidDoi)

    delete(
      s"/datasets/${dataset.nodeId}/external-publications?relationshipType=IsReferencedBy&doi=${ValidDoi.value}",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(204)
    }

    get(
      s"/datasets/${dataset.nodeId}/external-publications",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      parsedBody.extract[List[ExternalPublicationDTO]] shouldBe empty
    }
  }

  test("return error when external publication does not exist") {
    val dataset = createDataSet("dataset")

    delete(
      s"/datasets/${dataset.nodeId}/external-publications?relationshipType=IsReferencedBy&doi=${ValidDoi.value}",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(404)
    }
  }
}
