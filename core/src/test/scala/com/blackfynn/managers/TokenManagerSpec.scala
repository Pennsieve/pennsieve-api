// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.managers

import com.blackfynn.aws.cognito.MockCognito
import com.blackfynn.domain.{ CoreError, NotFound, PermissionError }
import com.blackfynn.models.DBPermission.{ Read, Write }
import com.blackfynn.models.Token
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
      .right
      .value
    assert(token.userId == user.id)
    assert(
      _secureTokenManager.get(user, testOrganization).await.right.value == List(
        token
      )
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
      .right
      .value

    assert(_secureTokenManager.get(token.token).await.right.value == token)
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
      .right
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
      .right
      .value
    val (tokenTwo, secret2) = _secureTokenManager
      .create("test token 2", user, testOrganization, mockCognitoClient)
      .await
      .right
      .value

    val tokens: Set[Token] =
      _secureTokenManager.get(user, testOrganization).await.right.value.toSet

    assert(tokens.contains(tokenOne))
    assert(tokens.contains(tokenTwo))
  }

  "update" should "change the name of an api token" in {
    val user = createUser()
    val _secureTokenManager = secureTokenManager(user)

    val (token, secret) = _secureTokenManager
      .create("test token", user, testOrganization, mockCognitoClient)
      .await
      .right
      .value
    val updated = _secureTokenManager
      .update(token.copy(name = "updated token"))
      .await
      .right
      .value

    assert(
      _secureTokenManager
        .get(token.token)
        .await
        .right
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
      .right
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
      .right
      .value

    assert(
      _secureTokenManager
        .get(user, testOrganization)
        .await
        .right
        .value
        .contains(token)
    )

    val deleteToken: Either[CoreError, Int] =
      _secureTokenManager.delete(token, mockCognitoClient).await

    assert(
      !(_secureTokenManager
        .get(user, testOrganization)
        .await
        .right
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
      .right
      .value
    val failedDelete: CoreError =
      _secureTokenManagerTwo.delete(token, mockCognitoClient).await.left.value

    assert(failedDelete == PermissionError(badUser.nodeId, Read, token.token))
  }
}
