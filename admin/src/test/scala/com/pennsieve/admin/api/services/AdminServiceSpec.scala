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

package com.pennsieve.admin.api.services

import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.testkit.TestKitBase
import com.pennsieve.admin.api.{ AdminContainer, Router }
import com.pennsieve.admin.api.Router.{
  AdminETLServiceContainer,
  InsecureResourceContainer,
  SecureResourceContainer
}
import com.pennsieve.aws.cognito.{
  CognitoConfig,
  CognitoPoolConfig,
  LocalCognitoContainer,
  MockCognito
}
import com.pennsieve.aws.s3.LocalS3Container
import com.pennsieve.akka.http.{ RouteService, RouterServiceSpec }
import com.pennsieve.aws.email.LocalEmailContainer
import com.pennsieve.aws.queue.LocalSQSContainer
import com.pennsieve.core.utilities._
import com.pennsieve.models.{ Organization, User }
import com.pennsieve.test._
import com.pennsieve.test.helpers._
import com.pennsieve.auth.middleware.Jwt
import com.pennsieve.clients._
import com.typesafe.config.{ Config, ConfigValueFactory }
import org.scalatest._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import software.amazon.awssdk.regions.Region

import scala.concurrent.Future

trait AdminServiceSpec
    extends AnyWordSpec
    with RouterServiceSpec
    with Matchers
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with CoreSpecHarness[InsecureResourceContainer]
    with SQSDockerContainer
    with CognitoJwtSeed[InsecureResourceContainer]
    with TestKitBase {
  self =>

  override var routeService: RouteService = _

  var secureContainerBuilder: Router.SecureResourceContainerBuilder = _

  lazy val adminConfig: Config = config
    .withValue("pennsieve.jwt.key", ConfigValueFactory.fromAnyRef("testkey"))
    .withValue(
      "job_scheduling_service.jwt.key",
      ConfigValueFactory.fromAnyRef("test-key")
    )
    .withValue(
      "pennsieve.discover_service.host",
      ConfigValueFactory.fromAnyRef("test-discover-service")
    )
    .withValue(
      "job_scheduling_service.host",
      ConfigValueFactory.fromAnyRef("localhost:8080")
    )
    .withValue(
      "job_scheduling_service.quota",
      ConfigValueFactory.fromAnyRef(10)
    )
    .withValue(
      "job_scheduling_service.queue_size",
      ConfigValueFactory.fromAnyRef(100)
    )
    .withValue(
      "job_scheduling_service.rate_limit",
      ConfigValueFactory.fromAnyRef(10)
    )
    .withValue(
      "s3.storage_bucket",
      ConfigValueFactory.fromAnyRef("test-storage-pennsieve")
    )
    .withValue(
      "discover_app.host",
      ConfigValueFactory.fromAnyRef("discover.pennsieve.io")
    )
    .withValue("s3.host", ConfigValueFactory.fromAnyRef("test-s3-host"))
    .withValue("s3.region", ConfigValueFactory.fromAnyRef("us-east-1"))
    .withFallback(sqsContainer.config)

  def jwtConfig: Jwt.Config = new Jwt.Config {
    override val key = adminConfig.getString("pennsieve.jwt.key")
  }

  override def beforeEach() = {
    super.beforeEach()

    testDIContainer.customTermsOfServiceClient
      .asInstanceOf[MockCustomTermsOfServiceClient]
      .reset()

    testDIContainer.cognitoClient
      .asInstanceOf[MockCognito]
      .reset()
  }

  // Hack to inject the mock JWK/JWT provider
  // TODO: figure out a cleaner way to inject this
  private val adminServiceSpec = self

  override def createTestDIContainer: InsecureResourceContainer = {

    val diContainer =
      new InsecureContainer(adminConfig) with InsecureCoreContainer
      with LocalEmailContainer with LocalSQSContainer with AdminContainer
      with LocalS3Container with MockCustomTermsOfServiceClientContainer
      with LocalCognitoContainer {
        override val postgresUseSSL = false
        override lazy val cognitoClient = new MockCognito()
        override lazy val cognitoConfig = adminServiceSpec.cognitoConfig
      }

    secureContainerBuilder = (user: User, organization: Organization) =>
      new SecureContainer(
        config = diContainer.config,
        _db = diContainer.db,
        user = user,
        organization = organization
      ) with SecureCoreContainer with LocalEmailContainer
      with MessageTemplatesContainer with AdminETLServiceContainer {
        override val postgresUseSSL = false
      }

    implicit val httpClient: HttpRequest => Future[HttpResponse] = { _ =>
      Future.successful(HttpResponse())
    }

    routeService =
      new Router(diContainer, secureContainerBuilder, new MockPublishClient())

    diContainer
  }
}
