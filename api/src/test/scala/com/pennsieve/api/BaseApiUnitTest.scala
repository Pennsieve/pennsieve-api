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

import akka.actor.ActorSystem
import akka.testkit.TestKitBase
import cats.implicits._
import com.pennsieve.aws.cognito.MockCognito
import com.pennsieve.aws.email.LocalEmailContainer
import com.pennsieve.aws.queue.LocalSQSContainer
import com.pennsieve.aws.sns.{ LocalSNSContainer, SNSContainer }
import com.pennsieve.auth.middleware.{
  CognitoSession,
  EncryptionKeyId,
  Jwt,
  OrganizationId,
  OrganizationNodeId,
  UserClaim,
  UserId
}
import com.pennsieve.auth.middleware.Jwt.Role.RoleIdentifier
import com.pennsieve.core.utilities.{
  ChangelogContainer,
  DatasetPublicationStatusContainer,
  _
}
import com.pennsieve.helpers.APIContainers.{
  InsecureAPIContainer,
  SecureAPIContainer,
  SecureContainerBuilderType
}
import com.pennsieve.helpers.fakes.{
  FakeSecureOrganizationManager,
  FakeSecureTokenManager,
  FakeUserManager,
  InMemoryState
}
import com.pennsieve.helpers.{
  ApiSNSContainer,
  ApiSQSContainer,
  MockSNSContainer
}
import com.pennsieve.managers.{
  SecureOrganizationManager,
  SecureTokenManager,
  UserManager
}
import com.pennsieve.models.{ CognitoId, Organization, Role, User }
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.traits.TimeSeriesDBContainer
import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }
import org.json4s.jackson.JsonMethods._
import org.json4s.{ DefaultFormats, Formats }
import org.scalatest.OptionValues._
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach }
import org.scalatest.funsuite.AnyFunSuite
import org.scalatra.swagger.Swagger
import org.scalatra.test.scalatest.ScalatraSuite
import shapeless.syntax.inject._

import java.time.Instant
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
  * Base for **unit** tests of API controllers. Brings up Scalatra against a
  * fake-backed `InsecureAPIContainer` / `SecureAPIContainer`. No Postgres,
  * no LocalStack, no Docker.
  *
  * Persistence: in-memory `state`, mutated via fakes. The `db: Database`
  * passed into the cake is a Slick `Database` pointing at an unreachable URL
  * — Slick is lazy, so the object constructs fine but any actual `db.run(...)`
  * call would fail at connection time. The fakes override every method that
  * production hits, so `db` is never accessed in well-behaved tests.
  *
  * If a test path you didn't anticipate calls a manager method that isn't
  * stubbed, the fake's trait body falls through to `db.run(...)` and the fake
  * throws a clear "method not stubbed" message — that loud failure is the
  * point.
  */
trait BaseApiUnitTest
    extends AnyFunSuite
    with ScalatraSuite
    with TestKitBase
    with BeforeAndAfterEach
    with BeforeAndAfterAll {

  implicit lazy val system: ActorSystem = ActorSystem("ApiUnitTest")
  implicit lazy val ec: ExecutionContext = system.dispatcher

  implicit lazy val jwtConfig: Jwt.Config = new Jwt.Config {
    override def key = "testkey"
  }

  implicit val swagger: Swagger = new com.pennsieve.web.SwaggerApp

  protected implicit val jsonFormats: Formats = DefaultFormats

  protected val state: InMemoryState = new InMemoryState

  /** Phantom Slick Database — never connects, used only to satisfy the
    * cake's `_db` parameter. Any `.run(...)` against it would throw at
    * connection time; fakes intercept all manager methods so this never
    * actually runs in tests. */
  protected lazy val phantomDb: Database =
    Database.forURL(
      url = "jdbc:postgresql://nowhere.invalid:1/none",
      driver = "org.postgresql.Driver"
    )

  protected lazy val config: Config = ConfigFactory
    .empty()
    .withValue("postgres.host", ConfigValueFactory.fromAnyRef("nowhere"))
    .withValue("postgres.port", ConfigValueFactory.fromAnyRef(1))
    .withValue("postgres.database", ConfigValueFactory.fromAnyRef("none"))
    .withValue("postgres.user", ConfigValueFactory.fromAnyRef("u"))
    .withValue("postgres.password", ConfigValueFactory.fromAnyRef("p"))
    .withValue("data.postgres.host", ConfigValueFactory.fromAnyRef("nowhere"))
    .withValue("data.postgres.port", ConfigValueFactory.fromAnyRef(1))
    .withValue("data.postgres.database", ConfigValueFactory.fromAnyRef("none"))
    .withValue("data.postgres.user", ConfigValueFactory.fromAnyRef("u"))
    .withValue("data.postgres.password", ConfigValueFactory.fromAnyRef("p"))
    .withValue("environment", ConfigValueFactory.fromAnyRef("local"))
    .withValue("email.host", ConfigValueFactory.fromAnyRef("test"))
    .withValue(
      "email.support_email",
      ConfigValueFactory.fromAnyRef("test@test")
    )
    .withValue("discover_app.host", ConfigValueFactory.fromAnyRef("disc"))
    .withValue("sqs.host", ConfigValueFactory.fromAnyRef("http://localhost"))
    .withValue("sqs.queue", ConfigValueFactory.fromAnyRef("http://localhost/q"))
    .withValue(
      "sqs.queue_v2",
      ConfigValueFactory.fromAnyRef("http://localhost/q2")
    )
    .withValue("sqs.region", ConfigValueFactory.fromAnyRef("us-east-1"))
    .withValue("sns.topic", ConfigValueFactory.fromAnyRef("test-topic"))
    .withValue(
      "pennsieve.changelog.sns_topic",
      ConfigValueFactory.fromAnyRef("test-topic")
    )
    .withValue(
      "pennsieve.uploads.queue",
      ConfigValueFactory.fromAnyRef("http://localhost/uploads")
    )
    .withValue(
      "pennsieve.api_host",
      ConfigValueFactory.fromAnyRef("http://localhost")
    )
    .withValue(
      "pennsieve.job_scheduling_service.host",
      ConfigValueFactory.fromAnyRef("http://localhost")
    )
    .withValue(
      "pennsieve.job_scheduling_service.queue_size",
      ConfigValueFactory.fromAnyRef(100)
    )
    .withValue(
      "pennsieve.job_scheduling_service.rate_limit",
      ConfigValueFactory.fromAnyRef(10)
    )
    .withValue(
      "pennsieve.packages_pagination.default_page_size",
      ConfigValueFactory.fromAnyRef(1)
    )
    .withValue(
      "pennsieve.packages_pagination.max_page_size",
      ConfigValueFactory.fromAnyRef(1000)
    )
    .withValue(
      "pennsieve.publishing.default_workflow",
      ConfigValueFactory.fromAnyRef("5")
    )
    .withValue("jwt.key", ConfigValueFactory.fromAnyRef("testkey"))
    .withValue("jwt.duration", ConfigValueFactory.fromAnyRef("60 seconds"))

  protected lazy val mockCognito: MockCognito = new MockCognito()

  protected lazy val insecureContainer: InsecureAPIContainer = {
    val st = state
    new InsecureContainer(config) with InsecureCoreContainer
    with LocalEmailContainer with MessageTemplatesContainer with DataDBContainer
    with TimeSeriesDBContainer with LocalSQSContainer with MockSNSContainer
    with ApiSNSContainer with ApiSQSContainer {
      override val postgresUseSSL = false
      override val dataPostgresUseSSL = false
      override lazy val db: Database = phantomDb
      override lazy val dataDB: Database = phantomDb
      override lazy val userManager: UserManager = new FakeUserManager(st)
      override lazy val organizationManager: SecureOrganizationManager =
        new FakeSecureOrganizationManager(st, User.serviceUser())
      override lazy val tokenManager: SecureTokenManager =
        new FakeSecureTokenManager(st, User.serviceUser())
    }
  }

  protected lazy val secureContainerBuilder: SecureContainerBuilderType = {
    (u, org) =>
      val st = state
      new SecureContainer(
        config = config,
        _db = phantomDb,
        user = u,
        organization = org
      ) with SecureCoreContainer with LocalEmailContainer with MockSNSContainer
      with ChangelogContainer with DatasetPublicationStatusContainer {
        override val postgresUseSSL = false
        override val dataPostgresUseSSL = false
        override lazy val userManager: UserManager = new FakeUserManager(st)
        override lazy val organizationManager: SecureOrganizationManager =
          new FakeSecureOrganizationManager(st, u)
        override lazy val tokenManager: SecureTokenManager =
          new FakeSecureTokenManager(st, u)
      }
  }

  /**
    * Mint a JWT directly without any DB lookup. The role is fabricated to
    * include just enough for downstream auth checks.
    */
  protected def mintUserJwt(
    user: User,
    organization: Organization,
    cognito: CognitoSession =
      CognitoSession.Browser(CognitoId.UserPoolId.randomId(),
        Instant.now().plusSeconds(60)),
    role: Role = Role.Owner,
    duration: FiniteDuration = 60.seconds
  ): String = {
    val orgRole = Jwt.OrganizationRole(
      OrganizationId(organization.id)
        .inject[Jwt.Role.RoleIdentifier[OrganizationId]],
      role,
      organization.encryptionKeyId.map(EncryptionKeyId),
      OrganizationNodeId(organization.nodeId).some,
      Some(Nil)
    )
    val claim = Jwt.generateClaim(
      UserClaim(
        id = UserId(user.id),
        roles = List(orgRole),
        cognito = Some(cognito)
      ),
      duration
    )
    Jwt.generateToken(claim).value
  }

  protected def authorizationHeader(jwt: String): Map[String, String] =
    Map("Authorization" -> s"Bearer $jwt")

  protected val contentTypeApplicationJsonHeader: (String, String) =
    "Content-Type" -> "application/json"

  protected def postJson[A](
    uri: String,
    body: String,
    headers: Map[String, String] = Map()
  )(
    f: => A
  ): A =
    post(
      uri,
      body.getBytes("utf-8"),
      headers = headers + contentTypeApplicationJsonHeader
    )(f)

  protected def putJson[A](
    uri: String,
    body: String = "",
    headers: Map[String, String] = Map()
  )(
    f: => A
  ): A =
    put(
      uri,
      body.getBytes("utf-8"),
      headers = headers + contentTypeApplicationJsonHeader
    )(f)

  override def afterAll(): Unit = {
    shutdown(system)
    super.afterAll()
  }
}
