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
import com.pennsieve.core.utilities.ContainerTypes.SnsTopic
import com.pennsieve.aws.email.Email
import com.pennsieve.aws.sns.SNSContainer
import com.pennsieve.db.{
  AllDataCanvasesViewMapper,
  CollectionMapper,
  ContributorMapper,
  DataCanvasMapper,
  DatasetIntegrationsMapper,
  DatasetPublicationStatusMapper,
  DatasetTeamMapper,
  DatasetUserMapper,
  DatasetsMapper,
  ExternalFilesMapper,
  PackagesMapper,
  WebhookEventSubscriptionsMapper,
  WebhookEventTypesMapper,
  WebhooksMapper
}
import com.pennsieve.domain.{ CoreError, DatasetRolePermissionError, NotFound }
import com.pennsieve.managers._
import com.pennsieve.models.{
  DataCanvas,
  Dataset,
  Organization,
  Package,
  Role,
  User
}
import com.pennsieve.service.utilities.ContextLogger
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.utilities.Container
import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._
import org.apache.http.ssl.SSLContexts
import shapeless.Inl
import shapeless.syntax.inject._

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

trait DatabaseContainer {
  self: Container =>

  /** Factory hook used by `StorageManager.create`. Production builds a real
    * `StorageManager` over `db`; tests override to return an in-memory fake. */
  def newStorageManager(
    organization: com.pennsieve.models.Organization
  ): com.pennsieve.managers.StorageServiceClientTrait =
    new com.pennsieve.managers.StorageManager(db, organization)

  /** Factory hook for per-organization `DatasetStatusManager`. Tests override
    * to return an in-memory fake; production builds a real impl over `db`. */
  def newDatasetStatusManager(
    organization: com.pennsieve.models.Organization
  ): com.pennsieve.managers.DatasetStatusManager =
    new com.pennsieve.managers.DatasetStatusManagerImpl(db, organization)

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

trait DataDBContainer {
  self: Container =>
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

trait MessageTemplatesContainer {
  self: Container =>

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
  val user: User,
  val organization: Organization
)(implicit
  val ec: ExecutionContext,
  val system: ActorSystem
) extends Container
    with RequestContextContainer
    with DatabaseContainer {

  override lazy val db: Database = _db
}

trait CoreContainer extends UserManagerContainer {
  self: Container =>

  val userInviteManager: UserInviteManager
  val organizationManager: OrganizationManager
  val tokenManager: TokenManager
}

trait UserInviteManagerContainer {
  val userInviteManager: UserInviteManager
}

trait DefaultUserInviteManagerContainer
    extends UserInviteManagerContainer
    with DatabaseContainer {
  self: Container =>
  lazy val userInviteManager: UserInviteManager = new UserInviteManagerImpl(db)
}

trait InsecureCoreContainer
    extends CoreContainer
    with DatabaseContainer
    with DefaultUserManagerContainer
    with DefaultOrganizationManagerContainer
    with DefaultTermsOfServiceManagerContainer
    with DefaultTokenManagerContainer
    with DefaultUserInviteManagerContainer
    with ContextLoggingContainer {
  self: Container =>
}

trait SecureCoreContainer
    extends CoreContainer
    with DefaultUserManagerContainer
    with DefaultDatasetManagerContainer
    with DatasetPreviewManagerContainer
    with DefaultContributorManagerContainer
    with DefaultCollectionManagerContainer
    with DefaultStorageContainer
    with PackagesMapperContainer
    with DataDBContainer
    with TimeSeriesManagerContainer
    with FilesManagerContainer
    with MetadataManagerContainer
    with DefaultOrganizationManagerContainer
    with DefaultTermsOfServiceManagerContainer
    with ExternalFilesContainer
    with ExternalPublicationContainer
    with WebhookManagerContainer
    with DatasetAssetsContainer
    with DataCanvasManagerContainer
    with AllDataCanvasesViewManagerContainer
    with DefaultUserInviteManagerContainer {
  self: SecureContainer =>

  lazy val annotationManager: AnnotationManager =
    new AnnotationManagerImpl(self.organization, db)

  override lazy val organizationManager: SecureOrganizationManager =
    new SecureOrganizationManagerImpl(db, user)
  lazy val tokenManager: SecureTokenManager =
    new SecureTokenManagerImpl(user, db)
  lazy val teamManager: TeamManager = TeamManager(organizationManager)

  lazy val datasetStatusManager: DatasetStatusManager =
    new DatasetStatusManagerImpl(db, self.organization)

  lazy val dataUseAgreementManager: DataUseAgreementManager =
    new DataUseAgreementManagerImpl(db, self.organization)

  lazy val datasetPublicationStatusMapper: DatasetPublicationStatusMapper =
    new DatasetPublicationStatusMapper(self.organization)

  implicit val ec: ExecutionContext

  implicit val datasetUserMapper: DatasetUserMapper =
    new DatasetUserMapper(self.organization)
  implicit val datasetTeamMapper: DatasetTeamMapper =
    new DatasetTeamMapper(self.organization)

  // lazy val organizationUser: OrganizationUser = ???

  lazy val userRoles: Future[Map[Int, Option[Role]]] =
    db.run(datasetsMapper.maxRoles(user.id))

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

object ContainerTypes {
  type SnsTopic = String
}

trait StorageContainer {
  val storageManager: StorageServiceClientTrait
}

trait DefaultStorageContainer extends StorageContainer {
  self: Container with DatabaseContainer with OrganizationContainer =>

  lazy val storageManager: StorageServiceClientTrait =
    StorageManager.create(self, organization)
}

trait DatasetPublicationStatusContainer {
  self: Container with SecureCoreContainer with ChangelogContainer =>

  lazy val datasetPublicationStatusManager: DatasetPublicationStatusManager =
    new DatasetPublicationStatusManagerImpl(
      db,
      user,
      datasetPublicationStatusMapper,
      changelogManager.changelogEventMapper
    )
}

trait ChangelogContainer {
  self: Container with SecureCoreContainer with SNSContainer =>

  val events_topic: SnsTopic =
    config.as[String]("pennsieve.changelog.sns_topic")

  lazy val changelogManager: ChangelogManager =
    new ChangelogManagerImpl(db, organization, user, events_topic, sns)

}

trait RequestContextContainer extends OrganizationContainer {
  self: Container =>
  val user: User
}

trait OrganizationContainer {
  self: Container =>
  val organization: Organization
}

trait DatasetContainer {
  val dataset: Dataset
}

trait DatasetManagerContainer {
  val datasetManager: DatasetManager
}

trait DefaultDatasetManagerContainer
    extends DatasetManagerContainer
    with DatasetMapperContainer
    with DatabaseContainer
    with RequestContextContainer {
  self: Container =>

  lazy val datasetManager: DatasetManager =
    new DatasetManagerImpl(db, user, datasetsMapper)
}

trait DataCanvasContainer {
  val datacanvas: DataCanvas
}

trait DataCanvasManagerContainer
    extends DataCanvasMapperContainer
    with DatabaseContainer
    with RequestContextContainer {
  self: Container =>

  lazy val dataCanvasManager: DataCanvasManager =
    new DataCanvasManagerImpl(db, user, dataCanvasMapper)
}

trait AllDataCanvasesViewContainer {
  val datacanvas: (Int, DataCanvas)
}

trait AllDataCanvasesViewManagerContainer
    extends AllDataCanvasesViewMapperContainer
    with DatabaseContainer
    with RequestContextContainer {
  self: Container =>

  lazy val allDataCanvasesViewManager: AllDataCanvasesViewManager =
    new AllDataCanvasesViewManagerImpl(db, user, allDataCanvasesViewMapper)
}

trait DatasetPreviewManagerContainer
    extends DatasetMapperContainer
    with DatabaseContainer
    with RequestContextContainer {
  self: Container =>

  lazy val datasetPreviewManager: DatasetPreviewManager =
    new DatasetPreviewManagerImpl(db, datasetsMapper)
}

trait DatasetMapperContainer {
  self: OrganizationContainer =>

  lazy val datasetsMapper: DatasetsMapper = new DatasetsMapper(
    self.organization
  )
}

trait DataCanvasMapperContainer {
  self: OrganizationContainer =>

  lazy val dataCanvasMapper: DataCanvasMapper = new DataCanvasMapper(
    self.organization
  )
}

trait AllDataCanvasesViewMapperContainer {
  self: Container =>

  lazy val allDataCanvasesViewMapper: AllDataCanvasesViewMapper =
    new AllDataCanvasesViewMapper()
}

trait ContributorManagerContainer {
  val contributorsManager: ContributorManager
}

trait DefaultContributorManagerContainer
    extends ContributorManagerContainer
    with OrganizationContainer
    with UserManagerContainer
    with DatabaseContainer
    with RequestContextContainer {
  self: Container =>

  lazy val contributorsMapper: ContributorMapper = new ContributorMapper(
    self.organization
  )

  lazy val contributorsManager: ContributorManager =
    new ContributorManagerImpl(db, user, contributorsMapper, userManager)

}

trait CollectionManagerContainer {
  val collectionManager: CollectionManager
}

trait DefaultCollectionManagerContainer
    extends CollectionManagerContainer
    with OrganizationContainer
    with DatabaseContainer
    with RequestContextContainer {
  self: Container =>

  lazy val collectionMapper: CollectionMapper =
    new CollectionMapper(self.organization)

  lazy val collectionManager: CollectionManager =
    new CollectionManagerImpl(db, collectionMapper)

}

trait DatasetAssetsContainer {
  self: DatasetMapperContainer with DatabaseContainer =>
  lazy val datasetAssetsManager: DatasetAssetsManager =
    new DatasetAssetsManagerImpl(db, datasetsMapper)
}

trait ExternalFilesMapperContainer {
  self: OrganizationContainer =>
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
    new PackageManagerImpl(datasetManager)
}

trait OrganizationManagerContainer {
  val organizationManager: OrganizationManager
}

trait DefaultOrganizationManagerContainer extends OrganizationManagerContainer {
  self: DatabaseContainer =>
  lazy val organizationManager: OrganizationManager =
    new OrganizationManagerImpl(db)
}

trait TokenManagerContainer {
  val tokenManager: TokenManager
}

trait DefaultTokenManagerContainer extends TokenManagerContainer {
  self: DatabaseContainer =>
  lazy val tokenManager: TokenManager = new TokenManagerImpl(db)
}

trait TermsOfServiceManagerContainer {
  val pennsieveTermsOfServiceManager: PennsieveTermsOfServiceManager
  val customTermsOfServiceManager: CustomTermsOfServiceManager
}

trait DefaultTermsOfServiceManagerContainer
    extends TermsOfServiceManagerContainer {
  self: DatabaseContainer =>
  lazy val pennsieveTermsOfServiceManager: PennsieveTermsOfServiceManager =
    new PennsieveTermsOfServiceManagerImpl(db)
  lazy val customTermsOfServiceManager: CustomTermsOfServiceManager =
    new CustomTermsOfServiceManagerImpl(db)
}

trait UserManagerContainer {
  val userManager: UserManager
}

trait DefaultUserManagerContainer
    extends UserManagerContainer
    with DatabaseContainer {
  self: Container =>
  lazy val userManager: UserManager = new UserManagerImpl(db)
}

trait TimeSeriesManagerContainer
    extends OrganizationContainer
    with DatabaseContainer {
  self: Container =>
  lazy val timeSeriesManager: TimeSeriesManager =
    new TimeSeriesManager(db, organization)
}

trait MetadataManagerContainer
    extends OrganizationContainer
    with DatabaseContainer
    with RequestContextContainer {
  self: Container =>

  lazy val metadataManager: MetadataManager =
    new MetadataManager(db, user, self.organization)
}

trait FilesManagerContainer
    extends DatasetManagerContainer
    with PackageContainer
    with PackagesMapperContainer
    with DatabaseContainer
    with DataDBContainer
    with StorageContainer
    with OrganizationContainer {
  self: Container =>
  lazy val fileManager: FileManager =
    new FileManagerImpl(packageManager, organization)
}

trait ExternalFilesContainer extends ExternalFilesMapperContainer {
  self: OrganizationContainer =>
  val packageManager: PackageManager

  lazy val externalFileManager =
    new ExternalFileManager(externalFilesMapper, packageManager)
}

trait ExternalPublicationContainer
    extends OrganizationContainer
    with DatabaseContainer {
  self: Container =>

  lazy val externalPublicationManager: ExternalPublicationManager =
    new ExternalPublicationManagerImpl(db, organization)
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

trait WebhookManagerContainer
    extends OrganizationContainer
    with DatabaseContainer
    with RequestContextContainer {
  self: Container =>

  lazy val webhooksMapper: WebhooksMapper = new WebhooksMapper(
    self.organization
  )

  lazy val webhookEventSubscriptionsMapper: WebhookEventSubscriptionsMapper =
    new WebhookEventSubscriptionsMapper(self.organization)

  lazy val webhookEventTypesMapper: WebhookEventTypesMapper =
    new WebhookEventTypesMapper(self.organization)

  lazy val datasetIntegrationsMapper: DatasetIntegrationsMapper =
    new DatasetIntegrationsMapper(self.organization)

  lazy val webhookManager: WebhookManager =
    new WebhookManagerImpl(
      db,
      user,
      webhooksMapper,
      webhookEventSubscriptionsMapper,
      webhookEventTypesMapper
    )

}
