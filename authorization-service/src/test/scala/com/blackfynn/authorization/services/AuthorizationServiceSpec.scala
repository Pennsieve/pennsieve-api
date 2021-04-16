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

import com.pennsieve.authorization.{ JwtContainer, Router }
import com.pennsieve.authorization.Router.ResourceContainer
import com.pennsieve.akka.http.{ RouteService, RouterServiceSpec }
import com.pennsieve.core.utilities._
import com.pennsieve.test._
import com.pennsieve.test.helpers._
import akka.testkit.TestKitBase
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
    with CognitoJwtSeed[ResourceContainer]
    with TestKitBase {

  val sessionTokenName: String = "Pennsieve-Token"
  val parentDomain: String = ".pennsieve.io"

  override var routeService: RouteService = _

  implicit val readmeKey = "test-readme-key"

  lazy val authorizationConfig: Config = config
    .withValue("jwt.key", ConfigValueFactory.fromAnyRef(s"test-jwt-key"))
    .withValue("jwt.duration", ConfigValueFactory.fromAnyRef(s"1800 seconds"))

  override def createTestDIContainer: ResourceContainer = {

    val diContainer =
      new InsecureContainer(authorizationConfig) with DatabaseContainer
      with UserManagerContainer with OrganizationManagerContainer
      with JwtContainer with TermsOfServiceManagerContainer
      with TokenManagerContainer {
        override val postgresUseSSL = false
      }

    routeService = new Router(diContainer)

    diContainer
  }
}
