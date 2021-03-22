// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.uploads.consumer

import com.blackfynn.db._
import com.blackfynn.managers._
import com.blackfynn.models.{
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
import com.blackfynn.traits.PostgresProfile.api._
import java.util.UUID

import org.scalatest.{ Matchers, WordSpec }

trait UploadsConsumerDatabaseSpecHarness
    extends WordSpec
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
