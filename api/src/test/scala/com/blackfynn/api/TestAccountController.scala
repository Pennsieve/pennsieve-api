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

import com.pennsieve.aws.cognito._
import com.pennsieve.aws.email.LoggingEmailer
import com.pennsieve.models.DBPermission
import com.pennsieve.web.Settings
import com.pennsieve.aws.cognito.MockCognito
import software.amazon.awssdk.regions.Region

import java.time.Duration

import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.write
import org.scalatest.EitherValues._
import org.scalatest._

class TestAccountController extends BaseApiTest {

  val cognitoConfig = CognitoConfig(
    Region.US_EAST_1,
    CognitoPoolConfig(Region.US_EAST_1, "user-pool-id", "client-id"),
    CognitoPoolConfig(Region.US_EAST_1, "token-pool-id", "client-id")
  )

  override def afterStart(): Unit = {
    super.afterStart()

    addServlet(
      new AccountController(
        insecureContainer,
        cognitoConfig,
        system.dispatcher
      ),
      "/*"
    )
  }

  test("reset password") {
    val temporarySession =
      sessionManager.generateTemporarySession(loggedInUser).await.right.value

    val request =
      write(ResetPasswordRequest(temporarySession.uuid, "okPassword1!"))

    postJson("/reset", request) {
      status should equal(200)
      parse(body)
        .extract[ResetPasswordResponse]
        .profile
        .id should equal(loggedInUser.nodeId)
    }

    post("/reset", request) {
      status should equal(401)
    }
  }

  test("reset password should validate a password") {
    val temporarySession =
      sessionManager.generateTemporarySession(loggedInUser).await.right.value

    val request =
      write(ResetPasswordRequest(temporarySession.uuid, "badP"))

    postJson("/reset", request) {
      status should equal(400)
      body should include(Settings.password_validation_error_message)
    }
  }

  test("bad json should not log password") {
    val temporarySession =
      sessionManager.generateTemporarySession(loggedInUser).await.right.value

    val request: String =
      s"""{"resetToken":"${temporarySession.uuid}", "newPassword:"secret-password"}"""

    postJson("/reset", request) {
      status should equal(400)
      body should equal("invalid json in request body")
    }
  }

  test("bad reset request should not log password") {
    val temporarySession =
      sessionManager.generateTemporarySession(loggedInUser).await.right.value

    val request: String =
      s"""{"resetToke":"${temporarySession.uuid}", "newPassword":"secret-password"}"""

    postJson("/reset", request) {
      status should equal(400)
      body should equal("invalid request body")
    }
  }

  // TODO update
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
      .right
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
      userInviteManager.get(invite.id).await should be('Left)
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
      .right
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

}
