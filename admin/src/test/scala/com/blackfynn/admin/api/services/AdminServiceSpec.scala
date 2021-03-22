// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.admin.api.services

import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import com.blackfynn.admin.api.{ AdminContainer, Router }
import com.blackfynn.admin.api.Router.{
  AdminETLServiceContainer,
  AdminETLServiceContainerImpl,
  InsecureResourceContainer,
  SecureResourceContainer
}
import com.blackfynn.aws.cognito.{ LocalCognitoContainer, MockCognito }
import com.blackfynn.aws.s3.LocalS3Container
import com.blackfynn.akka.http.{ RouteService, RouterServiceSpec }
import com.blackfynn.aws.email.LocalEmailContainer
import com.blackfynn.aws.queue.LocalSQSContainer
import com.blackfynn.core.utilities._
import com.blackfynn.models.{ Organization, User }
import com.blackfynn.test._
import com.blackfynn.test.helpers._
import akka.testkit.TestKitBase
import com.blackfynn.auth.middleware.Jwt
import com.blackfynn.clients._
import com.typesafe.config.{ Config, ConfigValueFactory }
import org.scalatest._

import scala.concurrent.Future

trait AdminServiceSpec
    extends WordSpec
    with RouterServiceSpec
    with Matchers
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with CoreSpecHarness[InsecureResourceContainer]
    with SQSDockerContainer
    with SessionSeed[InsecureResourceContainer]
    with TestKitBase {

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

  override def createTestDIContainer: InsecureResourceContainer = {

    val diContainer =
      new InsecureContainer(adminConfig) with InsecureCoreContainer
      with LocalEmailContainer with LocalSQSContainer with AdminContainer
      with LocalS3Container with MockCustomTermsOfServiceClientContainer
      with MockJobSchedulingServiceContainer with LocalCognitoContainer {
        override val postgresUseSSL = false
        override lazy val cognitoClient = new MockCognito()
      }

    secureContainerBuilder = (user: User, organization: Organization) =>
      new SecureContainer(
        config = diContainer.config,
        _db = diContainer.db,
        _redisClientPool = diContainer.redisClientPool,
        user = user,
        organization = organization
      ) with SecureCoreContainer with LocalEmailContainer
      with MessageTemplatesContainer with MockJobSchedulingServiceContainer
      with AdminETLServiceContainer with RoleOverrideContainer {
        override val jobSchedulingServiceClient: JobSchedulingServiceClient =
          diContainer.jobSchedulingServiceClient
        override val postgresUseSSL = false
      }

    val httpClient: HttpRequest => Future[HttpResponse] = { _ =>
      Future.successful(HttpResponse())
    }

    routeService = new Router(
      diContainer,
      secureContainerBuilder,
      new MockPublishClient(httpClient, executor, materializer)
    )

    diContainer
  }
}
