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

package com.pennsieve.managers

import com.pennsieve.aws.cognito.MockCognito
import com.pennsieve.domain.{ CoreError, NotFound, PermissionError }
import com.pennsieve.models.DBPermission.{ Read, Write }
import com.pennsieve.models.Token
import org.scalatest.EitherValues._

import scala.concurrent.ExecutionContext.Implicits.global

class TokenManagerSpec extends BaseManagerSpec {
  val mockCognitoClient: MockCognito = new MockCognito()

  "create" should "create a new api token node" in {
    val user = createUser()
    val _secureTokenManager = secureTokenManager(user)

    val (token, secret) = _secureTokenManager
      .create("test token", user, testOrganization, mockCognitoClient)
      .await
      .value
    assert(token.userId == user.id)
    assert(
      _secureTokenManager.get(user, testOrganization).await.value == List(token)
    )
  }

  "create" should "not create an api token for another user" in {
    val user = createUser()

    val anotherOrganization = createOrganization()
    val anotherDataset = createDataset(anotherOrganization)
    val badUser = createUser(
      email = "stealer@evil.com",
      organization = Some(anotherOrganization),
      datasets = List(anotherDataset)
    )

    val _secureTokenManager = secureTokenManager(badUser)

    val failedCreate = _secureTokenManager
      .create("test token", user, anotherOrganization, mockCognitoClient)
      .await
      .left
      .value

    assert(failedCreate == PermissionError(badUser.nodeId, Write, ""))
  }

  "get" should "get an api token by uuid" in {
    val user = createUser()
    val _secureTokenManager = secureTokenManager(user)

    val (token, secret) = _secureTokenManager
      .create("test token", user, testOrganization, mockCognitoClient)
      .await
      .value

    assert(_secureTokenManager.get(token.token).await.value == token)
  }

  "get" should "fail to get a non-existent api token" in {
    val user = createUser()
    val _secureTokenManager = secureTokenManager(user)

    val token = _secureTokenManager.create(
      "test token",
      user,
      testOrganization,
      mockCognitoClient
    )

    val fakeUUID = "foo"
    val failedGet = _secureTokenManager.get(fakeUUID).await.left.value

    assert(failedGet == NotFound("Token (foo)"))
  }

  "get" should "not get another user's api tokens" in {
    val user = createUser()

    val anotherOrganization = createOrganization()
    val anotherDataset = createDataset(anotherOrganization)
    val badUser = createUser(
      email = "stealer@evil.com",
      organization = Some(anotherOrganization),
      datasets = List(anotherDataset)
    )

    val _secureTokenManagerOne = secureTokenManager(user)
    val _secureTokenManagerTwo = secureTokenManager(badUser)

    val (token, secret) = _secureTokenManagerOne
      .create("test token", user, testOrganization, mockCognitoClient)
      .await
      .value

    val failedGet: CoreError =
      _secureTokenManagerTwo.get(token.token).await.left.value

    assert(failedGet == PermissionError(badUser.nodeId, Read, token.token))
  }

  "get" should "get all of a user's api tokens" in {
    val user = createUser()
    val _secureTokenManager = secureTokenManager(user)

    val (tokenOne, secret1) = _secureTokenManager
      .create("test token 1", user, testOrganization, mockCognitoClient)
      .await
      .value
    val (tokenTwo, secret2) = _secureTokenManager
      .create("test token 2", user, testOrganization, mockCognitoClient)
      .await
      .value

    val tokens: Set[Token] =
      _secureTokenManager.get(user, testOrganization).await.value.toSet

    assert(tokens.contains(tokenOne))
    assert(tokens.contains(tokenTwo))
  }

  "update" should "change the name of an api token" in {
    val user = createUser()
    val _secureTokenManager = secureTokenManager(user)

    val (token, secret) = _secureTokenManager
      .create("test token", user, testOrganization, mockCognitoClient)
      .await
      .value
    val updated = _secureTokenManager
      .update(token.copy(name = "updated token"))
      .await
      .value

    assert(
      _secureTokenManager
        .get(token.token)
        .await
        .value
        .name == "updated token"
    )
  }

  "update" should "not update another user's api token" in {
    val user = createUser()

    val anotherOrganization = createOrganization()
    val anotherDataset = createDataset(anotherOrganization)
    val badUser = createUser(
      email = "stealer@evil.com",
      organization = Some(anotherOrganization),
      datasets = List(anotherDataset)
    )

    val _secureTokenManagerOne = secureTokenManager(user)
    val _secureTokenManagerTwo = secureTokenManager(badUser)

    val (token, secret) = _secureTokenManagerOne
      .create("test token", user, testOrganization, mockCognitoClient)
      .await
      .value
    val failedUpdate = _secureTokenManagerTwo
      .update(token.copy(name = "updated name"))
      .await
      .left
      .value

    assert(failedUpdate == PermissionError(badUser.nodeId, Read, token.token))
  }

  "delete" should "delete an api token" in {
    val user = createUser()
    val _secureTokenManager = secureTokenManager(user)

    val (token, secret) = _secureTokenManager
      .create("test token", user, testOrganization, mockCognitoClient)
      .await
      .value

    assert(
      _secureTokenManager
        .get(user, testOrganization)
        .await
        .value
        .contains(token)
    )

    val deleteToken: Either[CoreError, Int] =
      _secureTokenManager.delete(token, mockCognitoClient).await

    assert(
      !(_secureTokenManager
        .get(user, testOrganization)
        .await
        .value
        .contains(token))
    )
  }

  "delete" should "not delete another user's api token" in {
    val user = createUser()

    val anotherOrganization = createOrganization()
    val anotherDataset = createDataset(anotherOrganization)
    val badUser = createUser(
      email = "stealer@evil.com",
      organization = Some(anotherOrganization),
      datasets = List(anotherDataset)
    )

    val _secureTokenManagerOne = secureTokenManager(user)
    val _secureTokenManagerTwo = secureTokenManager(badUser)

    val (token, secret) = _secureTokenManagerOne
      .create("test token", user, testOrganization, mockCognitoClient)
      .await
      .value
    val failedDelete: CoreError =
      _secureTokenManagerTwo.delete(token, mockCognitoClient).await.left.value

    assert(failedDelete == PermissionError(badUser.nodeId, Read, token.token))
  }
}
