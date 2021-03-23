// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.helpers

import java.time.ZonedDateTime

import cats.data.EitherT
import com.pennsieve.api.ApiSuite
import com.pennsieve.models.{
  Collection,
  Contributor,
  DataUseAgreement,
  Dataset,
  DatasetAsset,
  DatasetState,
  DefaultDatasetStatus,
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
import io.circe.syntax._
import com.pennsieve.test.helpers.TestDatabase

import scala.concurrent.{ ExecutionContext, Future }
import com.pennsieve.test.helpers.EitherValue._
import cats.implicits._
import com.pennsieve.models.PublishStatus.PublishSucceeded
import java.io.ByteArrayInputStream
import java.util.UUID

import com.pennsieve.dtos._
import com.pennsieve.domain.CoreError
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
    dataUseAgreement: Option[DataUseAgreement] = None
  )(implicit
    ec: ExecutionContext
  ): Dataset =
    secureContainer.datasetManager
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
      .await
      .right
      .value

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
    bucket: String = "test-dataset-asset-bucket"
  )(implicit
    ec: ExecutionContext
  ): DatasetAsset =
    secureContainer.db
      .run(
        secureContainer.datasetAssetsManager
          .createQuery(name, dataset, bucket)
      )
      .await

  def addBannerAndReadme(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext,
    datasetAssetClient: DatasetAssetClient
  ) = {
    val banner = createAsset(dataset, name = "banner.jpg")
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

    val readme = createAsset(dataset, name = "readme.md")
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

    secureContainer.datasetManager
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
      "password",
      "cred",
      "",
      "http://blind.com",
      0,
      false,
      None,
      orcidAuthorization = orcidAuthorization
    )

    val user = secureContainer.userManager
      .create(newUser, Some("password"))
      .await
      .right
      .value

    organizationManager
      .addUser(secureContainer.organization, user, Delete)
      .await
      .value
  }
}
