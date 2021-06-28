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
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase
import org.apache.http.entity.ByteArrayEntity
import com.pennsieve.auth.middleware._
import com.pennsieve.aws.cognito.MockCognito
import com.pennsieve.aws.email.LocalEmailContainer
import com.pennsieve.aws.queue.LocalSQSContainer
import com.pennsieve.aws.s3.LocalS3Container
import com.pennsieve.models.{
  CognitoId,
  DBPermission,
  Dataset,
  DatasetStatus,
  Degree,
  Feature,
  NodeCodes,
  OrcidAuthorization,
  Organization,
  Package,
  PayloadType,
  Role,
  Token,
  User
}
import com.pennsieve.clients.{
  MockCustomTermsOfServiceClientContainer,
  MockJobSchedulingServiceContainer
}
import com.pennsieve.core.utilities._
import com.pennsieve.models.DBPermission.{ Administer, Delete }
import com.pennsieve.managers._
import com.pennsieve.managers.DatasetManager
import com.pennsieve.helpers._
import com.pennsieve.helpers.APIContainers.{
  InsecureAPIContainer,
  SecureAPIContainer,
  SecureContainerBuilderType
}
import com.pennsieve.utilities._
import com.pennsieve.web.SwaggerApp
import com.pennsieve.dtos.Secret
import com.pennsieve.models.PackageState.READY
import com.pennsieve.models.PackageType.Collection
import com.pennsieve.models.PublishStatus.{ PublishFailed, PublishSucceeded }
import com.pennsieve.test.{ LocalstackDockerContainer, _ }
import com.pennsieve.test.helpers._
import com.pennsieve.test.helpers.EitherValue._
import com.pennsieve.traits.TimeSeriesDBContainer
import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }
import enumeratum._
import io.circe.syntax._

import java.net.URI
import java.time.{ Duration, Instant, ZonedDateTime }
import java.util.UUID
import com.pennsieve.audit.middleware.AuditLogger
import com.pennsieve.auth.middleware.Jwt.Role.RoleIdentifier
import com.pennsieve.aws.sns.{ LocalSNSContainer, SNS, SNSContainer }
import com.typesafe.scalalogging.LazyLogging
import net.ceedubs.ficus.Ficus.{ stringValueReader, toFicusConfig }
import org.json4s.{ DefaultFormats, Formats, JValue }
import org.json4s.jackson.JsonMethods._
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach, FunSuite }
import org.scalatest.EitherValues._
import org.scalatra.test.scalatest._
import org.scalatra.test.HttpComponentsClientResponse

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{ FiniteDuration, _ }
import scala.util.Random
import scala.util.Either
import shapeless.syntax.inject._
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.services.sns.SnsAsyncClient

trait ApiSuite
    extends ScalatraSuite
    with TestKitBase
    with TestDatabase
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with PersistantTestContainers
    with PostgresDockerContainer
    with LocalstackDockerContainer
    with LazyLogging {

  // Needed for being able to use localstack with SSL enabled,
  // which is required for testing KMS encryption with S3
  System.setProperty("com.amazonaws.sdk.disableCertChecking", "true")

  implicit lazy val system: ActorSystem = ActorSystem("ApiSuite")
  implicit lazy val ec: ExecutionContext = system.dispatcher

  implicit lazy val jwtConfig: Jwt.Config = new Jwt.Config {
    override def key = "testkey"
  }

  implicit val swagger: SwaggerApp = new SwaggerApp

  protected implicit val jsonFormats
    : Formats = DefaultFormats ++ ModelSerializers.serializers

  override def afterAll(): Unit = {
    insecureContainer.db.close()
    insecureContainer.dataDB.close()
    shutdown(system)
    super.afterAll()
  }

  override def afterStart(): Unit = {
    super.afterStart()

    config = ConfigFactory
      .empty()
      .withFallback(postgresContainer.config)
      .withFallback(localstackContainer.config)
      .withValue("sqs.host", ConfigValueFactory.fromAnyRef(s"http://localhost"))
      .withValue(
        "sqs.queue",
        ConfigValueFactory.fromAnyRef(s"http://localhost/queue/test")
      )
//      .withValue("sns.host", ConfigValueFactory.fromAnyRef(s"http://localhost"))
      .withValue("sns.topic", ConfigValueFactory.fromAnyRef(s"events.sns"))
      .withValue(
        "sqs.notifications_queue",
        ConfigValueFactory.fromAnyRef(s"http://localhost/queue/notifications")
      )
      .withValue("sqs.region", ConfigValueFactory.fromAnyRef("us-east-1"))
      .withValue(
        "pennsieve.uploads.queue",
        ConfigValueFactory
          .fromAnyRef(s"http://localhost/queue/uploads")
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
        "pennsieve.api_host",
        ConfigValueFactory.fromAnyRef("http://localhost")
      )
      .withValue(
        "discover_app.host",
        ConfigValueFactory.fromAnyRef("discover.pennsieve.io")
      )
      .withValue("email.host", ConfigValueFactory.fromAnyRef("test"))
      .withValue(
        "email.support_email",
        ConfigValueFactory.fromAnyRef("pennsieve.main@gmail.com")
      )
      .withValue(
        "changelog.sns_topic",
        ConfigValueFactory.fromAnyRef("changelog-events")
      )

    logger.info("In BaseAPITest")
    logger.info(config.as[String]("sns.host"))

    insecureContainer = new InsecureContainer(config) with TestCoreContainer
    with LocalEmailContainer with MessageTemplatesContainer with DataDBContainer
    with TimeSeriesDBContainer with LocalSQSContainer with LocalSNSContainer
    with ApiSNSContainer with ApiSQSContainer
    with MockJobSchedulingServiceContainer {
      override lazy val jobSchedulingServiceConfigPath: String =
        "pennsieve.job_scheduling_service"
      override val postgresUseSSL = false
      override val dataPostgresUseSSL = false
    }

    secureContainerBuilder = { (user, org) =>
      new SecureContainer(
        config = config,
        _db = insecureContainer.db,
        user = user,
        organization = org
      ) with SecureCoreContainer with LocalEmailContainer with LocalSNSContainer
      with ChangelogContainer {
        override val postgresUseSSL = false
        override val dataPostgresUseSSL = false
      }
    }

    userManager = insecureContainer.userManager
    userInviteManager = insecureContainer.userInviteManager
    tokenManager = insecureContainer.tokenManager

    migrateCoreSchema(insecureContainer.postgresDatabase)

    // Interestingly this must be "5" as the sandbox organization in some
    // pass throughs gets created twice; once on the migration and once again
    // within the beforeEach of this loop. TODO on figuring out a better approach.
    1 to 5 foreach { orgId =>
      insecureContainer.db.run(createSchema(orgId.toString)).await
      migrateOrganizationSchema(orgId, insecureContainer.postgresDatabase)
    }
  }

  var config: Config = _
  var insecureContainer: InsecureAPIContainer = _
  var secureContainerBuilder: SecureContainerBuilderType = _

  var userManager: UserManager = _
  var userInviteManager: UserInviteManager = _
  var organizationManager: SecureOrganizationManager = _
  var teamManager: TeamManager = _
  var fileManager: FileManager = _
  var packageManager: PackageManager = _
  var externalFileManager: ExternalFileManager = _
  var tokenManager: TokenManager = _
  var timeSeriesManager: TimeSeriesManager = _
  var storageManager: StorageServiceClientTrait = _
  var annotationManager: AnnotationManager = _
  var discussionManager: DiscussionManager = _
  var dimensionManager: DimensionManager = _
  var onboardingManager: OnboardingManager = _

  var loggedInUser: User = _
  var colleagueUser: User = _
  var externalUser: User = _
  var superAdmin: User = _
  var sandboxUser: User = _

  var pennsieve: Organization = _
  var loggedInOrganization: Organization = _
  var loggedInOrganizationNoFeatures: Organization = _
  var externalOrganization: Organization = _
  var sandboxOrganization: Organization = _

  var loggedInJwt: String = _

  val requestTraceId: String = "1234-4567"

  var colleagueJwt: String = _

  var externalJwt: String = _

  var adminJwt: String = _

  var sandboxUserJwt: String = _

  var loggedInSandboxUserJwt: String = _

  var apiToken: Token = _
  var secret: Secret = _
  var apiJwt: String = _

  var dataset: Dataset = _

  var home: Package = _
  var personal: Package = _

  var secureContainer: SecureAPIContainer = _
  var secureDataSetManager: DatasetManager = _
  var sandboxUserContainer: SecureAPIContainer = _

  var defaultDatasetStatus: DatasetStatus = _

  val me = User(
    NodeCodes.generateId(NodeCodes.userCode),
    "test@test.com",
    "first",
    Some("M"),
    "last",
    Some(Degree.MS),
    "cred",
    "",
    "http://test.com",
    0,
    false,
    None
  )
  val colleague = User(
    NodeCodes.generateId(NodeCodes.userCode),
    "colleague@test.com",
    "first",
    None,
    "last",
    None,
    "cred",
    "",
    "http://test.com",
    0,
    false,
    None
  )
  val other = User(
    NodeCodes.generateId(NodeCodes.userCode),
    "test@external.com",
    "first",
    None,
    "last",
    None,
    "cred",
    "",
    "http://other.com",
    0,
    false,
    None
  )
  val superAdminUser = User(
    NodeCodes.generateId(NodeCodes.userCode),
    "super3@external.com",
    "first",
    None,
    "last",
    None,
    "cred",
    "",
    "http://other.com",
    0,
    true,
    None,
    id = 4
  )

  override def afterEach(): Unit = {
    super.afterEach()

    insecureContainer.db.run(clearDB).await
    insecureContainer.db.run(clearOrganizationSchema(1)).await
    insecureContainer.db.run(clearOrganizationSchema(2)).await
    insecureContainer.db.run(clearOrganizationSchema(3)).await
  }

  override def beforeEach(): Unit = {
    super.beforeEach()

    val mockCognito: MockCognito = new MockCognito()

    superAdmin = userManager.create(superAdminUser).await.value

    organizationManager =
      new TestableSecureOrganizationManager(superAdmin, insecureContainer.db)

    loggedInOrganization =
      createOrganization("Test Organization", features = Set())
    loggedInOrganizationNoFeatures = createOrganization("Test Organization")
    externalOrganization = createOrganization("External Organization")
    pennsieve = createOrganization("Pennsieve", "pennsieve")

    loggedInUser = userManager.create(me).await.value
    colleagueUser = userManager.create(colleague).await.value
    externalUser = userManager.create(other).await.value

    secureContainer = secureContainerBuilder(loggedInUser, loggedInOrganization)

    secureDataSetManager = secureContainer.datasetManager
    fileManager = secureContainer.fileManager
    packageManager = secureContainer.packageManager
    externalFileManager = secureContainer.externalFileManager
    annotationManager = secureContainer.annotationManager
    discussionManager = secureContainer.discussionManager
    dimensionManager = secureContainer.dimensionManager
    onboardingManager = secureContainer.onboardingManager
    timeSeriesManager = secureContainer.timeSeriesManager
    teamManager = secureContainer.teamManager
    storageManager = secureContainer.storageManager

    organizationManager
      .addUser(loggedInOrganization, superAdmin, Administer)
      .await
      .value
    organizationManager
      .addUser(loggedInOrganization, loggedInUser, Administer)
      .await
      .value
    organizationManager
      .addUser(loggedInOrganization, colleagueUser, Delete)
      .await
      .value
    organizationManager
      .addUser(externalOrganization, externalUser, Delete)
      .await
      .value

    loggedInJwt = Authenticator.createUserToken(
      loggedInUser,
      loggedInOrganization
    )(jwtConfig, insecureContainer.db, ec)

    colleagueJwt = Authenticator.createUserToken(
      colleagueUser,
      loggedInOrganization
    )(jwtConfig, insecureContainer.db, ec)

    externalJwt = Authenticator.createUserToken(
      externalUser,
      externalOrganization
    )(jwtConfig, insecureContainer.db, ec)

    adminJwt = Authenticator.createUserToken(superAdmin, loggedInOrganization)(
      jwtConfig,
      insecureContainer.db,
      ec
    )

    val (_apiToken, _secret) = tokenManager
      .create("test api token", loggedInUser, loggedInOrganization, mockCognito)
      .await
      .value
    apiToken = _apiToken
    secret = _secret

    apiJwt = Authenticator.createUserToken(
      loggedInUser,
      loggedInOrganization,
      cognito = CognitoSession
        .API(CognitoId.TokenPoolId.randomId, Instant.now().plusSeconds(10))
    )(jwtConfig, insecureContainer.db, ec)

    secureContainer.datasetStatusManager.resetDefaultStatusOptions.await.right.value

    defaultDatasetStatus = secureContainer.db
      .run(secureContainer.datasetStatusManager.getDefaultStatus)
      .await

    dataset = secureDataSetManager
      .create(
        name = "Home",
        description = Some("Home Dataset"),
        statusId = Some(defaultDatasetStatus.id)
      )
      .await
      .value

    assert(packageManager != null)

    home = packageManager
      .create("Home", Collection, READY, dataset, Some(loggedInUser.id), None)
      .await
      .right
      .value

    personal = packageManager
      .create(
        "Personal",
        Collection,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .right
      .value

    // This will check to see if an organization of sandbox already exists, and if so, uses that
    // otherwise generates an org w/ the feature flag
    sandboxOrganization = organizationManager
      .getBySlug("__sandbox__")
      .value
      .await match {
      case Right(org) => org
      case _ => {
        createOrganization(
          "__sandbox__",
          "__sandbox__",
          features = Set(Feature.SandboxOrgFeature)
        )
      }
    }

    val sandboxUserDefinition = User(
      NodeCodes.generateId(NodeCodes.userCode),
      "sandboxtest@test.com",
      "first",
      Some("M"),
      "last",
      Some(Degree.MS),
      "cred",
      "",
      "http://test.com",
      0,
      false,
      Some(sandboxOrganization.id)
    )

    sandboxUser = userManager.create(sandboxUserDefinition).await.value

    organizationManager
      .addUser(sandboxOrganization, sandboxUser, Administer)
      .await
      .value

    organizationManager
      .addUser(sandboxOrganization, loggedInUser, Administer)
      .await
      .value

    sandboxUserJwt = Authenticator.createUserToken(
      sandboxUser,
      sandboxOrganization
    )(jwtConfig, insecureContainer.db, ec)

    sandboxUserContainer =
      secureContainerBuilder(sandboxUser, sandboxOrganization)

    loggedInSandboxUserJwt = Authenticator.createUserToken(
      loggedInUser,
      sandboxOrganization
    )(jwtConfig, insecureContainer.db, ec)
  }

  def createOrganization(
    name: String,
    slug: String = Random.alphanumeric.take(10).mkString,
    features: Set[Feature] = Set(),
    subscriptionType: Option[String] = None
  ): Organization = {

    assert(organizationManager != null)

    val _organization = organizationManager
      .create(name, slug, features, subscriptionType)
      .await
    _organization.value
  }

  def organizationHeader(organizationNodeId: String): Map[String, String] = {
    Map(AuthenticatedController.organizationHeaderKey -> organizationNodeId)
  }

  def createJwtServiceToken(
    datasetId: Option[Int] = None,
    role: Role = Role.Owner,
    expiration: FiniteDuration = 10.seconds
  ): String = {
    val organizationRole = List(
      Jwt.OrganizationRole(
        id = Wildcard.inject[Jwt.Role.RoleIdentifier[OrganizationId]],
        role
      )
    )
    val datasetRole = datasetId match {
      case Some(id) =>
        List(
          Jwt.DatasetRole(DatasetId(id).inject[RoleIdentifier[DatasetId]], role)
        )
      case _ => List()
    }
    val serviceClaim = ServiceClaim(organizationRole ++ datasetRole)
    val claim = Jwt.generateClaim(serviceClaim, expiration)

    Jwt.generateToken(claim).value
  }

  def jwtUserAuthorizationHeader(
    organization: Organization,
    organizationRole: Role = Role.Manager,
    dataset: Dataset = dataset,
    datasetRole: Role = Role.Owner,
    expiration: FiniteDuration = 10.seconds,
    userId: Int = loggedInUser.id,
    cognito: Option[CognitoSession] = None
  ): Map[String, String] = {
    val jwtClaim = Jwt.generateClaim(
      UserClaim(
        id = UserId(userId),
        roles = List(
          Jwt.OrganizationRole(
            id = OrganizationId(organization.id)
              .inject[Jwt.Role.RoleIdentifier[OrganizationId]],
            role = organizationRole
          ),
          Jwt.DatasetRole(
            id = DatasetId(dataset.id)
              .inject[Jwt.Role.RoleIdentifier[DatasetId]],
            role = datasetRole
          )
        ),
        cognito = cognito
      ),
      expiration
    )

    val token: String = Jwt.generateToken(jwtClaim).value

    authorizationHeader(token)
  }

  def authorizationHeader(token: String): Map[String, String] =
    Map(
      AuthenticatedController.authorizationHeaderKey -> s"${AuthenticatedController.bearerScheme} $token"
    )

  def traceIdHeader(id: String = requestTraceId): Map[String, String] =
    Map(AuditLogger.TRACE_ID_HEADER -> id)

  def jwtServiceAuthorizationHeader(
    organization: Organization,
    dataset: Option[Dataset] = None
  ): Map[String, String] = {
    val token = createJwtServiceToken(datasetId = dataset.map(_.id))
    organizationHeader(organization.nodeId) +
      (AuthenticatedController.authorizationHeaderKey ->
        s"${AuthenticatedController.bearerScheme} ${token}")
  }

  def parsedBodyOpt: Option[JValue] = parseOpt(response.body)
  def parsedBody: JValue = parse(response.body)
  def parsedBodyContent: JValue = parsedBody \ "content"
  def compactRender(json: JValue): String = compact(render(json))
  def prettyRender(json: JValue): String = pretty(render(json))

  def contentTypeApplicationJsonHeader: (String, String) =
    ("Content-Type" -> "application/json")

  def postJson[A](
    uri: String,
    body: String,
    headers: Map[String, String] = Map()
  )(
    f: => A
  ): A = {
    val bodyBytes: Array[Byte] = body.getBytes("utf-8")
    post(uri, bodyBytes, headers = headers + contentTypeApplicationJsonHeader)(
      f
    )
  }

  def putJson[A](
    uri: String,
    body: String = "",
    headers: Map[String, String] = Map()
  )(
    f: => A
  ): A = {
    val bodyBytes: Array[Byte] = body.getBytes("utf-8")
    put(uri, bodyBytes, headers = headers + contentTypeApplicationJsonHeader)(f)
  }

  // had to do our own custom delete stuff because default old apache code doesn't
  // allow delete requests to have a body set
  class HttpDeleteEntity(uri: String) extends HttpEntityEnclosingRequestBase {
    setURI(URI.create(uri))

    override def getMethod: String = "DELETE"
  }

  // had to bypass all the nice scalatra stuff to get this to work but work it does!
  def deleteJson[A](
    uri: String,
    body: String,
    headers: Map[String, String] = Map()
  )(
    f: => A
  ): A = {
    val bodyBytes: Array[Byte] = body.getBytes("utf-8")
    val _headers = headers + contentTypeApplicationJsonHeader
    val client = createClient
    val url = "%s/%s".format(baseUrl, uri)

    val req = new HttpDeleteEntity(url)
    req.setEntity(new ByteArrayEntity(bodyBytes))
    _headers.foreach { case (name, v) => req.addHeader(name, v) }

    withResponse(HttpComponentsClientResponse(client.execute(req))) { f }
  }
}

trait BaseApiTest extends FunSuite with ApiSuite
