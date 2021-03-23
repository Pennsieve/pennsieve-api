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

package com.pennsieve.authorization.routes

import akka.http.scaladsl.unmarshalling.Unmarshaller._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.HttpMethods.POST
import akka.http.scaladsl.model.DateTime
import akka.http.scaladsl.model.headers.{ `Set-Cookie`, HttpCookie }
import akka.http.scaladsl.model.StatusCodes.{
  Accepted,
  BadRequest,
  Created,
  Forbidden,
  NotFound,
  OK,
  Unauthorized
}

import akka.testkit.TestKitBase
import akka.util.ByteString

import com.pennsieve.akka.http.EitherValue._
import com.pennsieve.models.{ DBPermission, User }
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

import io.circe.java8.time._
import io.circe.syntax._
import java.util.UUID

import scala.concurrent.duration._
import scala.concurrent._

class AuthenticationRoutesSpec
    extends AuthorizationServiceSpec
    with TestKitBase {

  "POST /authentication/login route" should {

    "successfully log in a user and return a session id" in {
      val body: LoginRequest = LoginRequest(nonAdmin.email, "password")

      testRequest(POST, "/authentication/login", json = Some(body.asJson)) ~>
        routes ~> check {
        status shouldEqual OK
        val response = responseAs[LoginResponse]
        response.message shouldBe "Welcome to Pennsieve"
        UUID.fromString(response.sessionToken.get)
        header[`Set-Cookie`] shouldEqual Some(
          `Set-Cookie`(
            HttpCookie(
              sessionTokenName,
              value = response.sessionToken.get,
              domain = Some(parentDomain)
            )
          )
        )
        response.organization.get shouldBe organizationTwo.nodeId
        response.profile.get.id shouldBe nonAdmin.nodeId
      }
    }

    "respond with a 404 when a non-existent user email is provided" in {
      val body: LoginRequest =
        LoginRequest("non-existent-email@test.com", "password")

      testRequest(POST, "/authentication/login", json = Some(body.asJson)) ~>
        routes ~> check {
        status shouldEqual Unauthorized

        responseAs[String] shouldBe "User does not exist."
      }
    }

    "respond with a 403 when a user provides a bad password" in {
      val body: LoginRequest = LoginRequest(nonAdmin.email, "bad-password")

      testRequest(POST, "/authentication/login", json = Some(body.asJson)) ~>
        routes ~> check {
        status shouldEqual Forbidden

        responseAs[String] shouldBe "Incorrect password supplied."
      }
    }

    "respond with a 400 when the request body is invalid" in {
      val body: ByteString = ByteString(
        """{"emai":"test@pennsieve.org", "password":"secret-password"}"""
      )

      testRequestWithBytes(POST, "/authentication/login", content = Some(body)) ~>
        routes ~> check {
        status shouldEqual BadRequest

        responseAs[String] shouldBe "The request content was malformed."
      }
    }

    "respond with a 400 when the request contains invalid JSON" in {
      val body: ByteString = ByteString(
        """{"email":"test@pennsieve.org", "password:"secret-password"}"""
      )

      testRequestWithBytes(POST, "/authentication/login", content = Some(body)) ~>
        routes ~> check {
        status shouldEqual BadRequest

        responseAs[String] shouldBe "The request content was malformed."
      }
    }

  }

  "POST /authentication/logout route" should {

    "return 200 and remove a session from our session storage" in {
      val token: String =
        sessionManager.generateBrowserSession(nonAdmin, 6000).await.value.uuid

      testRequest(POST, "/authentication/logout", session = Some(token)) ~>
        routes ~> check {
        status shouldEqual OK
        header[`Set-Cookie`] shouldEqual Some(
          `Set-Cookie`(
            HttpCookie(
              sessionTokenName,
              value = "deleted",
              expires = Some(DateTime.MinValue)
            )
          )
        )
      }

      sessionManager.get(token) shouldBe 'left
    }

    "return 401 when no session is provided" in {
      testRequest(POST, "/authentication/logout") ~>
        routes ~> check {
        status shouldEqual Unauthorized
      }
    }

  }

  "POST /authentication/api/session route" should {

    "generate a session from an API token and secret" in {
      val (token, secret) = testDIContainer.tokenManager
        .create("test-api-token", nonAdmin, organizationTwo)
        .await
        .value

      val body: APILoginRequest = APILoginRequest(token.token, secret.plaintext)

      testRequest(POST, "/authentication/api/session", json = Some(body.asJson)) ~>
        routes ~> check {
        status shouldEqual Created

        val response = responseAs[APILoginResponse]
        UUID.fromString(response.session_token)
        response.organization shouldBe organizationTwo.nodeId
      }
    }

    "respond with a 404 when a non-existent user email is provided" in {
      val body: APILoginRequest =
        APILoginRequest(UUID.randomUUID.toString, UUID.randomUUID.toString)

      testRequest(POST, "/authentication/api/session", json = Some(body.asJson)) ~>
        routes ~> check {
        status shouldEqual Unauthorized

        responseAs[String] shouldBe "No such API token exists."
      }
    }

    "respond with a 403 when a user provides a bad password" in {
      val (token, secret) = testDIContainer.tokenManager
        .create("test-api-token", nonAdmin, organizationTwo)
        .await
        .value

      val body: APILoginRequest = APILoginRequest(token.token, "bad-secret")

      testRequest(POST, "/authentication/api/session", json = Some(body.asJson)) ~>
        routes ~> check {
        status shouldEqual Forbidden

        responseAs[String] shouldBe "Incorrect secret supplied."
      }
    }

    "respond with a 400 when the request body is invalid" in {
      val body: ByteString =
        ByteString("""{"token":"random-token", "secret":"secret"}""")

      testRequestWithBytes(
        POST,
        "/authentication/api/session",
        content = Some(body)
      ) ~>
        routes ~> check {
        status shouldEqual BadRequest

        responseAs[String] shouldBe "The request content was malformed."
      }
    }

    "respond with a 400 when the request contains invalid JSON" in {
      val body: ByteString =
        ByteString("""{"tokenId":"random-token", "secret:"secret"}""")

      testRequestWithBytes(
        POST,
        "/authentication/api/session",
        content = Some(body)
      ) ~>
        routes ~> check {
        status shouldEqual BadRequest

        responseAs[String] shouldBe "The request content was malformed."
      }
    }

  }

  "POST /authentication/login/twofactor route" should {

    "use two-factor authentication to return a session" in {
      val user: User = createAuthyUser
      val loginBody: LoginRequest = LoginRequest(user.email, "password")

      testRequest(POST, "/authentication/login", json = Some(loginBody.asJson)) ~>
        routes ~> check {
        status shouldEqual Accepted

        val response = responseAs[TemporaryLoginResponse]
        val session = response.sessionToken.get

        val body: TwoFactorLoginRequest = TwoFactorLoginRequest("0000000")
        testRequest(
          POST,
          "/authentication/login/twofactor",
          session = response.sessionToken,
          json = Some(body.asJson)
        ) ~>
          routes ~> check {
          status shouldEqual OK

          val response = responseAs[LoginResponse]
          response.message shouldBe "Welcome to Pennsieve"
          UUID.fromString(response.sessionToken.get)
          header[`Set-Cookie`] shouldEqual Some(
            `Set-Cookie`(
              HttpCookie(
                sessionTokenName,
                value = response.sessionToken.get,
                domain = Some(parentDomain)
              )
            )
          )
          response.organization.get shouldBe organizationTwo.nodeId
          response.profile.get.id shouldBe user.nodeId
        }
      }
    }

    "return unauthorized when a bad two-factor code is provided" in {
      val user: User = createAuthyUser
      val loginBody: LoginRequest = LoginRequest(user.email, "password")

      testRequest(POST, "/authentication/login", json = Some(loginBody.asJson)) ~>
        routes ~> check {
        status shouldEqual Accepted

        val response = responseAs[TemporaryLoginResponse]
        val session = response.sessionToken.get

        val body: TwoFactorLoginRequest = TwoFactorLoginRequest("0000001")
        testRequest(
          POST,
          "/authentication/login/twofactor",
          session = response.sessionToken,
          json = Some(body.asJson)
        ) ~>
          routes ~> check {
          status shouldEqual Unauthorized
        }
      }
    }

  }

  def createAuthyUser: User = {
    val email = "authy@test.com"

    val authyUser =
      testDIContainer.authy.getUsers.createUser(email, "111-111-1113", "1")

    assert(authyUser.isOk, "Authy user creation failed")

    val user: User = userManager
      .create(
        User(
          UUID.randomUUID.toString,
          email,
          "Authy",
          None,
          "User",
          None,
          "",
          authyId = authyUser.getId
        ),
        Some("password")
      )
      .await
      .value

    organizationManager
      .addUser(organizationTwo, user, DBPermission.Delete)
      .await

    user
  }
}
