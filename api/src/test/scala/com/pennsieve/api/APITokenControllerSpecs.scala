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

import com.pennsieve.auth.middleware.CognitoSession
import com.pennsieve.dtos.{ APITokenDTO, APITokenSecretDTO }
import com.pennsieve.models.{ CognitoId, Organization, Token, User }
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.write
import org.scalatest.OptionValues._

import java.time.Instant
import java.util.UUID

class APITokenControllerSpecs extends BaseApiUnitTest {

  private val loggedInUser: User = User(
    nodeId = "N:user:00000000-0000-0000-0000-000000000001",
    email = "user@test.com",
    firstName = "Test",
    middleInitial = None,
    lastName = "User",
    degree = None,
    credential = "",
    color = "",
    url = "",
    isSuperAdmin = false,
    id = 1
  )

  private val externalUser: User = User(
    nodeId = "N:user:00000000-0000-0000-0000-000000000002",
    email = "external@test.com",
    firstName = "External",
    middleInitial = None,
    lastName = "User",
    degree = None,
    credential = "",
    color = "",
    url = "",
    isSuperAdmin = false,
    id = 2
  )

  private val loggedInOrganization: Organization = Organization(
    nodeId = "N:organization:00000000-0000-0000-0000-000000000001",
    name = "Test Organization",
    slug = "test-organization",
    encryptionKeyId = Some("test-key"),
    id = 1
  )

  private val externalOrganization: Organization = Organization(
    nodeId = "N:organization:00000000-0000-0000-0000-000000000002",
    name = "External Organization",
    slug = "external-organization",
    encryptionKeyId = Some("test-key-2"),
    id = 2
  )

  private val pennsieve: Organization = Organization(
    nodeId = "N:organization:00000000-0000-0000-0000-000000000003",
    name = "Pennsieve",
    slug = "pennsieve",
    encryptionKeyId = Some("test-key-3"),
    id = 3
  )

  private var apiToken: Token = _
  private var loggedInJwt: String = _
  private var apiJwt: String = _
  private var externalJwt: String = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    addServlet(
      new APITokenController(
        insecureContainer,
        secureContainerBuilder,
        mockCognito,
        system.dispatcher
      ),
      "/*"
    )
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    state.users.clear()
    state.organizations.clear()
    state.tokens.clear()
    mockCognito.reset()

    state.users.put(loggedInUser.id, loggedInUser)
    state.users.put(externalUser.id, externalUser)
    state.organizations.put(loggedInOrganization.id, loggedInOrganization)
    state.organizations.put(externalOrganization.id, externalOrganization)
    state.organizations.put(pennsieve.id, pennsieve)

    apiToken = seedToken(
      name = "test api token",
      userId = loggedInUser.id,
      organizationId = loggedInOrganization.id
    )

    loggedInJwt = mintUserJwt(loggedInUser, loggedInOrganization)
    externalJwt = mintUserJwt(externalUser, externalOrganization)
    apiJwt = mintUserJwt(
      loggedInUser,
      loggedInOrganization,
      cognito = CognitoSession
        .API(CognitoId.TokenPoolId.randomId(), Instant.now().plusSeconds(60))
    )
  }

  private def seedToken(
    name: String,
    userId: Int,
    organizationId: Int
  ): Token = {
    val token = Token(
      name = name,
      token = UUID.randomUUID.toString,
      cognitoId = CognitoId.TokenPoolId.randomId(),
      organizationId = organizationId,
      userId = userId,
      id = state.newId()
    )
    state.tokens.put(token.token, token)
    token
  }

  test("requests should short-circuit if using a non-browser JWT") {
    post("/", headers = authorizationHeader(apiJwt)) {
      status should be(403)
    }
    get("/", headers = authorizationHeader(apiJwt)) {
      status should be(403)
    }
    put("/uuid", headers = authorizationHeader(apiJwt)) {
      status should be(403)
    }
    delete("/uuid", headers = authorizationHeader(apiJwt)) {
      status should be(403)
    }
  }

  test("should create an API token") {
    postJson(
      "/",
      write(CreateTokenRequest("new api token")),
      headers = authorizationHeader(loggedInJwt)
    ) {
      val response = parse(body).extract[APITokenSecretDTO]

      status should be(201)
      response.name should be("new api token")

      mockCognito.sentTokenInvites.length should be(1)

      val stored = state.tokens.get(response.key).value
      stored.userId should be(loggedInUser.id)
      stored.organizationId should be(loggedInOrganization.id)
    }
  }

  test("should get all user's API tokens") {
    val apiTokenTwo = seedToken(
      name = "test api token 2",
      userId = loggedInUser.id,
      organizationId = loggedInOrganization.id
    )

    get("/", headers = authorizationHeader(loggedInJwt)) {
      val result = parse(body).extract[List[APITokenDTO]]

      status should be(200)
      result.length should be(2)
      result.map(_.key) should contain allOf (apiToken.token, apiTokenTwo.token)
    }
  }

  test("should not get another user's API tokens") {
    get("/", headers = authorizationHeader(externalJwt)) {
      val result = parse(body).extract[List[APITokenDTO]]

      status should be(200)
      result.length should be(0)
    }
  }

  test("should not get user's API tokens from another organization") {
    seedToken(
      name = "pennsieve api token",
      userId = loggedInUser.id,
      organizationId = pennsieve.id
    )

    get("/", headers = authorizationHeader(loggedInJwt)) {
      val result = parse(body).extract[List[APITokenDTO]]

      status should be(200)
      result.length should be(1)
      result.map(_.key) should contain only apiToken.token
    }
  }

  test("should update a user's API Token") {
    val requestJSON = write(CreateTokenRequest("updated name"))

    putJson(
      s"/${apiToken.token}",
      requestJSON,
      headers = authorizationHeader(loggedInJwt)
    ) {
      val result = parse(body).extract[APITokenDTO]

      status should be(200)
      result.name should equal("updated name")

      state.tokens.get(apiToken.token).value.name should be("updated name")
    }
  }

  test("should not update another user's API Token") {
    val requestJSON = write(CreateTokenRequest("updated name"))

    putJson(
      s"/${apiToken.token}",
      requestJSON,
      headers = authorizationHeader(externalJwt)
    ) {
      status should be(500)
    }
  }

  test("should delete a user's API Token") {
    val uuid = apiToken.token

    delete(s"/$uuid", headers = authorizationHeader(loggedInJwt)) {
      status should be(200)

      mockCognito.sentDeletes.length should be(1)

      state.tokens.get(uuid) should be(None)
    }
  }

  test("should not delete another user's API Token") {
    delete(s"/${apiToken.token}", headers = authorizationHeader(externalJwt)) {
      status should be(500)
      state.tokens.get(apiToken.token) should be(defined)
    }
  }
}
