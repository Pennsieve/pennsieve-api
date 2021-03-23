// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.api

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKitBase
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase
import org.apache.http.entity.ByteArrayEntity
import com.pennsieve.auth.middleware._
import com.pennsieve.aws.email.LocalEmailContainer
import com.pennsieve.aws.queue.LocalSQSContainer
import com.pennsieve.aws.s3.LocalS3Container
import com.pennsieve.models.{
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
import com.pennsieve.models.DBPermission.{ Administer, BlindReviewer, Delete }
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
import com.pennsieve.domain.Sessions
import com.pennsieve.dtos.Secret
import com.pennsieve.models.PackageState.READY
import com.pennsieve.models.PackageType.Collection
import com.pennsieve.models.PublishStatus.{ PublishFailed, PublishSucceeded }
import com.pennsieve.test._
import com.pennsieve.test.helpers._
import com.pennsieve.test.helpers.EitherValue._
import com.pennsieve.traits.TimeSeriesDBContainer
import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }
import enumeratum._
import io.circe.syntax._
import java.net.URI
import java.time.{ Duration, ZonedDateTime }
import java.util.UUID

import com.pennsieve.audit.middleware.AuditLogger
import com.pennsieve.auth.middleware.Jwt.Role.RoleIdentifier
import com.redis.RedisClientPool
import org.json4s.{ DefaultFormats, Formats, JValue }
import org.json4s.jackson.JsonMethods._
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach, FunSuite }
import org.scalatest.EitherValues._
import org.scalatra.test.scalatest._
import org.scalatra.test.HttpComponentsClientResponse

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{ FiniteDuration, _ }
import scala.util.Random
import shapeless.syntax.inject._

trait ApiSuite
    extends ScalatraSuite
    with TestKitBase
    with TestDatabase
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with PersistantTestContainers
    with RedisDockerContainer
    with PostgresDockerContainer {

  implicit lazy val system: ActorSystem = ActorSystem("ApiSuite")
  implicit lazy val materializer: ActorMaterializer = ActorMaterializer()
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
      .withFallback(redisContainer.config)
      .withFallback(postgresContainer.config)
      .withValue("sqs.host", ConfigValueFactory.fromAnyRef(s"http://localhost"))
      .withValue(
        "sqs.queue",
        ConfigValueFactory.fromAnyRef(s"http://localhost/queue/test")
      )
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
        "pennsieve.analytics_service.queue_size",
        ConfigValueFactory.fromAnyRef(100)
      )
      .withValue(
        "pennsieve.analytics_service.rate_limit",
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
        "email.trials_host",
        ConfigValueFactory.fromAnyRef("trials_test")
      )
      .withValue(
        "email.support_email",
        ConfigValueFactory.fromAnyRef("pennsieve.main@gmail.com")
      )

    insecureContainer = new InsecureContainer(config) with TestCoreContainer
    with LocalEmailContainer with MessageTemplatesContainer with DataDBContainer
    with TimeSeriesDBContainer with LocalSQSContainer with ApiSQSContainer
    with MockJobSchedulingServiceContainer {
      override lazy val jobSchedulingServiceConfigPath: String =
        "pennsieve.job_scheduling_service"
      override val postgresUseSSL = false
      override val dataPostgresUseSSL = false
    }

    secureContainerBuilder = { (user, org, roleOverrides) =>
      new SecureContainer(
        config = config,
        _db = insecureContainer.db,
        _redisClientPool = insecureContainer.redisClientPool,
        user = user,
        organization = org,
        roleOverrides = roleOverrides
      ) with SecureCoreContainer with LocalEmailContainer
      with RoleOverrideContainer {
        override val postgresUseSSL = false
        override val dataPostgresUseSSL = false
      }
    }

    userManager = insecureContainer.userManager
    userInviteManager = insecureContainer.userInviteManager
    sessionManager = insecureContainer.sessionManager
    tokenManager = insecureContainer.tokenManager

    migrateCoreSchema(insecureContainer.postgresDatabase)
    1 to 3 foreach { orgId =>
      insecureContainer.db.run(createSchema(orgId.toString)).await
      migrateOrganizationSchema(orgId, insecureContainer.postgresDatabase)
    }
  }

  var config: Config = _
  var insecureContainer: InsecureAPIContainer = _
  var secureContainerBuilder: SecureContainerBuilderType = _

  var userManager: UserManager = _
  var userInviteManager: UserInviteManager = _
  var sessionManager: SessionManager = _
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
  var loggedInBlindReviewer: User = _
  var colleagueUser: User = _
  var externalUser: User = _
  var superAdmin: User = _

  var pennsieve: Organization = _
  var loggedInOrganization: Organization = _
  var loggedInOrganizationNoFeatures: Organization = _
  var externalOrganization: Organization = _

  var loggedInJwt: String = _

  val requestTraceId: String = "1234-4567"

  var colleagueJwt: String = _

  var externalJwt: String = _

  var blindReviewerJwt: String = _

  var adminJwt: String = _

  var apiToken: Token = _
  var secret: Secret = _
  var apiJwt: String = _

  var dataset: Dataset = _

  var home: Package = _
  var personal: Package = _

  var secureContainer: SecureAPIContainer = _
  var secureDataSetManager: DatasetManager = _

  var defaultDatasetStatus: DatasetStatus = _

  val me = User(
    NodeCodes.generateId(NodeCodes.userCode),
    "test@test.com",
    "first",
    Some("M"),
    "last",
    Some(Degree.MS),
    "password",
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
    "password",
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
    "password",
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
    "password",
    "cred",
    "",
    "http://other.com",
    0,
    true,
    None,
    id = 4
  )
  val blindReviewerUser = User(
    NodeCodes.generateId(NodeCodes.userCode),
    "blind@test.com",
    "blind",
    None,
    "reviewer",
    None,
    "password",
    "cred",
    "",
    "http://blind.com",
    0,
    false,
    None
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

    superAdmin =
      userManager.create(superAdminUser, Some("password")).await.value

    organizationManager =
      new TestableSecureOrganizationManager(superAdmin, insecureContainer.db)

    loggedInOrganization =
      createOrganization("Test Organization", features = Set())
    loggedInOrganizationNoFeatures = createOrganization("Test Organization")
    externalOrganization = createOrganization("External Organization")
    pennsieve = createOrganization("Pennsieve", "pennsieve")

    loggedInUser = userManager.create(me, Some("password")).await.value
    colleagueUser = userManager.create(colleague, Some("password")).await.value

    externalUser = userManager.create(other, Some("password")).await.value
    loggedInBlindReviewer =
      userManager.create(blindReviewerUser, Some("password")).await.value

    secureContainer =
      secureContainerBuilder(loggedInUser, loggedInOrganization, List.empty)

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
    organizationManager
      .addUser(loggedInOrganization, loggedInBlindReviewer, BlindReviewer)
      .await
      .value

    loggedInJwt = Authenticator.createUserToken(
      loggedInUser,
      loggedInOrganization
    )(jwtConfig, insecureContainer.db, ec)

    blindReviewerJwt = Authenticator.createUserToken(
      loggedInBlindReviewer,
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
      .create("test api token", loggedInUser, loggedInOrganization)
      .await
      .value
    apiToken = _apiToken
    secret = _secret

    apiJwt = Authenticator.createUserToken(
      loggedInUser,
      loggedInOrganization,
      session = Session.API(UUID.randomUUID.toString)
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
    session: Option[Session] = None
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
        session = session
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
