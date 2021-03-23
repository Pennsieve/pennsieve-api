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

import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.models._

import java.time.ZonedDateTime
import com.pennsieve.domain.InvalidOrganization
import com.pennsieve.dtos.UserDTO

import scala.concurrent.{ ExecutionContext, Future }

final class OrganizationsTable(tag: Tag)
    extends Table[Organization](tag, Some("pennsieve"), "organizations") {

  // set by the database
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def createdAt =
    column[ZonedDateTime]("created_at", O.AutoInc) // set by the database on insert
  def updatedAt =
    column[ZonedDateTime]("updated_at", O.AutoInc) // set by the database on update

  def name = column[String]("name")
  def slug = column[String]("slug")
  def terms = column[Option[String]]("terms")
  def nodeId = column[String]("node_id")
  // TODO: remove this after transition
  def encryptionKeyId = column[Option[String]]("encryption_key_id")
  def customTermsOfServiceVersion =
    column[Option[ZonedDateTime]]("custom_terms_of_service_version")
  def storageBucket = column[Option[String]]("storage_bucket")

  def * =
    (
      nodeId,
      name,
      slug,
      terms,
      encryptionKeyId,
      customTermsOfServiceVersion,
      storageBucket,
      createdAt,
      updatedAt,
      id
    ).mapTo[Organization]
}

object OrganizationsMapper extends TableQuery(new OrganizationsTable(_)) {

  def get(id: Int): DBIO[Option[Organization]] =
    this.filter(_.id === id).result.headOption

  def getOrganization(
    id: Int
  )(implicit
    executionContext: ExecutionContext
  ): DBIO[Organization] = {
    this
      .get(id)
      .flatMap {
        case None => DBIO.failed(InvalidOrganization(id))
        case Some(organization) => DBIO.successful(organization)
      }
  }

  def getNodeId(id: Int): DBIO[Option[String]] =
    this.filter(_.id === id).map(_.nodeId).result.headOption

  def getId(nodeId: String): DBIO[Option[Int]] =
    this.filter(_.nodeId === nodeId).map(_.id).result.headOption

  def getById(id: Int): DBIO[Option[Organization]] =
    this.filter(_.id === id).result.headOption

  def getByNodeId(nodeId: String): DBIO[Option[Organization]] =
    this.filter(_.nodeId === nodeId).result.headOption

  def getTeam(id: Int, teamId: Int): DBIO[Option[Team]] =
    getTeams(id).filter(_.id === teamId).result.headOption

  def getTeamByNodeId(id: Int, teamId: String): DBIO[Option[Team]] =
    getTeams(id).filter(_.nodeId === teamId).result.headOption

  def getTeamWithOrganizationTeamByNodeId(
    id: Int,
    teamId: String
  ): DBIO[Option[(Team, OrganizationTeam)]] =
    getTeamsWithOrganizationTeam(id)
      .filter(_._1.nodeId === teamId)
      .result
      .headOption

  def getTeams(id: Int): Query[TeamsTable, Team, Seq] =
    (TeamsMapper join OrganizationTeamMapper.getByOrganizationId(id) on (_.id === _.teamId))
      .map(_._1)

  def getTeamsWithOrganizationTeam(
    id: Int
  ): Query[(TeamsTable, OrganizationTeamTable), (Team, OrganizationTeam), Seq] =
    (TeamsMapper join OrganizationTeamMapper.getByOrganizationId(id) on (_.id === _.teamId))

  def getTeams(id: Int, userId: Int): Query[TeamUserTable, TeamUser, Seq] =
    (teamUser.getByUser(userId) join OrganizationTeamMapper
      .getByOrganizationId(id) on (_.teamId === _.teamId)).map(_._1)

  def getPublisherTeam(
    id: Int
  ): Query[(TeamsTable, OrganizationTeamTable), (Team, OrganizationTeam), Seq] =
    getTeamsWithOrganizationTeam(id)
      .filter(
        _._2.systemTeamType === (SystemTeamType.Publishers: SystemTeamType)
      )

  def getUsers(id: Int): Query[UserTable, User, Seq] =
    UserMapper
      .join(OrganizationUserMapper)
      .on { (userTable, userOrganizationTable) =>
        userTable.id === userOrganizationTable.userId && userOrganizationTable.organizationId === id
      }
      .map(_._1)

  def getOrganizationUsers(
    id: Int
  ): Query[(UserTable, OrganizationUserTable), (User, OrganizationUser), Seq] =
    UserMapper
      .join(OrganizationUserMapper)
      .on { (userTable, userOrganizationTable) =>
        userTable.id === userOrganizationTable.userId && userOrganizationTable.organizationId === id
      }

  def getUserByNodeIdInOrganization(
    nodeId: String,
    id: Int
  ): DBIO[Option[User]] = {
    getOrganizationUsers(id)
      .map(_._1)
      .filter(_.nodeId === nodeId)
      .result
      .headOption
  }

  def getUserByIntIdInOrganization(
    userId: Int,
    orgId: Int
  ): DBIO[Option[User]] = {
    getOrganizationUsers(orgId)
      .map(_._1)
      .filter(_.id === userId)
      .result
      .headOption
  }

  def getUserByEmailInOrganization(
    email: String,
    id: Int
  ): DBIO[Option[User]] = {
    getOrganizationUsers(id)
      .map(_._1)
      .filter(_.email.toLowerCase === email.toLowerCase)
      .result
      .headOption
  }

  def isMember(organization: Organization, user: User): DBIO[Boolean] =
    OrganizationUserMapper
      .filter(
        ou => ou.organizationId === organization.id && ou.userId === user.id
      )
      .exists
      .result

  def deleteUser(id: Int, userId: Int): DBIO[Int] =
    OrganizationUserMapper
      ._getOrganizations(id)
      .filter(_.userId === userId)
      .delete

  def isActive(organization: Organization): DBIO[Boolean] = {
    val lastYear = ZonedDateTime.now().minusYears(1)
    // If the organization itself was updated within 1 year, no need to look at datasets:
    if (organization.updatedAt.isAfter(lastYear)) {
      return DBIO.from(Future.successful(true))
    }
    val datasets = new DatasetsMapper(organization)
    datasets.filter(_.updatedAt > lastYear).exists.result
  }

  /**
    * Retrieves an organization with the appropriate permission for the user.
    *
    * If the user does not belong to the organization the result will be empty.
    *
    * If the user is a super admin we retrieve the organization with elevated permissions.
    */
  def getWithPermission(user: User): Query[
    (OrganizationsTable, Rep[DBPermission]),
    (Organization, DBPermission),
    Seq
  ] =
    if (user.isSuperAdmin) {
      this
        .joinLeft(OrganizationUserMapper)
        .on {
          case (organizationTable, organizationUserTable) =>
            organizationTable.id === organizationUserTable.organizationId &&
              organizationUserTable.userId === user.id
        }
        .map[
          (OrganizationsTable, Rep[DBPermission]),
          (OrganizationsTable, Rep[DBPermission]),
          (Organization, DBPermission)
        ] {
          case (organiationTable, organizationUserTable) =>
            (
              organiationTable,
              organizationUserTable
                .map(_.permission)
                .getOrElse(DBPermission.Owner: DBPermission)
            )
        }
    } else {
      this
        .join(OrganizationUserMapper)
        .on {
          case (organizationTable, organizationUserTable) =>
            organizationTable.id === organizationUserTable.organizationId &&
              organizationUserTable.userId === user.id
        }
        .map[
          (OrganizationsTable, Rep[DBPermission]),
          (OrganizationsTable, Rep[DBPermission]),
          (Organization, DBPermission)
        ] {
          case (organizationTable, organizationUserTable) =>
            (organizationTable, organizationUserTable.permission)
        }
    }

  def get(user: User)(id: Int): Query[
    (OrganizationsTable, Rep[DBPermission]),
    (Organization, DBPermission),
    Seq
  ] =
    getWithPermission(user).filter(_._1.id === id)

  def getByNodeId(user: User)(nodeId: String): Query[
    (OrganizationsTable, Rep[DBPermission]),
    (Organization, DBPermission),
    Seq
  ] =
    getWithPermission(user).filter(_._1.nodeId === nodeId)

  def getUsersWithPermission(
    organization: Organization
  ): Query[(UserTable, Rep[DBPermission]), (User, DBPermission), Seq] =
    OrganizationUserMapper
      .join(UserMapper)
      .on(_.userId === _.id)
      .filter {
        case (organizationUserTable, _) =>
          organizationUserTable.organizationId === organization.id
      }
      .map[
        (UserTable, Rep[DBPermission]),
        (UserTable, Rep[DBPermission]),
        (User, DBPermission)
      ] {
        case (organizationUserTable, usersTable) =>
          usersTable -> organizationUserTable.permission
      }

  def getCustomTermsOfServiceVersion(nodeId: String) =
    this
      .filter(_.nodeId === nodeId)
      .map(_.customTermsOfServiceVersion)
      .result
      .headOption

  def updateCustomTermsOfServiceVersion(
    nodeId: String,
    version: ZonedDateTime
  ) =
    this
      .filter(_.nodeId === nodeId)
      .map(_.customTermsOfServiceVersion)
      .update(Some(version))
}
