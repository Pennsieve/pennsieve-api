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

import java.time.LocalDateTime

import com.pennsieve.auth.middleware.{ Jwt, UserClaim }
import com.pennsieve.clients.MockCustomTermsOfServiceClient

import com.pennsieve.db.CustomTermsOfService
import com.pennsieve.dtos.{
  CustomTermsOfServiceDTO,
  OrcidDTO,
  PennsieveTermsOfServiceDTO
}
import com.pennsieve.helpers.{ MockAuditLogger, OrcidClient }
import com.pennsieve.models.DBPermission.Delete
import com.pennsieve.models.{ DateVersion, Degree, OrcidAuthorization }
import org.json4s.jackson.Serialization.write
import org.scalatest.EitherValues._

import scala.concurrent.Future

class TestUsersController extends BaseApiTest {

  val auditLogger = new MockAuditLogger()

  val orcidAuthorization: OrcidAuthorization = OrcidAuthorization(
    name = "name",
    accessToken = "accessToken",
    expiresIn = 100,
    tokenType = "tokenType",
    orcid = "orcid",
    scope = "scope",
    refreshToken = "refreshToken"
  )

  val testAuthorizationCode = "authCode"

  val orcidClient: OrcidClient = new OrcidClient {
    override def getToken(
      authorizationCode: String
    ): Future[OrcidAuthorization] =
      if (authorizationCode == testAuthorizationCode) {
        Future.successful(orcidAuthorization)
      } else {
        Future.failed(new Throwable("invalid authorization code"))
      }
    override def verifyOrcid(orcid: Option[String]): Future[Boolean] =
      Future.successful(true)
  }

  val mockCustomToSClient: MockCustomTermsOfServiceClient =
    new MockCustomTermsOfServiceClient()

  override def afterStart(): Unit = {
    super.afterStart()

    addServlet(
      new UserController(
        insecureContainer,
        secureContainerBuilder,
        auditLogger,
        system.dispatcher,
        orcidClient
      ),
      "/*"
    )
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    mockCustomToSClient.reset()
  }

  test("get user info") {
    get(s"", headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()) {
      status should equal(200)
      body should include(loggedInUser.firstName)
      body should include(loggedInUser.middleInitial.get)
      body should include(loggedInUser.lastName)
      body should include(loggedInUser.degree.get.entryName)
    }
  }

  test("return unauthorized when requesting user info with no token") {
    get("/") {
      status should equal(401)
      body should include("Unauthorized.")
    }
  }

  test("return unauthorized when requesting user info with a bad token") {
    get("", headers = authorizationHeader("badtoken")) {
      status should equal(401)
    }
  }

  test("put user info") {
    val updateReq = write(
      UpdateUserRequest(
        firstName = Some("newfirstname"),
        middleInitial = Some("M"),
        lastName = Some("newlastname"),
        degree = Some("B.S."),
        credential = Some("newcred"),
        organization = Some(loggedInOrganization.nodeId),
        url = Some("newurl"),
        email = None,
        color = None
      )
    )

    putJson(
      s"",
      updateReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should include("newfirstname")
      body should include("newlastname")
      body should include("M")

      val updatedUser =
        insecureContainer.userManager.get(loggedInUser.id).await.right.value
      assert(updatedUser.firstName == "newfirstname")
      assert(updatedUser.lastName == "newlastname")
      assert(updatedUser.middleInitial == Some("M"))
      assert(updatedUser.degree == Some(Degree.BS))
      assert(updatedUser.preferredOrganizationId.get == loggedInOrganization.id)
    }
  }

  test("put user info: degree null") {
    val updateReq =
      s"""{"firstName":"newfirstname",
         |"lastName":"newlastname",
         |"middleInitial":"M",
         |"degree":null,
         |"credential":"newcred",
         |"organization":"${loggedInOrganization.nodeId}",
         |"url":"newurl"}""".stripMargin

    putJson(
      s"",
      updateReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      assert(
        insecureContainer.userManager
          .get(loggedInUser.id)
          .await
          .right
          .value
          .degree == None
      )
    }
  }

  test("put valid pennsieve terms of service succeeds") {
    putJson(
      s"/pennsieve-terms-of-service",
      tosVersionJson,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)

      // returned userDTO should contain new terms
      val terms = (parsedBody \ "pennsieveTermsOfService")
        .extract[Option[PennsieveTermsOfServiceDTO]]
        .get
      terms.version should equal(tosTestVersion)

      // new terms should be updated in the database
      val dbTerms = insecureContainer.pennsieveTermsOfServiceManager
        .get(loggedInUser.id)
        .await
        .right
        .get
        .get
        .toDTO
      dbTerms.version should equal(tosTestVersion)

      // terms stored in the database should be equal to the terms
      // returned to the client
      dbTerms should equal(terms)
    }
  }

  test("put invalid pennsieve terms of service should return 400") {
    val testVersion = "invalid version format"
    val updateReq = s"""{"version": "$testVersion"}"""

    putJson(
      s"/pennsieve-terms-of-service",
      updateReq,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(400)
    }
  }

  test("orcid creation") {
    val orcidRequest = write(ORCIDRequest(testAuthorizationCode))

    postJson(
      s"/orcid",
      orcidRequest,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      val result = parsedBody.extract[OrcidDTO]
      result.name should be(orcidAuthorization.name)
      result.orcid should be(orcidAuthorization.orcid)
    }
  }

  test("orcid deletion") {
    val orcidRequest = write(ORCIDRequest(testAuthorizationCode))
    postJson(
      s"/orcid",
      orcidRequest,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      delete(s"/orcid", headers = authorizationHeader(loggedInJwt)) {
        body shouldBe empty
        status should equal(200)
      }
    }
  }

  test("terms of service date versions should function as expected") {
    // Parsing correctly formatted dates should work:
    val dv1: DateVersion = DateVersion.from("19990909120000").right.get
    val dv2: DateVersion = DateVersion.from("20080501053000").right.get
    dv1 should be < (dv2)
    dv2 should be > (dv1)
    DateVersion.from("1999foo0909120000").isLeft should be(true)
    DateVersion.from("19995009280000").isLeft should be(true)
  }

  test("getting mock terms of service from an empty store should fail") {
    import DateVersion._
    val version = LocalDateTime.of(1999, 9, 9, 12, 0, 0)
    mockCustomToSClient
      .getTermsOfService("pennsieve", version)
      .isLeft should be(true)
  }

  test("getting and setting mock terms of service should work as expected") {
    import DateVersion._

    val v1 = LocalDateTime.of(1999, 9, 9, 12, 0, 0)
    val v2 = LocalDateTime.of(2008, 5, 1, 5, 30, 0)
    val v3 = LocalDateTime.of(2010, 2, 28, 8, 20, 10)
    val (dv1, _, _) =
      mockCustomToSClient
        .updateTermsOfService("pennsieve", "Lorem ipsum", v1)
        .right
        .get
    dv1.toString should equal("19990909120000")
    val (dv2, _, _) = mockCustomToSClient
      .updateTermsOfService("pennsieve", "Something else", v2)
      .right
      .get
    dv2.toString should equal("20080501053000")
    mockCustomToSClient.getTermsOfService("pennsieve", v1).right.get should be(
      "Lorem ipsum"
    )
    mockCustomToSClient.getTermsOfService("pennsieve", v2).right.get should be(
      "Something else"
    )
    mockCustomToSClient.getTermsOfService("pennsieve", v3).isLeft should be(
      true
    )
    mockCustomToSClient.updateTermsOfService("pennsieve", "Lorem ipsum #2", v2)
    mockCustomToSClient.getTermsOfService("pennsieve", v2).right.get should be(
      "Lorem ipsum #2"
    )
  }

  test("reject a bad custom terms of service version") {
    putJson(
      s"/custom-terms-of-service",
      """{ "version": "19995009120000" }""",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(400)
    }
  }

  test("accepting a valid initial custom terms of service") {
    putJson(
      s"/custom-terms-of-service",
      """{ "version": "19990909120000" }""",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      val customToS = (parsedBody \ "customTermsOfService")
        .extract[Seq[CustomTermsOfServiceDTO]]
      customToS.head.version should equal("19990909120000")
    }
  }

  test("updating a valid custom terms of service") {
    val tos1: CustomTermsOfServiceDTO = putJson(
      s"/custom-terms-of-service",
      """{ "version": "19990909120000" }""",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      (parsedBody \ "customTermsOfService")
        .extract[Seq[CustomTermsOfServiceDTO]]
        .head
    }
    val tos2: CustomTermsOfServiceDTO = putJson(
      s"/custom-terms-of-service",
      """{ "version": "20080501053000" }""",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      (parsedBody \ "customTermsOfService")
        .extract[Seq[CustomTermsOfServiceDTO]]
        .head
    }

    val foundToS: CustomTermsOfService =
      insecureContainer.customTermsOfServiceManager
        .get(loggedInUser.id, loggedInOrganization.id)
        .await
        .right
        .get
        .get

    val v = foundToS.acceptedVersion
    v.getYear should ===(2008)
    v.getMonthValue should ===(5)
    v.getDayOfMonth should ===(1)
  }

  test("a service token should be able to get a specified users dto") {
    get(
      s"/${loggedInUser.id}",
      headers = jwtServiceAuthorizationHeader(loggedInOrganization) ++ traceIdHeader()
    ) {
      status shouldEqual 200

      body should include(loggedInUser.firstName)
      body should include(loggedInUser.lastName)
      body should include(loggedInUser.nodeId)
    }
  }

  test("non-service token should not be able to access get user endpoint") {
    get(
      s"/${loggedInUser.id}",
      headers = jwtUserAuthorizationHeader(loggedInOrganization)
    ) {
      status shouldEqual 403
    }
  }

  val tosTestVersion = "20190131163402"
  val tosVersionJson = s"""{"version": "$tosTestVersion"}"""
}
