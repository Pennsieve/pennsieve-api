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
import com.pennsieve.aws.cognito.MockCognito
import com.pennsieve.clients.MockCustomTermsOfServiceClient
import com.pennsieve.db.CustomTermsOfService
import com.pennsieve.dtos.{
  CustomTermsOfServiceDTO,
  OrcidDTO,
  PennsieveTermsOfServiceDTO,
  UserDTO
}
import com.pennsieve.helpers.{
  MockAuditLogger,
  OrcidClient,
  OrcidWorkPublishing,
  OrcidWorkUnpublishing
}
import com.pennsieve.models.DBPermission.Delete
import com.pennsieve.models.{ DateVersion, Degree, OrcidAuthorization, User }
import com.typesafe.scalalogging.Logger
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
    scope = "/read-limited /activities/update",
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

    override def publishWork(
      work: OrcidWorkPublishing
    ): Future[Option[String]] = Future.successful(Some("1234567"))

    override def unpublishWork(work: OrcidWorkUnpublishing): Future[Boolean] =
      Future.successful(true)
  }

  val mockCustomToSClient: MockCustomTermsOfServiceClient =
    new MockCustomTermsOfServiceClient()

  val mockCognito: MockCognito =
    new MockCognito()

  def updateRequestFromUser(
    user: User,
    userRequestedChange: Option[Boolean] = None
  ): UpdateUserRequest =
    UpdateUserRequest(
      firstName = Some(user.firstName),
      lastName = Some(user.lastName),
      middleInitial = user.middleInitial,
      degree = Some(user.degree.getOrElse("").toString),
      credential = Some(user.credential),
      organization = None,
      url = Some(user.url),
      email = Some(user.email),
      color = Some(user.color),
      userRequestedChange = userRequestedChange
    )

  override def afterStart(): Unit = {
    super.afterStart()

    addServlet(
      new UserController(
        insecureContainer,
        secureContainerBuilder,
        auditLogger,
        system.dispatcher,
        orcidClient,
        mockCognito
      ),
      "/*"
    )
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    mockCustomToSClient.reset()
  }

  test("swagger") {
    import com.pennsieve.web.ResourcesApp
    addServlet(new ResourcesApp, "/api-docs/*")

    get("/api-docs/swagger.json") {
      status should equal(200)
      println(body)
    }
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

  test("get user info includes ORCID Auth Scope") {
    get(s"", headers = authorizationHeader(loggedInOrcidJwt) ++ traceIdHeader()) {
      status should equal(200)
      val result: UserDTO = parsedBody.extract[UserDTO]
      val orcidDto = result.orcid.get
      orcidDto.scope.length shouldBe (2)
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

  test("get user by my email when authenticated") {
    get(
      s"/email/${me.email}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should include(loggedInUser.firstName)
      body should include(loggedInUser.lastName)
      body should include(loggedInUser.email)
    }
  }

  test("get user by colleague email when authenticated") {
    get(
      s"/email/${colleague.email}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should include(colleague.firstName)
      body should include(colleague.lastName)
      body should include(colleague.email)
    }
  }

  test("get user by email returns 404 when email is not associated with a user") {
    get(
      s"/email/unknown@nowhere.none",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  test("return unauthorized when requesting user info by email with no token") {
    get(s"/email/${me.email}") {
      status should equal(401)
      body should include("Unauthorized.")
    }
  }

  test(
    "return unauthorized when requesting user info by email with a bad token"
  ) {
    get(s"/email/${me.email}", headers = authorizationHeader("badtoken")) {
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
        color = None,
        userRequestedChange = None
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
        insecureContainer.userManager.get(loggedInUser.id).await.value
      assert(updatedUser.firstName == "newfirstname")
      assert(updatedUser.lastName == "newlastname")
      assert(updatedUser.middleInitial == Some("M"))
      assert(updatedUser.degree == Some(Degree.BS))
      assert(updatedUser.preferredOrganizationId.get == loggedInOrganization.id)
    }
  }

  test("update user email") {
    val updatedEmail = "updated@email.com"
    val beforeUser =
      insecureContainer.userManager.get(loggedInUser.id).await.value
    val updateReq = write(
      UpdateUserRequest(
        firstName = Some(beforeUser.firstName),
        middleInitial = beforeUser.middleInitial,
        lastName = Some(beforeUser.lastName),
        degree = Some(beforeUser.degree.get.entryName),
        credential = Some(beforeUser.credential),
        organization = Some(loggedInOrganization.nodeId),
        url = Some(beforeUser.url),
        email = Some(updatedEmail),
        color = Some(beforeUser.color),
        userRequestedChange = None
      )
    )

    putJson(
      "/email",
      updateReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should include(updatedEmail)

      val updatedUser =
        insecureContainer.userManager.get(loggedInUser.id).await.value
      assert(updatedUser.email == updatedEmail)
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
        .value
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

  test("orcid link succeeds with valid body") {
    val validRequest = write(
      ORCIDRequest(
        ORCIDAuthorizationInfo(
          source = "orcid-redirect-response",
          code = testAuthorizationCode
        )
      )
    )

    postJson(
      s"/orcid",
      validRequest,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      val result = parsedBody.extract[OrcidDTO]
      result.name should be(orcidAuthorization.name)
      result.orcid should be(orcidAuthorization.orcid)
    }
  }

  test("orcid link fails with invalid body") {
    val invalidRequest = write(
      ORCIDAuthorizationInfo(
        source = "orcid-redirect-response",
        code = testAuthorizationCode
      )
    )

    postJson(
      s"/orcid",
      invalidRequest,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(400)
    }
  }

  test("orcid unlink succeeds with linked Cognito identity") {
    val orcidRequest = write(
      ORCIDRequest(
        ORCIDAuthorizationInfo(
          source = "orcid-redirect-response",
          code = testAuthorizationCode
        )
      )
    )
    // first we POST to link the ORCID iD, then we DELETE to unlink it
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

  test("orcid unlink succeeds without linked Cognito identity") {
    val orcidRequest = write(
      ORCIDRequest(
        ORCIDAuthorizationInfo(
          source = "orcid-redirect-response",
          code = testAuthorizationCode
        )
      )
    )
    // first we POST to link the ORCID iD, then we DELETE to unlink it
    postJson(
      s"/orcid",
      orcidRequest,
      headers = authorizationHeader(colleagueJwt)
    ) {
      status should equal(200)
      delete(s"/orcid", headers = authorizationHeader(colleagueJwt)) {
        body shouldBe empty
        status should equal(200)
      }
    }
  }

  test("orcid unlink fails when ORCID iD is not linked") {
    delete(s"/orcid", headers = authorizationHeader(externalJwt)) {
      body shouldBe """{"message":"ORCiD ID is not linked."}"""
      status should equal(400)
    }
  }

  test("terms of service date versions should function as expected") {
    // Parsing correctly formatted dates should work:
    val dv1: DateVersion = DateVersion.from("19990909120000").value
    val dv2: DateVersion = DateVersion.from("20080501053000").value
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
        .value
    dv1.toString should equal("19990909120000")
    val (dv2, _, _) = mockCustomToSClient
      .updateTermsOfService("pennsieve", "Something else", v2)
      .value
    dv2.toString should equal("20080501053000")
    mockCustomToSClient.getTermsOfService("pennsieve", v1).value should be(
      "Lorem ipsum"
    )
    mockCustomToSClient.getTermsOfService("pennsieve", v2).value should be(
      "Something else"
    )
    mockCustomToSClient.getTermsOfService("pennsieve", v3).isLeft should be(
      true
    )
    mockCustomToSClient.updateTermsOfService("pennsieve", "Lorem ipsum #2", v2)
    mockCustomToSClient.getTermsOfService("pennsieve", v2).value should be(
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
        .value
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

  test("change email request must provide a different address") {
    val updateUserRequest =
      updateRequestFromUser(loggedInUser).copy(userRequestedChange = Some(true))

    putJson(
      "/email",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader(),
      body = write(updateUserRequest)
    ) {
      status should equal(400)
    }
  }

  test("change email request succeeds when a new address is provided") {
    val updateUserRequest = updateRequestFromUser(loggedInUser).copy(
      email = Some("new-email@somewhere.org"),
      userRequestedChange = Some(true)
    )

    putJson(
      "/email",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader(),
      body = write(updateUserRequest)
    ) {
      status should equal(200)
    }
  }

  val tosTestVersion = "20190131163402"
  val tosVersionJson = s"""{"version": "$tosTestVersion"}"""
}
