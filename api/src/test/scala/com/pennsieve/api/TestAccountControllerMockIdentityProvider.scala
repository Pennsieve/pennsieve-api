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
import com.pennsieve.aws.cognito.{
  Cognito,
  CognitoConfig,
  CognitoPoolConfig,
  MockCognitoIdentityProviderAsyncClient
}
import org.json4s.jackson.Serialization.write
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cognitoidentityprovider.model.{
  AdminCreateUserResponse,
  AttributeType,
  UserType,
  UsernameExistsException
}

import java.util.UUID

class TestAccountControllerMockIdentityProvider extends BaseApiTest {

  private val cognitoConfig: CognitoConfig = CognitoConfig(
    Region.US_EAST_1,
    CognitoPoolConfig(Region.US_EAST_1, "user-pool-id", "client-id"),
    CognitoPoolConfig(Region.US_EAST_1, "token-pool-id", "client-id"),
    CognitoPoolConfig(Region.US_EAST_1, "identity-pool-id", "")
  )

  val recaptchaClient: AntiSpamChallengeClient =
    new MockRecaptchaClient()

  val mockIdentityProvider = new MockCognitoIdentityProviderAsyncClient()
  val cognitoClient = new Cognito(mockIdentityProvider, cognitoConfig)

  override def afterStart(): Unit = {
    super.afterStart()

    addServlet(
      new AccountController(
        insecureContainer,
        cognitoConfig,
        cognitoClient,
        recaptchaClient,
        system.dispatcher
      ),
      "/*"
    )
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    mockIdentityProvider.reset()
  }

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

    val userCognitoId = UUID.randomUUID().toString
    val user = UserType
      .builder()
      .attributes(
        AttributeType
          .builder()
          .name("sub")
          .value(userCognitoId)
          .build()
      )
      .build()

    mockIdentityProvider.adminCreateUserResponse =
      Left(AdminCreateUserResponse.builder().user(user).build())

    postJson("/sign-up", write(newUserRequest)) {
      status should be(200)
      assert(body.contains(welcomeOrganization.nodeId))
    }
  }

  test(
    "sign up with an already existent email address should result in a 409 error"
  ) {
    val newUserRequest = CreateUserWithRecaptchaRequest(
      firstName = "test",
      middleInitial = None,
      lastName = "tester",
      degree = None,
      title = Some(""),
      email = "guest@test.com",
      recaptchaToken = "foooo"
    )

    mockIdentityProvider.adminCreateUserResponse =
      Right(UsernameExistsException.builder().build())

    postJson("/sign-up", write(newUserRequest)) {
      println(parsedBody)
      status should be(409)
      body should include("An account with the given email already exists")
    }
  }
}
