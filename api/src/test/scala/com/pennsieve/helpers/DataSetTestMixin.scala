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

package com.pennsieve.helpers

import com.pennsieve.api.ApiSuite
import com.pennsieve.aws.cognito.MockCognito
import com.pennsieve.models.{
  Collection,
  DataUseAgreement,
  Dataset,
  DatasetAsset,
  DatasetIntegration,
  DatasetState,
  DatasetType,
  Degree,
  FileType,
  IntegrationTarget,
  License,
  NodeCodes,
  OrcidAuthorization,
  Organization,
  OrganizationUser,
  Package,
  PackageState,
  PackageType,
  Role,
  Team,
  User,
  Webhook,
  WebhookEventSubcription
}
import com.pennsieve.clients.DatasetAssetClient
import org.scalatest.EitherValues._

import scala.concurrent.ExecutionContext
import java.io.ByteArrayInputStream
import com.pennsieve.dtos._
import com.pennsieve.helpers.APIContainers.SecureAPIContainer
import com.pennsieve.models.DBPermission.{ Administer, Delete }

trait DataSetTestMixin {

  self: ApiSuite =>

  def createDataSet(
    name: String,
    description: Option[String] = Some("This is a dataset."),
    loggedInOrg: Organization = loggedInOrganization,
    status: Option[Int] = None,
    automaticallyProcessPackages: Boolean = false,
    license: Option[License] = Some(License.`Apache 2.0`),
    tags: List[String] = List("tag"),
    dataUseAgreement: Option[DataUseAgreement] = None,
    container: SecureAPIContainer = secureContainer,
    `type`: DatasetType = DatasetType.Research
  )(implicit
    ec: ExecutionContext
  ): Dataset =
    container.datasetManager
      .create(
        name,
        description,
        DatasetState.READY,
        automaticallyProcessPackages = automaticallyProcessPackages,
        statusId = status,
        license = license,
        tags = tags,
        dataUseAgreement = dataUseAgreement,
        `type` = `type`
      )
      .await match {
      case Left(error) => throw error
      case Right(value) => value
    }

  def updateDataset(
    dataset: Dataset,
    container: SecureAPIContainer = secureContainer
  )(implicit
    ec: ExecutionContext
  ): Dataset =
    container.datasetManager
      .update(dataset)
      .await match {
      case Left(error) => throw error
      case Right(value) => value
    }

  def createDataUseAgreement(
    name: String,
    body: String
  )(implicit
    ec: ExecutionContext
  ): DataUseAgreement = {
    secureContainer.dataUseAgreementManager.create(name, body).await.value
  }

  def createCollection(
    name: String
  )(implicit
    ec: ExecutionContext
  ): Collection =
    secureContainer.collectionManager
      .create(name)
      .await
      .value

  def addDatasetToCollection(
    dataset: Dataset,
    collection: Collection
  )(implicit
    ec: ExecutionContext
  ): Unit =
    secureContainer.datasetManager
      .addCollection(dataset, collection.id)
      .await
      .value

  def createPackage(
    dataset: Dataset,
    name: String,
    state: PackageState = PackageState.READY,
    `type`: PackageType = PackageType.Collection,
    ownerId: Option[Int] = None,
    parent: Option[Package] = None,
    description: Option[String] = None,
    externalLocation: Option[String] = None
  )(implicit
    ec: ExecutionContext
  ): Package =
    secureContainer.packageManager
      .create(
        name,
        `type`,
        state,
        dataset,
        ownerId,
        parent,
        description = description,
        externalLocation = externalLocation
      )
      .await
      .value

  def createTeam(
    name: String,
    loggedInUser: User = loggedInUser,
    loggedInOrg: Organization = loggedInOrganization,
    container: SecureAPIContainer = secureContainer
  )(implicit
    ec: ExecutionContext
  ): Team = {
    container.teamManager.create(name, loggedInOrg).await match {
      case Left(error) => throw error
      case Right(value) => value
    }
  }

  def createAsset(
    dataset: Dataset,
    name: String = "my-pic.jpg",
    bucket: String = "test-dataset-asset-bucket",
    container: SecureAPIContainer = secureContainer
  )(implicit
    ec: ExecutionContext
  ): DatasetAsset =
    container.db
      .run(
        container.datasetAssetsManager
          .createQuery(name, dataset, bucket)
      )
      .await

  def addBannerAndReadme(
    dataset: Dataset,
    container: SecureAPIContainer = secureContainer
  )(implicit
    ec: ExecutionContext,
    datasetAssetClient: DatasetAssetClient
  ) = {
    val banner =
      createAsset(dataset, name = "banner.jpg", container = container)
    val bannerData = "binary content"
    datasetAssetClient
      .uploadAsset(
        banner,
        bannerData.getBytes.length,
        None,
        new ByteArrayInputStream(bannerData.getBytes)
      )
      .value

    val readme = createAsset(dataset, name = "readme.md", container = container)
    val readmeData = "readme description"
    datasetAssetClient
      .uploadAsset(
        readme,
        readmeData.getBytes.length,
        None,
        new ByteArrayInputStream(readmeData.getBytes)
      )
      .value

    container.datasetManager
      .update(
        dataset.copy(bannerId = Some(banner.id), readmeId = Some(readme.id))
      )
      .await
      .value
  }

  def addChangelog(
    dataset: Dataset,
    changelogText: String,
    container: SecureAPIContainer = secureContainer
  )(implicit
    ec: ExecutionContext,
    datasetAssetClient: DatasetAssetClient
  ) = {
    val changelog =
      createAsset(dataset, name = "changelog.md", container = container)
    val changelogData = changelogText
    datasetAssetClient
      .uploadAsset(
        changelog,
        changelogData.getBytes.length,
        None,
        new ByteArrayInputStream(changelogData.getBytes)
      )
      .value

    container.datasetManager
      .update(dataset.copy(changelogId = Some(changelog.id)))
      .await
      .value
  }

  def createContributor(
    firstName: String,
    lastName: String,
    email: String,
    middleInitial: Option[String] = None,
    degree: Option[Degree] = None,
    orcid: Option[String] = None,
    userId: Option[Int] = None,
    dataset: Option[Dataset] = None
  )(implicit
    ec: ExecutionContext
  ): ContributorDTO = {
    val contributorAndUser = secureContainer.contributorsManager
      .create(firstName, lastName, email, middleInitial, degree, orcid, userId)
      .await
      .value

    dataset.foreach(
      secureContainer.datasetManager
        .addContributor(_, contributorAndUser._1.id)
        .await
        .value
    )

    ContributorDTO(contributorAndUser)
  }

  def createUser(
    firstName: String,
    lastName: String,
    email: String,
    nodeId: Option[String] = None,
    orcid: Option[String] = None,
    middleInitial: Option[String] = None,
    degree: Option[Degree] = None
  )(implicit
    ec: ExecutionContext
  ): OrganizationUser = {

    val orcidAuthorization = orcid.map(
      o =>
        OrcidAuthorization(
          "name",
          "accessToken",
          10000,
          "tokenType",
          o,
          "scope",
          "refreshToken"
        )
    )

    val newUser = User(
      nodeId.getOrElse(NodeCodes.generateId(NodeCodes.userCode)),
      email,
      firstName,
      middleInitial,
      lastName,
      degree,
      "cred",
      "",
      "http://blind.com",
      0,
      false,
      false,
      None,
      orcidAuthorization = orcidAuthorization
    )

    val user = secureContainer.userManager
      .create(newUser)
      .await
      .value

    organizationManager
      .addUser(secureContainer.organization, user, Delete)
      .await
      .value
  }

  def createWebhook(
    apiUrl: String = "https://www.api.com",
    imageUrl: Option[String] = Some("https://www.image.com"),
    description: String = "test webhook",
    secret: String = "secretkey123",
    displayName: String = "Test Webhook",
    targetEvents: Option[List[String]] = Some(List("METADATA", "FILES")),
    customTargets: Option[List[WebhookTargetDTO]] =
      Some(List(WebhookTargetDTO(IntegrationTarget.PACKAGE,
            Some(WebhookTargetFilterDTO(Some(WebhookPackageFilter(List(FileType.EDF)))))),
          WebhookTargetDTO(IntegrationTarget.PACKAGES,
            Some(WebhookTargetFilterDTO(Some(WebhookPackageFilter(List(FileType.Intan,
                      FileType.RData)))))))),
    isPrivate: Boolean = false,
    isDefault: Boolean = false,
    hasAccess: Boolean = false,
    container: SecureAPIContainer = secureContainer
  )(implicit
    ec: ExecutionContext
  ): (Webhook, Seq[String]) = {

    val mockCognito = new MockCognito()
    val integrationUserDefinition = User(
      NodeCodes.generateId(NodeCodes.userCode),
      "",
      "first",
      None,
      "last",
      None,
      "cred",
      "",
      "http://integration.com",
      0,
      false,
      true,
      None
    )
    val integrationUser =
      userManager.create(integrationUserDefinition).await.value

    organizationManager
      .addUser(loggedInOrganization, integrationUser, Administer)
      .await
      .value

    insecureContainer.tokenManager
      .create(
        name = "Integration-user",
        user = integrationUser,
        organization = secureContainer.organization,
        cognitoClient = mockCognito
      )
      .await
      .value

    container.webhookManager
      .create(
        apiUrl = apiUrl,
        imageUrl = imageUrl,
        description = description,
        secret = secret,
        displayName = displayName,
        isPrivate = isPrivate,
        isDefault = isDefault,
        hasAccess = hasAccess,
        targetEvents = targetEvents,
        customTargets = customTargets,
        integrationUser = integrationUser
      )
      .await match {
      case Left(error) => throw error
      case Right(value) => value
    }

  }

  def addUserCollaborator(
    dataset: Dataset,
    collaborator: User,
    role: Role,
    container: SecureAPIContainer = secureContainer
  )(implicit
    ec: ExecutionContext
  ): Option[Role] = {
    container.datasetManager
      .addUserCollaborator(dataset, collaborator, role)
      .await
      .value
      .oldRole
  }

  def enableWebhook(
    dataset: Dataset,
    webhook: Webhook,
    container: SecureAPIContainer = secureContainer
  )(implicit
    ec: ExecutionContext
  ): DatasetIntegration = {

    val integrationUser =
      container.userManager.get(webhook.integrationUserId).await.value

    container.datasetManager
      .enableWebhook(dataset, webhook, integrationUser)
      .await
      .value
  }
}
