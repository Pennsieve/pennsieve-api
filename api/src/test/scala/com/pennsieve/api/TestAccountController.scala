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

import com.blackfynn.clients.AntiSpamChallengeClient
import com.pennsieve.aws.cognito.{ CognitoPoolConfig, MockCognito, _ }
import com.pennsieve.models.{ CognitoId, DBPermission, Organization, User }
import org.json4s.jackson.Serialization.write
import org.scalatest.EitherValues._
import pdi.jwt.{ JwtAlgorithm, JwtCirce }
import software.amazon.awssdk.regions.Region

import java.time.{ Duration, Instant }
import java.util.UUID
import scala.concurrent.Future

class MockRecaptchaClient extends AntiSpamChallengeClient {
  override def verifyToken(responseToken: String): Future[Boolean] =
    Future.successful(true)
}

class TestAccountController extends BaseApiTest {

  val pennsieveUserId: String = "0f14d0ab-9605-4a62-a9e4-5ed26688389b"
  val cognitoPoolId: String = "12345"
  val cognitoAppClientId: String = "67890"
  val cognitoPoolId2: String = "abcdef"
  val cognitoAppClientId2: String = "ghijkl"
  val cognitoIdentityPoolId: String = "vbnm"

  val jwkProvider = new MockJwkProvider()

  val cognitoConfig = CognitoConfig(
    Region.US_EAST_1,
    CognitoPoolConfig(Region.US_EAST_1, "user-pool-id", "client-id"),
    CognitoPoolConfig(Region.US_EAST_1, "token-pool-id", "client-id"),
    CognitoPoolConfig(Region.US_EAST_1, "identity-pool-id", "")
  )

  val issuedAtTime: Long = Instant.now().toEpochMilli() / 1000 - 90
  val validTokenTime: Long = Instant.now().toEpochMilli() / 1000 + 9999

  val validToken: String = JwtCirce.encode(
    header = s"""{"kid": "${jwkProvider.jwkKeyId}", "alg": "RS256"}""",
    claim = s"""
      {
        "sub": "$pennsieveUserId",
        "iss": "https://cognito-idp.${Region.US_EAST_1}.amazonaws.com/$cognitoPoolId",
        "iat": $issuedAtTime,
        "exp": $validTokenTime,
        "aud": "$cognitoAppClientId",
        "cognito:username": "$pennsieveUserId"
      }
    """,
    key = jwkProvider.privateKey,
    algorithm = JwtAlgorithm.RS256
  )

  val recaptchaClient: AntiSpamChallengeClient =
    new MockRecaptchaClient()

  val mockCognito = new MockCognito()

  override def afterStart(): Unit = {
    super.afterStart()

    addServlet(
      new AccountController(
        insecureContainer,
        cognitoConfig,
        mockCognito,
        recaptchaClient,
        system.dispatcher
      ),
      "/*"
    )
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    mockCognito.reset()
  }

  test("swagger") {
    import com.pennsieve.web.ResourcesApp
    addServlet(new ResourcesApp, "/api-docs/*")

    get("/api-docs/swagger.json") {
      status should equal(200)
      println(body)
    }
  }

  // TODO update this
  ignore("create new account with Cognito JWT") {
    val mockCognito = new MockCognito()
    val invite = userInviteManager
      .createOrRefreshUserInvite(
        organization = loggedInOrganization,
        email = "testPassword@test.com",
        firstName = "Fynn",
        lastName = "Blackwell",
        permission = DBPermission.Delete,
        Duration.ofSeconds(1000)
      )(userManager, mockCognito, ec)
      .await
      .value

    val newUserRequest = CreateUserRequest(
      firstName = "test",
      middleInitial = None,
      lastName = "tester",
      degree = None,
      title = ""
    )

    postJson("/", write(newUserRequest)) {
      status should be(200)
      userInviteManager.get(invite.id).await should be(Symbol("Left"))
    }
  }

  // TODO: update
  ignore("creating users with invalid Cognito JWT") {
    val mockCognito = new MockCognito()
    val expired = userInviteManager
      .createOrRefreshUserInvite(
        organization = loggedInOrganization,
        email = "expired@test.com",
        firstName = "Fynn",
        lastName = "Blackwell",
        permission = DBPermission.Delete,
        Duration.ofSeconds(0)
      )(userManager, mockCognito, ec)
      .await
      .value

    val badRequest = CreateUserRequest(
      firstName = "test",
      middleInitial = None,
      lastName = "tester",
      degree = None,
      title = ""
    )

    postJson("/", write(badRequest)) {
      status should be(400)
    }
  }

  // TODO update this
  test("create new account without an organization") {

    val newUserRequest = CreateUserWithRecaptchaRequest(
      firstName = "test",
      middleInitial = None,
      lastName = "tester",
      degree = None,
      title = Some(""),
      email = "test@gmail.com",
      recaptchaToken = "foooo"
    )

    postJson("/sign-up", write(newUserRequest)) {
      status should be(200)
      assert(body.contains(welcomeOrganization.nodeId))
    }
  }

  ignore("update an external user invite") {
    // create a User
    val cognitoId = UUID.fromString(pennsieveUserId)
    val user = User(
      nodeId = "N:user:1",
      email = "external@user.com",
      firstName = "???",
      middleInitial = None,
      lastName = "???",
      degree = None,
      credential = "???",
      color = "#342E37",
      url = "???",
      authyId = 0,
      isSuperAdmin = false,
      isIntegrationUser = false,
      preferredOrganizationId = Some(1),
      status = true,
      orcidAuthorization = None,
      cognitoId = Some(CognitoId.UserPoolId(cognitoId))
    )
    val newUser = userManager.create(user).await.value

    // create a CreateUserRequest
    val updateUserRequest = CreateUserRequest(
      firstName = "john",
      middleInitial = Some("andrew"),
      lastName = "smith",
      degree = None,
      title = "chief"
    )

    // PUT request to /account
    putJson(
      uri = "/",
      headers = Map("Authorization" -> s"Bearer ${validToken}"),
      body = write(updateUserRequest)
    ) {
      status should be(200)
    }

  }
}
