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

package com.pennsieve.managers

import com.pennsieve.audit.middleware.TraceId
import net.ceedubs.ficus.Ficus._
import com.pennsieve.aws.sns.{ LocalSNSContainer, MockSNS, SNS, SNSClient }
import com.pennsieve.models.{
  CognitoId,
  Contributor,
  DBPermission,
  Dataset,
  DatasetState,
  DatasetType,
  Degree,
  File,
  FileObjectType,
  FileProcessingState,
  FileState,
  FileType,
  NodeCodes,
  Organization,
  Package,
  PackageState,
  PackageType,
  Role,
  Team,
  User,
  Webhook
}
import com.pennsieve.core.utilities.PostgresDatabase
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.db._
import com.pennsieve.dtos.WebhookTargetDTO
import com.pennsieve.models.FileObjectType.Source
import com.pennsieve.test._
import com.pennsieve.test.helpers.{ TestDatabase, TestableOrganizationManager }
import org.scalatest._
import org.scalatest.EitherValues._
import org.scalatest.flatspec.AnyFlatSpec

import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random

trait ManagerSpec
    extends TestDatabase
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with PersistantTestContainers
    with PostgresDockerContainer { self: TestSuite =>

  var userManager: UserManager = _
  var userInviteManager: UserInviteManager = _
  var tokenManager: TokenManager = _

  // We implicitly assume that once the organization is created for a
  // test, that's its id is 1, since we create the organization per-test (after
  // clearing the graph and database) but only create its database schema once
  // per test suite (for speed and efficiency reasons).
  val testOrganizationId: Int = 1
  var testOrganization: Organization = _

  // Same for Org 2
  val testOrganizationId2: Int = 2
  var testOrganization2: Organization = _

  var testDataset: Dataset = _

  var extraOrganizationIds: Seq[Int] = Nil

  var superAdmin: User = _
  var integrationUser: User = _
  var database: Database = _
  var postgresDB: PostgresDatabase = _

  override def beforeEach(): Unit = {
    database.run(clearDB).await
    superAdmin = createSuperAdmin()

    // we create the schema for this Organization once (on container start-up below)
    testOrganization = createOrganization(createSchema = false)
    testOrganization2 = createOrganization(createSchema = false)

    testDataset =
      createDataset(testOrganization, name = "super admin's test dataset")

    integrationUser = createUser(
      email = "",
      permission = DBPermission.Administer,
      isIntegrationUser = true,
      firstName = "Integration",
      lastName = "User"
    )

    super.beforeEach()
  }

  override def afterEach(): Unit = {
    database.run(clearOrganizationSchema(testOrganizationId)).await
    extraOrganizationIds.foreach { organizationId =>
      database.run(dropOrganizationSchema(organizationId.toString)).await
    }
    extraOrganizationIds = Nil
    super.afterEach()
  }

  override def afterStart(): Unit = {
    super.afterStart()

    postgresDB = postgresContainer.database

    database = postgresDB.forURL

    // Migrate core schema
    migrateCoreSchema(postgresDB)
    // Migrate the schema for our universal Test Organizations (1 & 2)
    migrateOrganizationSchema(testOrganizationId, postgresDB)
    migrateOrganizationSchema(testOrganizationId2, postgresDB)

    userManager = new UserManager(database)
    userInviteManager = new UserInviteManager(database)
    tokenManager = new TokenManager(database)
  }

  override def afterAll(): Unit = {
    database.close()
    super.afterAll()
  }

  def generateRandomString(size: Int = 10): String =
    Random.alphanumeric.filter(_.isLetter).take(size).mkString

  def organizationManager(user: User = superAdmin): SecureOrganizationManager =
    new TestableOrganizationManager(false, database, user)

  def secureTokenManager(user: User = superAdmin): SecureTokenManager =
    new SecureTokenManager(user, database)

  def changelogManager(
    organization: Organization = testOrganization,
    user: User = superAdmin
  ): ChangelogManager = {
    val sns: SNSClient = new MockSNS
    new ChangelogManager(database, organization, user, "test-topic", sns = sns)
  }

  def datasetManager(
    organization: Organization = testOrganization,
    user: User = superAdmin
  ): DatasetManager = {
    val datasetsMapper = new DatasetsMapper(organization)

    new DatasetManager(database, user, datasetsMapper)
  }

  def datasetCollectionManager(
    organization: Organization = testOrganization
  ): CollectionManager = {
    val collectionMapper = new CollectionMapper(organization)

    new CollectionManager(database, collectionMapper)
  }

  def datasetPublicationStatusManager(
    organization: Organization = testOrganization,
    user: User = superAdmin
  ): DatasetPublicationStatusManager = {
    val datasetPublicationStatusMapper = new DatasetPublicationStatusMapper(
      organization
    )

    new DatasetPublicationStatusManager(
      database,
      user,
      datasetPublicationStatusMapper,
      changelogManager(organization, user).changelogEventMapper
    )
  }

  def datasetStatusManager(
    organization: Organization = testOrganization
  ): DatasetStatusManager = new DatasetStatusManager(database, organization)

  def contributorsManager(
    organization: Organization = testOrganization,
    user: User = superAdmin
  ): ContributorManager = {
    val contributorsMapper = new ContributorMapper(organization)
    new ContributorManager(
      database,
      user,
      contributorsMapper,
      new UserManager(database)
    )
  }

  def fileManager(
    organization: Organization = testOrganization,
    user: User = superAdmin
  ): FileManager =
    new FileManager(packageManager(organization, user), organization)

  def externalFileManager(
    organization: Organization = testOrganization,
    user: User = superAdmin
  ): ExternalFileManager = {
    val externalFileMapper = new ExternalFilesMapper(organization)
    val pkgManager = packageManager(organization, user)
    new ExternalFileManager(externalFileMapper, pkgManager)
  }

  def storageManager(
    organization: Organization = testOrganization
  ): StorageManager =
    new StorageManager(database, organization)

  def packageManager(
    organization: Organization = testOrganization,
    user: User = superAdmin
  ): PackageManager =
    new PackageManager(datasetManager(organization, user))

  def timeSeriesManager(
    organization: Organization = testOrganization
  ): TimeSeriesManager =
    new TimeSeriesManager(database, organization)

  def dimensionManager(
    organization: Organization = testOrganization
  ): DimensionManager =
    new DimensionManager(database, organization)

  def annotationManager(organization: Organization): AnnotationManager =
    new AnnotationManager(organization, database)

  def teamManager(user: User = superAdmin): TeamManager =
    TeamManager(organizationManager(user))

  lazy val timeSeriesLayerTableQuery = new TableQuery(
    new TimeSeriesLayerTable(_)
  )
  lazy val timeSeriesAnnotationTableQuery = new TableQuery(
    new TimeSeriesAnnotationTable(_)
  )

  def timeSeriesLayerManager(): TimeSeriesLayerManager = {
    new TimeSeriesLayerManager(
      database,
      timeSeriesLayerTableQuery,
      timeSeriesAnnotationTableQuery
    )
  }

  def timeSeriesAnnotationManager(): TimeSeriesAnnotationManager =
    new TimeSeriesAnnotationManager(db = database)

  def webhookManager(
    organization: Organization = testOrganization,
    user: User = superAdmin
  ): WebhookManager = {

    val webhooksMapper: WebhooksMapper =
      new WebhooksMapper(organization)
    val webhookEventSubscriptionsMapper: WebhookEventSubscriptionsMapper =
      new WebhookEventSubscriptionsMapper(organization)
    val webhookEventTypesMapper: WebhookEventTypesMapper =
      new WebhookEventTypesMapper(organization)
    val datasetIntegrationsMapper: DatasetIntegrationsMapper =
      new DatasetIntegrationsMapper(organization)

    new WebhookManager(
      db = database,
      actor = user,
      webhooksMapper = webhooksMapper,
      webhookEventSubscriptionsMapper = webhookEventSubscriptionsMapper,
      webhookEventTypesMapper = webhookEventTypesMapper
    )

  }

  val provenance = "from unit test"

  def createSuperAdmin(email: String = "superadmin@pennsieve.org"): User =
    createUser(
      email = email,
      isSuperAdmin = true,
      organization = None,
      datasets = Nil,
      firstName = "SUPER",
      lastName = "ADMIN"
    )

  def createUser(
    email: String = "test+" + generateRandomString() + "@pennsieve.org",
    isSuperAdmin: Boolean = false,
    organization: Option[Organization] = Some(testOrganization),
    datasets: List[Dataset] = List(testDataset),
    permission: DBPermission = DBPermission.Delete,
    isIntegrationUser: Boolean = false,
    firstName: String = "Test",
    lastName: String = "User"
  ): User = {
    val unsavedUser = User(
      nodeId = NodeCodes.generateId(NodeCodes.userCode),
      email = email,
      firstName = firstName,
      middleInitial = None,
      lastName = lastName,
      degree = None,
      credential = "",
      color = "",
      url = "",
      isSuperAdmin = isSuperAdmin,
      isIntegrationUser = isIntegrationUser,
      cognitoId = Some(CognitoId.UserPoolId.randomId())
    )

    val user = userManager.create(unsavedUser).await.value

    // necessary because the in-memory manager is by default insecure
    // TODO: delete!!
    //authorizationManager.setPermission(user.nodeId, Administer, user.nodeId)

    if (organization.isDefined) {
      organizationManager(superAdmin)
        .addUser(organization.get, user, permission)
        .await
        .value

      datasets.foreach { dataset =>
        datasetManager(organization.get, superAdmin)
          .addUserCollaborator(dataset, user, Role.Editor)
          .await
          .value
      }
    }

    user
  }

  def createOrganization(createSchema: Boolean = true): Organization = {
    val om = organizationManager(superAdmin)

    val organization = om
      .create(name = "Test Organization", slug = generateRandomString())
      .await
      .value

    om.addUser(organization, superAdmin, DBPermission.Owner).await.value

    if (createSchema) {
      migrateOrganizationSchema(organization.id, postgresDB)
      extraOrganizationIds = extraOrganizationIds :+ organization.id
    }

    organization
  }

  def createPackage(
    organization: Organization = testOrganization,
    user: User = superAdmin,
    dataset: Dataset = testDataset,
    name: String = generateRandomString(),
    state: PackageState = PackageState.READY,
    `type`: PackageType = PackageType.Collection,
    parent: Option[Package] = None,
    externalLocation: Option[String] = None,
    description: Option[String] = None,
    importId: Option[UUID] = None
  ): Package = {
    val pm = packageManager(organization, user)

    pm.create(
        name = name,
        `type` = `type`,
        state = state,
        dataset = dataset,
        ownerId = Some(user.id),
        parent = parent,
        externalLocation = externalLocation,
        description = description,
        importId = importId
      )
      .await
      .value
  }

  def deletePackage(
    organization: Organization = testOrganization,
    user: User = superAdmin,
    pkg: Package
  ): Unit = {
    val pm = packageManager(organization, user)
    val sm = storageManager(organization)
    val traceId = TraceId("n/a")
    val _ = pm.delete(traceId, pkg)(sm).await.value
  }

  def createDataset(
    organization: Organization = testOrganization,
    user: User = superAdmin,
    name: String = generateRandomString(),
    state: DatasetState = DatasetState.READY,
    description: Option[String] = None,
    tags: List[String] = List.empty,
    `type`: DatasetType = DatasetType.Research
  ): Dataset = {
    val status = datasetStatusManager(organization)
      .create(s"status for $name".take(20))
      .await
      .value

    val dm = datasetManager(organization, user)

    dm.create(
        name = name,
        description = description,
        state = state,
        statusId = Some(status.id),
        tags = tags,
        `type` = `type`
      )
      .await
      .value
  }

  def createFile(
    container: Package,
    organization: Organization = testOrganization,
    user: User = superAdmin,
    name: String = generateRandomString(),
    s3Bucket: String = "bucket/" + generateRandomString(),
    s3Key: String = "key/" + generateRandomString(),
    fileType: FileType = FileType.GenericData,
    objectType: FileObjectType = Source,
    processingState: FileProcessingState = FileProcessingState.Unprocessed,
    size: Long = 0,
    uploadedState: Option[FileState] = None
  ): File = {
    val fm = fileManager(organization, user)
    fm.create(
        name,
        fileType,
        container,
        s3Bucket,
        s3Key,
        objectType,
        processingState,
        size,
        uploadedState = uploadedState
      )
      .await match {
      case Right(x) => x
      case Left(e) => throw e
    }
  }

  def createTeam(
    name: String,
    organization: Organization,
    actor: User = superAdmin
  ): Team = {
    teamManager(actor)
      .create(name = name, organization = organization)
      .await
      .value
  }

  def createContributor(
    firstName: String,
    lastName: String,
    email: String,
    middleInitial: Option[String],
    degree: Option[Degree],
    orcid: Option[String] = None,
    userId: Option[Int] = None,
    organization: Organization = testOrganization,
    creatingUser: User = superAdmin
  ): (Contributor, Option[User]) = {
    contributorsManager(organization, creatingUser)
      .create(firstName, lastName, email, middleInitial, degree, orcid, userId)
      .await
      .value
  }

  def createWebhook(
    apiUrl: String = "https://www.api.com",
    imageUrl: Option[String] = Some("https://www.image.com"),
    description: String = "test webhook",
    secret: String = "secretkey123",
    displayName: String = "Test Webhook",
    isPrivate: Boolean = false,
    isDefault: Boolean = false,
    hasAccess: Boolean = false,
    targetEvents: Option[List[String]] = Some(List("METADATA", "FILES")),
    customTargets: Option[List[WebhookTargetDTO]] = null,
    organization: Organization = testOrganization,
    creatingUser: User = superAdmin
  ): (Webhook, Seq[String]) = {

    val integrationUser =
      createUser(email = "", permission = DBPermission.Administer)

    webhookManager(organization, creatingUser)
      .create(
        apiUrl,
        imageUrl,
        description,
        secret,
        displayName,
        isPrivate,
        isDefault,
        hasAccess,
        targetEvents,
        customTargets,
        integrationUser
      )
      .await
      .value
  }
}

class BaseManagerSpec extends AnyFlatSpec with ManagerSpec
