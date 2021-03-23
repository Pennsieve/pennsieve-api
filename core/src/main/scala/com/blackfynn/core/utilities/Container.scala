package com.pennsieve.core.utilities

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.implicits._
import com.pennsieve.auth.middleware.{
  DatasetId,
  DatasetNodeId,
  Jwt,
  OrganizationId,
  Permission
}
import com.pennsieve.aws.email.Email
import com.pennsieve.db.{
  CollectionMapper,
  ContributorMapper,
  DatasetPublicationStatusMapper,
  DatasetTeamMapper,
  DatasetUserMapper,
  DatasetsMapper,
  ExternalFilesMapper,
  PackagesMapper
}
import com.pennsieve.domain.{ CoreError, DatasetRolePermissionError, NotFound }
import com.pennsieve.managers._
import com.pennsieve.models.{ Dataset, Organization, Package, Role, User }
import com.pennsieve.service.utilities.ContextLogger
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.utilities.Container
import com.redis.RedisClientPool
import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._
import org.apache.http.ssl.SSLContexts
import shapeless.Inl
import shapeless.syntax.inject._

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

trait DatabaseContainer { self: Container =>
  val postgresHost: String = config.as[String]("postgres.host")
  val postgresPort: Int = config.as[Int]("postgres.port")
  val postgresDatabaseName: String = config.as[String]("postgres.database")
  val postgresUser: String = config.as[String]("postgres.user")
  val postgresPassword: String = config.as[String]("postgres.password")
  val environmentForSSL: Option[String] =
    config.as[Option[String]]("environment")
  val postgresUseSSL =
    if (environmentForSSL.getOrElse("").toLowerCase == "local") {
      false
    } else {
      true
    }

  lazy val postgresDatabase: PostgresDatabase = PostgresDatabase(
    host = postgresHost,
    port = postgresPort,
    database = postgresDatabaseName,
    user = postgresUser,
    password = postgresPassword,
    useSSL = postgresUseSSL
  )

  lazy val db: Database = postgresDatabase.forURL
}

trait DataDBContainer { self: Container =>
  lazy val dataPostgresHost: String = config.as[String]("data.postgres.host")
  lazy val dataPostgresPort: Int = config.as[Int]("data.postgres.port")
  lazy val dataPostgresDatabase: String =
    config.as[String]("data.postgres.database")
  lazy val dataPostgresUser: String = config.as[String]("data.postgres.user")
  lazy val dataPostgresPassword: String =
    config.as[String]("data.postgres.password")
  val dataPostgresUseSSL = true

  lazy val postgresDataDatabase: PostgresDatabase = PostgresDatabase(
    host = dataPostgresHost,
    port = dataPostgresPort,
    database = dataPostgresDatabase,
    user = dataPostgresUser,
    password = dataPostgresPassword,
    useSSL = dataPostgresUseSSL
  )

  lazy val dataDB: Database = postgresDataDatabase.forURL
}

trait MessageTemplatesContainer { self: Container =>

  lazy val host: String = config.as[String]("email.host")
  lazy val discoverHost: String = config.as[String]("discover_app.host")
  lazy val supportEmail: Email = Email(config.as[String]("email.support_email"))

  lazy val messageTemplates: MessageTemplates =
    new MessageTemplates(
      host = host,
      discoverHost = discoverHost,
      supportEmail = supportEmail
    )
}

class InsecureContainer(
  val config: Config
)(implicit
  val ec: ExecutionContext,
  val system: ActorSystem
) extends Container

class SecureContainer(
  val config: Config,
  _db: Database,
  _redisClientPool: RedisClientPool,
  val user: User,
  val organization: Organization,
  val roleOverrides: List[Jwt.Role] = List.empty
)(implicit
  val ec: ExecutionContext,
  val system: ActorSystem
) extends Container
    with RequestContextContainer
    with DatabaseContainer
    with RedisContainer {

  override lazy val db: Database = _db
  override lazy val redisClientPool: RedisClientPool = _redisClientPool

  /**
    * Generate a user-level JWT from the user, organization, and roles that were provided to construct this secure
    * container.
    *
    * By default, the token will expire after 2 minutes.
    *
    * @param config
    * @return
    */
  def generateUserToken()(implicit config: Jwt.Config): Jwt.Token =
    JwtAuthenticator.generateUserToken(2.minutes, user, roleOverrides)

  def generateUserToken(
    dataset: Dataset,
    role: Role
  )(implicit
    config: Jwt.Config
  ): Jwt.Token = {
    val datasetRole = Jwt.DatasetRole(
      id = DatasetId(dataset.id).inject[Jwt.Role.RoleIdentifier[DatasetId]],
      role = role,
      node_id = Some(DatasetNodeId(dataset.nodeId))
    )
    JwtAuthenticator.generateUserToken(
      2.minutes,
      user,
      datasetRole :: roleOverrides
    )
  }
}

object RedisContainer {

  /**
    * Create a new Redis client pool from configuration values
    * @param config
    * @return
    */
  def poolFromConfig(config: Config): RedisClientPool = {
    val redisHost: String = config.as[String]("redis.host")
    val redisPort: Int = config.getOrElse[Int]("redis.port", 6379)
    val redisUseSSL: Boolean = config.getOrElse[Boolean]("redis.use_ssl", false)
    val redisMaxConnections: Int =
      config.getOrElse[Int]("redis.max_connections", 128)

    // An empty string implies that there is no auth token. This is due
    // to a lack of support in terraform 0.11.11 for null values.
    val redisAuthToken: Option[String] =
      config.as[Option[String]]("redis.auth_token").filter(_.nonEmpty)

    val sslContext = if (redisUseSSL) SSLContexts.createDefault().some else None

    new RedisClientPool(
      redisHost,
      redisPort,
      secret = redisAuthToken,
      sslContext = sslContext,
      maxConnections = redisMaxConnections
    )
  }
}

trait RedisContainer { self: Container =>
  lazy val redisClientPool: RedisClientPool =
    RedisContainer.poolFromConfig(config)
}

trait CoreContainer extends SessionManagerContainer { self: Container =>

  val userInviteManager: UserInviteManager
  val organizationManager: OrganizationManager
  val tokenManager: TokenManager
}

trait InsecureCoreContainer
    extends CoreContainer
    with DatabaseContainer
    with OrganizationManagerContainer
    with TermsOfServiceManagerContainer
    with RedisManagerContainer
    with TokenManagerContainer
    with ContextLoggingContainer { self: Container =>

  lazy val userInviteManager: UserInviteManager = new UserInviteManager(db)
}

trait SecureCoreContainer
    extends CoreContainer
    with DatasetManagerContainer
    with DatasetPreviewManagerContainer
    with ContributorManagerContainer
    with CollectionManagerContainer
    with StorageContainer
    with PackagesMapperContainer
    with DataDBContainer
    with RedisContainer
    with TimeSeriesManagerContainer
    with FilesManagerContainer
    with OrganizationManagerContainer
    with TermsOfServiceManagerContainer
    with DimensionManagerContainer
    with ExternalFilesContainer
    with ExternalPublicationContainer
    with DatasetAssetsContainer { self: SecureContainer =>

  // A JWT can be used to temporarily elevate a user's role in order
  // to allow this user to perform tasks in a specific context (e.g. a
  // blind reviewer)
  val datasetRoleOverrides: Map[Int, Option[Role]]
  val organizationRoleOverrides: Map[Int, Option[Role]]

  lazy val annotationManager: AnnotationManager =
    new AnnotationManager(self.organization, db)

  lazy val changelogManager = new ChangelogManager(db, organization, user)
  lazy val discussionManager: DiscussionManager =
    new DiscussionManager(self.organization, db)
  lazy val onboardingManager = new OnboardingManager(db)
  override lazy val organizationManager: SecureOrganizationManager =
    new SecureOrganizationManager(db, user, organizationRoleOverrides)
  lazy val tokenManager: SecureTokenManager = new SecureTokenManager(user, db)
  lazy val teamManager: TeamManager = TeamManager(organizationManager)
  lazy val userInviteManager: UserInviteManager = new UserInviteManager(db)

  lazy val datasetStatusManager: DatasetStatusManager =
    new DatasetStatusManager(db, self.organization)

  lazy val dataUseAgreementManager: DataUseAgreementManager =
    new DataUseAgreementManager(db, self.organization)

  lazy val datasetPublicationStatusMapper: DatasetPublicationStatusMapper =
    new DatasetPublicationStatusMapper(self.organization)

  lazy val datasetPublicationStatusManager: DatasetPublicationStatusManager =
    new DatasetPublicationStatusManager(
      db,
      user,
      datasetPublicationStatusMapper,
      changelogManager.changelogEventMapper
    )

  implicit val ec: ExecutionContext

  implicit val datasetUserMapper: DatasetUserMapper =
    new DatasetUserMapper(self.organization)
  implicit val datasetTeamMapper: DatasetTeamMapper =
    new DatasetTeamMapper(self.organization)

  lazy val userRoles: Future[Map[Int, Option[Role]]] =
    db.run(datasetsMapper.maxRoles(user.id)).map(_ ++ datasetRoleOverrides)

  def authorizeDataset(
    permissions: Set[Permission]
  )(
    dataset: Dataset
  ): EitherT[Future, CoreError, Unit] =
    authorizeDatasetId(permissions)(dataset.id)

  def authorizeDatasetId(
    permissions: Set[Permission]
  )(
    datasetId: Int
  ): EitherT[Future, CoreError, Unit] =
    if (user.isSuperAdmin)
      EitherT.rightT[Future, CoreError](())
    else
      EitherT(
        userRoles.map(
          roleMap =>
            roleMap.get(datasetId) match {
              case Some(maybeRole) =>
                if (maybeRole
                    .map(Permission.hasPermissions(_)(permissions))
                    .getOrElse(false))
                  Right(())
                else Left(DatasetRolePermissionError(user.nodeId, datasetId))
              case None => Left(NotFound(s"Dataset (${datasetId})"))
            }
        )
      )

  def authorizePackage(
    permissions: Set[Permission]
  )(
    `package`: Package
  ): EitherT[Future, CoreError, Unit] =
    authorizeDatasetId(permissions)(`package`.datasetId)

  def authorizePackageId(
    permissions: Set[Permission]
  )(
    packageId: Int
  ): EitherT[Future, CoreError, Unit] =
    for {
      `package` <- packageManager.get(packageId)
      _ <- authorizePackage(permissions)(`package`)
    } yield ()
}

trait RoleOverrideContainer {
  val roleOverrides: List[Jwt.Role]

  val (
    datasetRoleOverrides: Map[Int, Option[Role]],
    organizationRoleOverrides: Map[Int, Option[Role]]
  ) = roleOverrides
    .foldLeft(Map.empty[Int, Option[Role]] -> Map.empty[Int, Option[Role]]) {
      case ((datasetMap, organizationMap), role) =>
        role match {
          case Jwt.DatasetRole(Inl(DatasetId(id)), role, _, _) =>
            (datasetMap + (id -> Some(role)), organizationMap)
          case Jwt.OrganizationRole(Inl(OrganizationId(id)), role, _, _, _) =>
            (datasetMap, organizationMap + (id -> Some(role)))
          case _ => datasetMap -> organizationMap
        }
    }
}

trait StorageContainer {
  self: Container with DatabaseContainer with OrganizationContainer =>

  lazy val storageManager = StorageManager.create(self, organization)
}

trait RequestContextContainer extends OrganizationContainer { self: Container =>
  val user: User
}

trait OrganizationContainer { self: Container =>
  val organization: Organization
}

trait DatasetContainer {
  val dataset: Dataset
}

trait DatasetManagerContainer
    extends DatasetMapperContainer
    with DatabaseContainer
    with RequestContextContainer { self: Container =>

  lazy val datasetManager: DatasetManager =
    new DatasetManager(db, user, datasetsMapper)
}

trait DatasetPreviewManagerContainer
    extends DatasetMapperContainer
    with DatabaseContainer
    with RequestContextContainer { self: Container =>

  lazy val datasetPreviewManager: DatasetPreviewManager =
    new DatasetPreviewManager(db, datasetsMapper)
}

trait DatasetMapperContainer { self: OrganizationContainer =>

  lazy val datasetsMapper: DatasetsMapper = new DatasetsMapper(
    self.organization
  )
}

trait ContributorManagerContainer
    extends OrganizationContainer
    with UserManagerContainer
    with DatabaseContainer
    with RequestContextContainer { self: Container =>

  lazy val contributorsMapper: ContributorMapper = new ContributorMapper(
    self.organization
  )

  lazy val contributorsManager: ContributorManager =
    new ContributorManager(db, user, contributorsMapper, userManager)

}

trait CollectionManagerContainer
    extends OrganizationContainer
    with DatabaseContainer
    with RequestContextContainer { self: Container =>

  lazy val collectionMapper: CollectionMapper =
    new CollectionMapper(self.organization)

  lazy val collectionManager: CollectionManager =
    new CollectionManager(db, collectionMapper)

}

trait DatasetAssetsContainer {
  self: DatasetMapperContainer with DatabaseContainer =>
  lazy val datasetAssetsManager: DatasetAssetsManager =
    new DatasetAssetsManager(db, datasetsMapper)
}

trait ExternalFilesMapperContainer { self: OrganizationContainer =>
  lazy val externalFilesMapper: ExternalFilesMapper =
    new ExternalFilesMapper(self.organization)
}

trait PackagesMapperContainer {
  self: DatabaseContainer with OrganizationContainer =>

  lazy val packagesMapper: PackagesMapper = new PackagesMapper(organization)
}

trait PackageContainer {
  self: DatabaseContainer
    with StorageContainer
    with DataDBContainer
    with DatasetManagerContainer =>

  lazy val packageManager: PackageManager =
    new PackageManager(datasetManager)
}

trait OrganizationManagerContainer { self: DatabaseContainer =>
  lazy val organizationManager: OrganizationManager = new OrganizationManager(
    db
  )
}

trait TokenManagerContainer { self: DatabaseContainer =>
  lazy val tokenManager: TokenManager = new TokenManager(db)
}

trait TermsOfServiceManagerContainer { self: DatabaseContainer =>
  lazy val pennsieveTermsOfServiceManager: PennsieveTermsOfServiceManager =
    new PennsieveTermsOfServiceManager(db)
  lazy val customTermsOfServiceManager: CustomTermsOfServiceManager =
    new CustomTermsOfServiceManager(db)
}

trait UserManagerContainer extends DatabaseContainer { self: Container =>
  lazy val userManager = new UserManager(db)
}

trait SessionManagerContainer
    extends UserManagerContainer
    with RedisManagerContainer {
  self: Container =>

  lazy val sessionManager: SessionManager =
    new SessionManager(redisManager, userManager)
}

trait RedisManagerContainer extends RedisContainer { self: Container =>
  lazy val redisManager: RedisManager = {
    val redisDatabase: Int = config.getOrElse[Int]("redis.database", 0)
    new RedisManager(redisClientPool, redisDatabase)
  }
}

trait TimeSeriesManagerContainer
    extends OrganizationContainer
    with DatabaseContainer { self: Container =>
  lazy val timeSeriesManager: TimeSeriesManager =
    new TimeSeriesManager(db, organization)
}

trait FilesManagerContainer
    extends DatasetManagerContainer
    with PackageContainer
    with PackagesMapperContainer
    with DatabaseContainer
    with DataDBContainer
    with StorageContainer
    with RedisContainer { self: Container =>
  lazy val fileManager: FileManager =
    new FileManager(packageManager, organization)
}

trait ExternalFilesContainer extends ExternalFilesMapperContainer {
  self: OrganizationContainer =>
  val packageManager: PackageManager

  lazy val externalFileManager =
    new ExternalFileManager(externalFilesMapper, packageManager)
}

trait ExternalPublicationContainer
    extends OrganizationContainer
    with DatabaseContainer { self: Container =>

  lazy val externalPublicationManager =
    new ExternalPublicationManager(db, organization)
}

trait DimensionManagerContainer
    extends DatabaseContainer
    with OrganizationContainer { self: Container =>

  lazy val dimensionManager: DimensionManager =
    new DimensionManager(db, organization)
}

trait UserPermissionContainer {
  val user: User
}

trait DatasetRoleContainer {
  val datasetRole: Option[Role]
}

trait ContextLoggingContainer {
  val log: ContextLogger = new ContextLogger()
}
