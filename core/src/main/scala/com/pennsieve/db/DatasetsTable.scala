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

package com.pennsieve.db

import com.pennsieve.models._
import com.pennsieve.traits.PostgresProfile.api._
import java.time.ZonedDateTime

import cats.Semigroup
import cats.implicits._
import com.rms.miu.slickcats.DBIOInstances._
import slick.lifted.Case._
import java.util.UUID

import com.pennsieve.domain.SqlError
import com.pennsieve.traits.PostgresProfile

import scala.concurrent.ExecutionContext

final class DatasetsTable(schema: String, tag: Tag)
    extends Table[Dataset](tag, Some(schema), "datasets") {

  // set by the database
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)

  def name = column[String]("name")
  def state = column[DatasetState]("state")
  def nodeId = column[String]("node_id")
  def description = column[Option[String]]("description")
  def `type` = column[DatasetType]("type")

  def permission = column[Option[DBPermission]]("permission_bit")
  def role = column[Option[Role]]("role")

  def automaticallyProcessPackages =
    column[Boolean]("automatically_process_packages")
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)

  def etag = column[ETag]("etag")

  def statusId = column[Int]("status_id")
  def publicationStatusId = column[Option[Int]]("publication_status_id")
  def license = column[Option[License]]("license")
  def tags = column[List[String]]("tags")
  def bannerId = column[Option[UUID]]("banner_id")
  def readmeId = column[Option[UUID]]("readme_id")
  def changelogId = column[Option[UUID]]("changelogId")
  def dataUseAgreementId = column[Option[Int]]("data_use_agreement_id")

  def * =
    (
      nodeId,
      name,
      state,
      description,
      createdAt,
      updatedAt,
      permission,
      role,
      automaticallyProcessPackages,
      statusId,
      publicationStatusId,
      `type`,
      license,
      tags,
      bannerId,
      readmeId,
      changelogId,
      dataUseAgreementId,
      etag,
      id
    ).mapTo[Dataset]
}

/**
  * Wrapper around a dataset and various computed status attributes.
  */
case class DatasetAndStatus(
  dataset: Dataset,
  status: DatasetStatus,
  publicationStatus: Option[DatasetPublicationStatus],
  canPublish: Boolean,
  locked: Boolean
)

class DatasetsMapper(val organization: Organization)
    extends TableQuery(new DatasetsTable(organization.schemaId, _)) {

  val datasetStatusMapper = new DatasetStatusMapper(organization)

  val datasetPublicationStatusMapper = new DatasetPublicationStatusMapper(
    organization
  )

  val datasetContributorMapper = new DatasetContributorMapper(organization)

  def getByNodeIds(nodeIds: Set[String]): Query[DatasetsTable, Dataset, Seq] =
    this.filter(_.nodeId inSet nodeIds)

  def get(id: Int): Query[DatasetsTable, Dataset, Seq] =
    this.filter(_.id === id)

  def getPublicationStatus(
    dataset: Dataset
  ): DBIO[Option[DatasetPublicationStatus]] = {
    dataset.publicationStatusId
      .map(datasetPublicationStatusMapper.get(_).result.headOption)
      .getOrElse(DBIO.successful(None))
  }

  /**
    * Check if the dataset is locked during publication for the given user.
    *
    * A dataset is locked to prevent modifications while in `Requested`,
    * `Accepted` and `Failed` states. Users on the publishers team are still
    * able to modify datasets in `Requested` and `Failed` states.
    */
  def isLocked(
    dataset: Dataset,
    user: User
  )(implicit
    datasetUser: DatasetUserMapper,
    datasetTeam: DatasetTeamMapper,
    executionContext: ExecutionContext
  ): DBIO[Boolean] =
    this
      .get(dataset.id)
      .joinLeft(datasetPublicationStatusMapper)
      .on(_.publicationStatusId === _.id)
      .map {
        case (dataset, publicationStatus) =>
          isLockedLifted(dataset, publicationStatus, user)
      }
      .take(1)
      .result
      .headOption
      .map(_.getOrElse(false))

  /**
    * Lifted root of `isLocked`. Can be composed directly into Slick queries.
    */
  def isLockedLifted(
    dataset: DatasetsTable,
    publicationStatus: Rep[Option[DatasetPublicationStatusTable]],
    user: User
  )(implicit
    datasetUser: DatasetUserMapper,
    datasetTeam: DatasetTeamMapper,
    ec: ExecutionContext
  ): Rep[Boolean] = {

    val isSharedWithUserOnPublisherTeam: Rep[Boolean] = OrganizationsMapper
      .getPublisherTeam(organization.id)
      .map(_._2)
      .join(datasetTeam.filter(_.datasetId === dataset.id))
      .on {
        case (organizationTeam, datasetTeam) =>
          organizationTeam.teamId === datasetTeam.teamId
      }
      .join(teamUser.filter(_.userId === user.id))
      .on {
        case ((_, datasetTeam), teamUser) =>
          datasetTeam.teamId === teamUser.teamId
      }
      .exists

    val status = publicationStatus
      .map(_.publicationStatus)
      .getOrElse((PublicationStatus.Draft: PublicationStatus))

    (Case
      If (
        status === (PublicationStatus.Requested: PublicationStatus) && isSharedWithUserOnPublisherTeam
      ) Then false
      If (
        status === (PublicationStatus.Failed: PublicationStatus) && isSharedWithUserOnPublisherTeam
      ) Then false
      If (status inSet PublicationStatus.lockedStatuses) Then true
      Else false)
  }

  def getDataset(
    id: Int
  )(implicit
    executionContext: ExecutionContext
  ): DBIO[Dataset] = {
    this
      .get(id)
      .result
      .headOption
      .flatMap {
        case None => DBIO.failed(SqlError(s"No dataset with id $id exists"))
        case Some(dataset) => DBIO.successful(dataset)
      }
  }

  def withoutDeleted: Query[DatasetsTable, Dataset, Seq] =
    this.filter(_.state =!= (DatasetState.DELETING: DatasetState))

  /**
    * Finds all datatsets for a user provided a minimum permission level.
    *
    * @param user the user to find all permitted datasets for
    * @param withRole the minimum role level (can be overriden)
    * @param datasetIds a list of dataset integer ids
    * @param datasetUser the mapper to provide access to the linking table between dataset and user
    * @param datasetTeam the mapper to provide access to the linking table between dataset and team
    * @param ec the thread pool on which to execute
    * @return a list of datasets
    */
  def find(
    user: User,
    withRole: Option[Role],
    datasetIds: Option[List[Int]]
  )(implicit
    datasetUser: DatasetUserMapper,
    datasetTeam: DatasetTeamMapper,
    ec: ExecutionContext
  ): DBIOAction[Seq[DatasetAndStatus], NoStream, Effect.Read with Effect.Read] = {
    maxRoles(user.id).flatMap { roleMap =>
      val datasetIdsByRole = roleMap
        .filter {
          case (_, role) => role >= withRole
        }
        .keys
        .toList

      this.withoutDeleted
        .filter(_.id.inSet(datasetIdsByRole))
        .filterOpt(datasetIds)(_.id.inSet(_))
        .join(datasetStatusMapper)
        .on(_.statusId === _.id)
        .joinLeft(datasetPublicationStatusMapper)
        .on(_._1.publicationStatusId === _.id)
        .map {
          case ((dataset, datasetStatus), datasetPublicationStatus) =>
            (
              dataset,
              datasetStatus,
              datasetPublicationStatus,
              canPublish(dataset),
              isLockedLifted(dataset, datasetPublicationStatus, user)
            )
        }
        .result
        .map(_.map(DatasetAndStatus.apply _ tupled))
    }
  }

  /**
    * Check if the dataset meets all conditions for publication.
    */
  def canPublish(
    dataset: DatasetsTable
  )(implicit
    datasetUser: DatasetUserMapper
  ): Rep[Boolean] =
    dataset.name.length > 0 &&
      dataset.description.map(_.length > 0).getOrElse(false) &&
      !(dataset.tags === List.empty[String]) &&
      dataset.license.isDefined &&
      dataset.readmeId.isDefined &&
      //dataset.changelogId.isDefined &&
      dataset.bannerId.isDefined &&
      // Dataset has at least one contributor
      datasetContributorMapper
        .filter(_.datasetId === dataset.id)
        .exists &&
      // Dataset owner has registered an ORCID
      datasetUser
        .filter(_.datasetId === dataset.id)
        .filter(_.role === (Role.Owner: Role))
        .join(UserMapper)
        .on(_.userId === _.id)
        .filter(_._2.orcidAuthorization.isDefined)
        .exists &&
      // Dataset cannot already be locked for publication
      !this
        .filter(_.id === dataset.id)
        .joinLeft(datasetPublicationStatusMapper)
        .on(_.publicationStatusId === _.id)
        .filter(
          _._2
            .map(_.publicationStatus.inSet(PublicationStatus.lockedStatuses))
            .getOrElse(false)
        )
        .exists

  def setOrganizationRole(datasetId: Int, role: Option[Role]) = {
    val permission = role.map(_.toPermission).orElse(None)
    this
      .get(datasetId)
      .map(ds => (ds.permission, ds.role))
      .update((permission, role))
  }

  def currentSharees(
    datasetId: Int
  )(implicit
    datasetUser: DatasetUserMapper,
    datasetTeam: DatasetTeamMapper
  ): Query[Rep[String], String, Seq] =
    datasetUser
      .getUsersBy(datasetId)
      .map(_._2.nodeId)
      .union {
        datasetTeam
          .teamsBy(datasetId)
          .map(_._2.nodeId)
      }

  def validIdTuplesToShareWith(
    includeIntegrationUsers: Boolean = false
  ): Query[(Rep[String], Rep[Int]), (String, Int), Seq] =
    OrganizationUserMapper
      .join(UserMapper)
      .on(_.userId === _.id)
      .filter {
        case (organizationUser, user) =>
          organizationUser.organizationId === organization.id && user.isIntegrationUser === includeIntegrationUsers
      }
      .map {
        case (_, usersTable) =>
          usersTable.nodeId -> usersTable.id
      }
      .union {
        OrganizationTeamMapper
          .join(TeamsMapper)
          .on(_.teamId === _.id)
          .filter {
            case (organizationTeamsTable, _) =>
              organizationTeamsTable.organizationId === organization.id
          }
          .map {
            case (_, teamsTable) =>
              teamsTable.nodeId -> teamsTable.id
          }
      }

  def owner(datasetId: Int)(implicit datasetUser: DatasetUserMapper) =
    datasetUser
      .join(UserMapper)
      .on(_.userId === _.id)
      .filter(_._1.datasetId === datasetId)
      .filter(_._1.role === (Role.Owner: Role))
      .map(_._2)
      .result
      .headOption

  def owners(
    datasetIds: Seq[Int]
  )(implicit
    datasetUser: DatasetUserMapper,
    ec: ExecutionContext
  ): DBIOAction[Map[Int, User], NoStream, Effect.Read] =
    datasetUser
      .join(UserMapper)
      .on(_.userId === _.id)
      .filter(_._1.datasetId inSet datasetIds)
      .filter(_._1.role === (Role.Owner: Role))
      .distinctOn(_._1.datasetId)
      .map {
        case (datasetUser, user) =>
          datasetUser.datasetId -> user
      }
      .result
      .map(_.toMap)

  def managers(datasetId: Int)(implicit datasetUser: DatasetUserMapper) =
    datasetUser
      .join(UserMapper)
      .on(_.userId === _.id)
      .filter(_._1.datasetId === datasetId)
      .filter(_._1.role === (Role.Manager: Role))
      .map(_._2)
      .result

  def datasetRoleMap(
    implicit
    ec: ExecutionContext
  ): DBIOAction[Map[Int, Option[Role]], NoStream, Effect.Read] =
    this
      .map(datasetTable => datasetTable.id -> datasetTable.role)
      .distinct
      .result
      .map(_.toMap)

  def maxRoles(
    userId: Int
  )(implicit
    datasetUserMapper: DatasetUserMapper,
    datasetTeamsMapper: DatasetTeamMapper,
    ec: ExecutionContext
  ): DBIOAction[Map[Int, Option[Role]], NoStream, Effect.Read] =
    for {
      datasetRoles <- this.datasetRoleMap
      datasetTeamRoles <- datasetTeamsMapper.maxRoles(userId)
      datasetUserRoles <- datasetUserMapper.maxRoles(userId)
    } yield
      (datasetRoles.toList ++ datasetTeamRoles.toList ++ datasetUserRoles.toList)
        .groupBy(_._1)
        .map {
          case (datasetId, roleGroups) => datasetId -> roleGroups.map(_._2).max
        }

  def datasetWithRole(
    userId: Int,
    datasetId: Int
  )(implicit
    datasetUserMapper: DatasetUserMapper,
    datasetTeamMapper: DatasetTeamMapper,
    ec: ExecutionContext
  ): DBIO[Option[(Dataset, Option[Role])]] =
    maxRoles(userId).flatMap { roleMap =>
      this.withoutDeleted
        .filter(_.id === datasetId)
        .result
        .headOption
        .map(_.map(dataset => (dataset, roleMap.get(datasetId).flatten)))
    }

  def datasetWithRoleByNodeId(
    userId: Int,
    datasetNodeId: String
  )(implicit
    datasetUserMapper: DatasetUserMapper,
    datasetTeamMapper: DatasetTeamMapper,
    ec: ExecutionContext
  ): DBIO[Option[(Dataset, Option[Role])]] =
    maxRoles(userId).flatMap { roleMap =>
      this.withoutDeleted
        .filter(_.nodeId === datasetNodeId)
        .result
        .headOption
        .map(_.map(dataset => (dataset, roleMap.get(dataset.id).flatten)))
    }

  def nameExists(name: String): DBIO[Boolean] =
    this
      .filter(_.name === name)
      .filter(_.state =!= (DatasetState.DELETING: DatasetState))
      .exists
      .result

}
