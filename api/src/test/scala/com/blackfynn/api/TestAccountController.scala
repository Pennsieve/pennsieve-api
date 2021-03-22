// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.api

import com.blackfynn.aws.email.LoggingEmailer
import com.blackfynn.models.DBPermission
import com.blackfynn.web.Settings
import com.blackfynn.aws.cognito.MockCognito

import java.time.Duration

import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.write
import org.scalatest.EitherValues._
import org.scalatest._

class TestAccountController extends BaseApiTest {

  override def afterStart(): Unit = {
    super.afterStart()

    addServlet(
      new AccountController(insecureContainer, system.dispatcher),
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
