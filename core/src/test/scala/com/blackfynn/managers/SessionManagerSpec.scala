// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.managers

import com.blackfynn.domain.NotFound
import com.blackfynn.test.helpers.EitherValue._
import com.blackfynn.domain.Sessions.{
  sessionKey,
  APISession,
  BrowserSession,
  TemporarySession
}
import org.scalatest.EitherValues._

import scala.concurrent.ExecutionContext.Implicits.global

class SessionManagerSpec extends BaseManagerSpec {

  implicit def um: UserManager = userManager

  "create" should "generate a new browser session" in {
    val user = createUser()

    val uuid =
      sessionManager.generateBrowserSession(user).await.right.value.uuid
    val session = sessionManager.get(uuid).right.value

    assert(session.`type` == BrowserSession)
    assert(session.user().await.value == user)
  }

  "create" should "generate a new temporary session" in {
    val user = createUser()

    val uuid =
      sessionManager.generateTemporarySession(user).await.right.value.uuid
    val session = sessionManager.get(uuid).right.value

    assert(session.`type` == TemporarySession)
    assert(session.user().await.value == user)
  }

  "create" should "generate a new api session" in {
    val user = createUser()

    val _secureTokenManager = secureTokenManager(user)

    val (token, secret) = _secureTokenManager
      .create("test token", user, testOrganization)
      .await
      .right
      .value

    val uuid = sessionManager
      .generateAPISession(token, 1200, _secureTokenManager)
      .await
      .right
      .value
      .uuid
    val session = sessionManager.get(uuid).right.value

    assert(session.`type` == APISession(token.token))
    assert(session.user().await.value == user)
  }

  "get" should "retrieve a session by uuid" in {
    val user = createUser()

    val uuid =
      sessionManager.generateBrowserSession(user).await.right.value.uuid
    val session = sessionManager.get(uuid).right.value

    assert(session.uuid == uuid)
  }

  "get" should "not retrieve a session with a non-existent uuid" in {
    val user = createUser()

    val uuid =
      sessionManager.generateBrowserSession(user).await.right.value.uuid
    val fakeUUID = s"${uuid}foo"

    assert(
      sessionManager.get(fakeUUID).left.value == NotFound(sessionKey(fakeUUID))
    )
  }

  "remove" should "delete a session" in {
    val user = createUser()

    val uuid =
      sessionManager.generateBrowserSession(user).await.right.value.uuid
    val session = sessionManager.get(uuid).right.value

    assert(session.`type` == BrowserSession)

    sessionManager.remove(session)

    assert(sessionManager.get(uuid).left.value == NotFound(sessionKey(uuid)))
  }

  "remove" should "fail to delete a non-existent session" in {
    val user = createUser()

    val uuid =
      sessionManager.generateBrowserSession(user).await.right.value.uuid
    sessionManager.remove(s"${uuid}foo")

    assert(sessionManager.get(uuid).right.value.`type` == BrowserSession)
  }

}
