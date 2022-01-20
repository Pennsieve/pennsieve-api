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

import com.pennsieve.aws.cognito.MockCognito
import com.pennsieve.domain.NotFound
import com.pennsieve.dtos.{ APITokenDTO, APITokenSecretDTO }
import com.pennsieve.managers.SecureTokenManager
import com.pennsieve.models.DBPermission.Administer
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.write
import org.scalatest.EitherValues._

class APITokenControllerSpecs extends BaseApiTest {
  val mockCognito: MockCognito = new MockCognito()

  override def afterStart(): Unit = {
    super.afterStart()

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
      write(CreateTokenRequest("test api token")),
      headers = authorizationHeader(loggedInJwt)
    ) {
      val response = parse(body).extract[APITokenSecretDTO]

      status should be(201)
      response.name should be("test api token")

      mockCognito.sentTokenInvites.length should be(1)

      val secureTokenManager =
        new SecureTokenManager(loggedInUser, insecureContainer.db)
      val token = secureTokenManager.get(response.key).await.right.value

      token.userId should be(loggedInUser.id)

    }
  }

  test("should get all user's API tokens") {
    val (apiTokenTwo, secret) = tokenManager
      .create(
        "test api token 2",
        loggedInUser,
        loggedInOrganization,
        mockCognito
      )
      .await
      .right
      .value

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
    val (pennsieveToken, secret) = tokenManager
      .create(
        "pennsieve api token",
        loggedInUser,
        externalOrganization,
        mockCognito
      )
      .await
      .right
      .value

    get("/", headers = authorizationHeader(loggedInJwt)) {
      val result = parse(body).extract[List[APITokenDTO]]

      status should be(200)
      result.length should be(1)
      result.map(_.key) should contain only (apiToken.token)
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

      val secureTokenManager =
        new SecureTokenManager(loggedInUser, insecureContainer.db)
      val token = secureTokenManager.get(apiToken.token).await.right.value
      token.name should be("updated name")
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

    delete(s"/${uuid}", headers = authorizationHeader(loggedInJwt)) {
      status should be(200)

      val secureTokenManager =
        new SecureTokenManager(loggedInUser, insecureContainer.db)

      mockCognito.sentDeletes.length should be(1)

      secureTokenManager.get(uuid).await.left.value should equal(
        NotFound(s"Token ($uuid)")
      )
      tokenManager.get(uuid).await.left.value should equal(
        NotFound(s"Token ($uuid)")
      )
    }
  }

  test("should not delete another user's API Token") {
    delete(s"/${apiToken.token}", headers = authorizationHeader(externalJwt)) {
      status should be(500)

      get("/", headers = authorizationHeader(loggedInJwt)) {
        val result = parse(body).extract[List[APITokenDTO]]

        status should be(200)
        result.map(_.key) should contain(apiToken.token)
      }
    }
  }
}
