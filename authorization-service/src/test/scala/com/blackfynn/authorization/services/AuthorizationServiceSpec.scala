// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.authorization.routes

import com.blackfynn.authorization.{ AuthyContainer, JwtContainer, Router }
import com.blackfynn.authorization.Router.ResourceContainer
import com.blackfynn.akka.http.{ RouteService, RouterServiceSpec }
import com.blackfynn.core.utilities._
import com.blackfynn.test._
import com.blackfynn.test.helpers._
import akka.testkit.TestKitBase
import com.authy.AuthyApiClient
import com.blackfynn.clients.MockAuthyApiClient
import com.redis.RedisClientPool
import com.typesafe.config.{ Config, ConfigValueFactory }

import scala.collection.JavaConverters._
import org.scalatest.{
  BeforeAndAfterAll,
  BeforeAndAfterEach,
  Matchers,
  WordSpec
}

trait AuthorizationServiceSpec
    extends WordSpec
    with RouterServiceSpec
    with Matchers
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with CoreSpecHarness[ResourceContainer]
    with SessionSeed[ResourceContainer]
    with TestKitBase {

  val sessionTokenName: String = "Pennsieve-Token"
  val parentDomain: String = ".pennsieve.io"

  override var routeService: RouteService = _

  lazy val authorizationConfig: Config = config
    .withValue("jwt.key", ConfigValueFactory.fromAnyRef(s"test-jwt-key"))
    .withValue("jwt.duration", ConfigValueFactory.fromAnyRef(s"1800 seconds"))
    .withValue(
      "authentication.bad_login_limit",
      ConfigValueFactory.fromAnyRef(s"5")
    )
    .withValue(
      "authentication.api_session_timeout",
      ConfigValueFactory.fromAnyRef(s"7200")
    )
    .withValue(
      "authentication.session_timeout",
      ConfigValueFactory.fromAnyRef(s"28800")
    )
    .withValue(
      "authentication.session_token",
      ConfigValueFactory.fromAnyRef(sessionTokenName)
    )
    .withValue(
      "authentication.parent_domain",
      ConfigValueFactory.fromAnyRef(parentDomain)
    )
    .withValue(
      "authentication.temporary_session_timeout",
      ConfigValueFactory.fromAnyRef(s"600")
    )
    .withValue(
      "authy.key",
      ConfigValueFactory.fromAnyRef(s"f45ec9af9dcb7419dc52b05889c858e9")
    )
    .withValue(
      "authy.url",
      ConfigValueFactory.fromAnyRef(s"http://sandbox-api.authy.com")
    )
    .withValue(
      "workspaces.allowed",
      ConfigValueFactory.fromIterable(List(1).asJava)
    )

  override def createTestDIContainer: ResourceContainer = {

    val diContainer =
      new InsecureContainer(authorizationConfig) with RedisManagerContainer
      with DatabaseContainer with SessionManagerContainer
      with OrganizationManagerContainer with JwtContainer with AuthyContainer
      with TermsOfServiceManagerContainer with TokenManagerContainer {
        override val authy: AuthyApiClient =
          new MockAuthyApiClient(authyKey, authyUrl, authyDebugMode)
        override val postgresUseSSL = false
      }

    routeService = new Router(diContainer)

    diContainer
  }
}
