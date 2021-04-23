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
import com.pennsieve.models.{
  Collection,
  DataUseAgreement,
  Dataset,
  DatasetAsset,
  DatasetState,
  Degree,
  License,
  NodeCodes,
  OrcidAuthorization,
  Organization,
  OrganizationUser,
  Package,
  PackageState,
  PackageType,
  Team,
  User
}
import com.pennsieve.clients.DatasetAssetClient
import org.scalatest.EitherValues._

import scala.concurrent.{ ExecutionContext }
import com.pennsieve.test.helpers.EitherValue._

import java.io.ByteArrayInputStream
import com.pennsieve.dtos._
import com.pennsieve.helpers.APIContainers.SecureAPIContainer
import com.pennsieve.models.DBPermission.Delete

trait DataSetTestMixin { self: ApiSuite =>

  def createDataSet(
    name: String,
    description: Option[String] = Some("This is a dataset."),
    loggedInOrg: Organization = loggedInOrganization,
    status: Option[Int] = None,
    automaticallyProcessPackages: Boolean = false,
    license: Option[License] = Some(License.`Apache 2.0`),
    tags: List[String] = List("tag"),
    dataUseAgreement: Option[DataUseAgreement] = None,
    container: SecureAPIContainer = secureContainer
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
        dataUseAgreement = dataUseAgreement
      )
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
    secureContainer.dataUseAgreementManager.create(name, body).await.right.value
  }

  def createCollection(
    name: String
  )(implicit
    ec: ExecutionContext
  ): Collection =
    secureContainer.collectionManager
      .create(name)
      .await
      .right
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
      .right
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
      .right
      .value

  def createTeam(
    name: String,
    loggedInUser: User = loggedInUser,
    loggedInOrg: Organization = loggedInOrganization
  )(implicit
    ec: ExecutionContext
  ): Team = {
    secureContainer.teamManager.create(name, loggedInOrg).await.right.value
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
      .right
      .get

    val readme = createAsset(dataset, name = "readme.md", container = container)
    val readmeData = "readme description"
    datasetAssetClient
      .uploadAsset(
        readme,
        readmeData.getBytes.length,
        None,
        new ByteArrayInputStream(readmeData.getBytes)
      )
      .right
      .get

    container.datasetManager
      .update(
        dataset.copy(bannerId = Some(banner.id), readmeId = Some(readme.id))
      )
      .await
      .right
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
      .right
      .value

    dataset.foreach(
      secureContainer.datasetManager
        .addContributor(_, contributorAndUser._1.id)
        .await
        .right
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
      None,
      orcidAuthorization = orcidAuthorization
    )

    val user = secureContainer.userManager
      .create(newUser)
      .await
      .right
      .value

    organizationManager
      .addUser(secureContainer.organization, user, Delete)
      .await
      .value
  }
}
