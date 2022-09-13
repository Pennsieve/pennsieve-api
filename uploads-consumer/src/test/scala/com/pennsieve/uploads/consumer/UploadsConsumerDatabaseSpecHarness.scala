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

package com.pennsieve.uploads.consumer

import com.pennsieve.db._
import com.pennsieve.managers._
import com.pennsieve.models.{
  DBPermission,
  Dataset,
  DatasetPublicationStatus,
  File,
  NodeCodes,
  OrganizationTeam,
  Package,
  PackageState,
  PackageType,
  PublicationStatus,
  PublicationType,
  Role,
  SystemTeamType,
  Team,
  TeamUser,
  User
}
import com.pennsieve.traits.PostgresProfile.api._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID

trait UploadsConsumerDatabaseSpecHarness
    extends AnyWordSpec
    with UploadsConsumerSpecHarness
    with Matchers {

  val files = new FilesMapper(organization)
  val packages = new PackagesMapper(organization)
  val datasets = new DatasetsMapper(organization)

  def createPublisherTeamAndAttachToDataset(user: User, dataset: Dataset) = {

    val teamNodeId = NodeCodes.generateId(NodeCodes.teamCode)
    val team = Team(teamNodeId, SystemTeamType.Publishers.entryName.capitalize)

    val teamId = consumerContainer.db
      .run((TeamsMapper returning TeamsMapper.map(_.id)) += team)
      .await

    consumerContainer.db
      .run(
        (OrganizationTeamMapper) += OrganizationTeam(
          organization.id,
          teamId,
          DBPermission.Administer,
          Some(SystemTeamType.Publishers)
        )
      )
      .await

    val teams = new OrganizationManager(consumerContainer.db)
      .getPublisherTeam(organization)
      .await
      .right
      .get

    val (publisherTeam, orgTeam) = teams

    val teamUserMapping =
      TeamUser(publisherTeam.id, user.id, DBPermission.Delete)

    consumerContainer.db
      .run(teamUser += teamUserMapping)
      .await

    new DatasetManager(consumerContainer.db, user, datasets)
      .addTeamCollaborator(dataset, publisherTeam, Role.Manager)
      .await
  }

  def createDataset: Dataset = {

    val status = new DatasetStatusManager(consumerContainer.db, organization)
      .create("Test")
      .await
      .right
      .get

    val dataset = Dataset(
      nodeId = NodeCodes.generateId(NodeCodes.dataSetCode),
      name = "Test Dataset",
      statusId = status.id
    )

    val id: Int = consumerContainer.db
      .run((datasets returning datasets.map(_.id)) += dataset)
      .await

    dataset.copy(id = id)
  }

  def createPackage(
    dataset: Dataset,
    state: PackageState = PackageState.UNAVAILABLE,
    `type`: PackageType = PackageType.TimeSeries,
    importId: UUID = UUID.randomUUID
  ): Package = {

    val p = Package(
      nodeId = NodeCodes.generateId(NodeCodes.packageCode),
      name = "Test Package",
      `type` = `type`,
      datasetId = dataset.id,
      ownerId = Some(user.id),
      state = state,
      importId = Some(importId)
    )

    val id: Int = consumerContainer.db
      .run((packages returning packages.map(_.id)) += p)
      .await

    p.copy(id = id)
  }

  def getPackage(id: Int): Package = {
    consumerContainer.db.run(packages.get(id).result.head).await
  }

  def getFiles(p: Package): Seq[File] = {
    consumerContainer.db.run(files.getByPackage(p).result).await
  }

  def sendPublishRequest(
    dataset: Dataset,
    user: User
  ): DatasetPublicationStatus = {

    val datasetPublicationStatusMapper = new DatasetPublicationStatusMapper(
      organization
    )

    val changelogEventMapper = new ChangelogEventMapper(organization)

    new DatasetPublicationStatusManager(
      consumerContainer.db,
      user,
      datasetPublicationStatusMapper,
      changelogEventMapper
    ).create(
        dataset,
        PublicationStatus.Requested,
        PublicationType.Publication,
        comments = Some("v1")
      )
      .await
      .right
      .get

  }
}
