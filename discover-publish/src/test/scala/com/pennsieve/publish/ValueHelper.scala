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

package com.pennsieve.publish
import com.pennsieve.aws.s3.S3
import com.pennsieve.managers.DatasetStatusManager
import com.pennsieve.models.{
  Dataset,
  DatasetAsset,
  DatasetState,
  DatasetStatus,
  Doi,
  NodeCodes,
  Organization,
  PublishedCollection,
  PublishedContributor,
  PublishedExternalPublication,
  RelationshipType,
  User
}
import com.pennsieve.test.helpers.AwaitableImplicits.toAwaitable
import com.pennsieve.traits.PostgresProfile.api._
import org.scalatest.Assertion
import org.scalatest.EitherValues._
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext
import scala.util.Random

trait ValueHelper extends Matchers {

  val sampleOrganization: Organization =
    Organization("N:organization:32352", "Test org", "test-org", id = 5)
  val contributor: PublishedContributor =
    PublishedContributor(
      first_name = "John",
      middle_initial = Some("G"),
      last_name = "Malkovich",
      degree = None,
      orcid = Some("0000-0003-8769-1234")
    )
  val collection: PublishedCollection = PublishedCollection(
    "My awesome collection"
  )
  val externalPublication: PublishedExternalPublication =
    PublishedExternalPublication(
      Doi("10.26275/t6j6-77pu"),
      Some(RelationshipType.References)
    )
  def generateRandomString(size: Int = 10): String =
    Random.alphanumeric.filter(_.isLetter).take(size).mkString

  def newUser(
    email: String = s"test+${generateRandomString()}@pennsieve.org",
    isSuperAdmin: Boolean = false
  ): User =
    User(
      nodeId = NodeCodes.generateId(NodeCodes.userCode),
      email = email,
      firstName = "Test",
      middleInitial = None,
      lastName = "User",
      degree = None,
      isSuperAdmin = isSuperAdmin
    )

  def newDataset(
    name: String = generateRandomString(),
    statusId: Int = 1,
    description: Option[String] = Some("description")
  ): Dataset = {

    Dataset(
      NodeCodes.generateId(NodeCodes.dataSetCode),
      name,
      DatasetState.READY,
      description = description,
      statusId = statusId
    )
  }

  def createUser(
    databaseContainer: InsecureDatabaseContainer,
    email: String = s"test+${generateRandomString()}@pennsieve.org",
    isSuperAdmin: Boolean = false
  )(implicit
    executionContext: ExecutionContext
  ): User =
    databaseContainer.userManager
      .create(newUser(email = email, isSuperAdmin = isSuperAdmin))
      .await
      .value

  def createDatasetStatus(
    databaseContainer: InsecureDatabaseContainer,
    displayName: String = generateRandomString()
  )(implicit
    executionContext: ExecutionContext
  ): DatasetStatus =
    new DatasetStatusManager(
      databaseContainer.db,
      databaseContainer.organization
    ).create(displayName).await.value

  def createDataset(
    databaseContainer: InsecureDatabaseContainer,
    name: String = generateRandomString(),
    description: Option[String] = Some("description")
  )(implicit
    executionContext: ExecutionContext
  ): Dataset = {
    val datasetsMapper = databaseContainer.datasetsMapper

    val status = createDatasetStatus(databaseContainer)
    databaseContainer.db
      .run(
        (datasetsMapper returning datasetsMapper) += newDataset(
          name = name,
          statusId = status.id,
          description
        )
      )
      .await
  }

  // Generate random content
  def createS3File(
    s3: S3,
    s3Bucket: String,
    s3Key: String,
    content: String = generateRandomString()
  ): Assertion =
    s3.putObject(s3Bucket, s3Key, content).isRight shouldBe true

  def createAsset(
    databaseContainer: InsecureDatabaseContainer,
    dataset: Dataset,
    name: String,
    assetBucket: String,
    content: String = generateRandomString(),
    s3: Option[S3] = None
  )(implicit
    executionContext: ExecutionContext
  ): DatasetAsset = {
    val asset: DatasetAsset = databaseContainer.db
      .run(
        databaseContainer.datasetAssetsManager
          .createQuery(name, dataset, assetBucket)
      )
      .await

    s3.foreach(createS3File(_, asset.s3Bucket, asset.s3Key, content))
    asset
  }

  def addBannerAndReadme(
    databaseContainer: InsecureDatabaseContainer,
    dataset: Dataset,
    assetBucket: String,
    s3: Option[S3] = None
  )(implicit
    executionContext: ExecutionContext
  ): Dataset = {
    val banner =
      createAsset(
        databaseContainer = databaseContainer,
        dataset = dataset,
        name = "some-image-with-any-name.jpg",
        assetBucket = assetBucket,
        content = "banner-data",
        s3 = s3
      )
    val readme =
      createAsset(
        databaseContainer,
        dataset,
        name = Publish.README_FILENAME,
        assetBucket = assetBucket,
        content = "readme-data",
        s3 = s3
      )
    val changelog =
      createAsset(
        databaseContainer,
        dataset,
        name = Publish.CHANGELOG_FILENAME,
        assetBucket = assetBucket,
        content = "changelog-data",
        s3 = s3
      )

    val updatedDataset =
      dataset.copy(
        bannerId = Some(banner.id),
        readmeId = Some(readme.id),
        changelogId = Some(changelog.id)
      )

    databaseContainer.db
      .run(
        databaseContainer.datasetsMapper
          .filter(_.id === dataset.id)
          .update(updatedDataset)
      )
      .await

    updatedDataset
  }

  def createDatasetWithAssets(
    databaseContainer: InsecureDatabaseContainer,
    name: String = generateRandomString(),
    bucket: String,
    description: Option[String] = Some("description"),
    s3: Option[S3] = None
  )(implicit
    executionContext: ExecutionContext
  ): Dataset = {
    val d = createDataset(databaseContainer, name, description)
    addBannerAndReadme(databaseContainer, d, bucket, s3)
  }

}
