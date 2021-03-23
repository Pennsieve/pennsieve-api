package com.pennsieve.managers

import com.pennsieve.db._
import com.pennsieve.models._
import cats.data._
import cats.implicits._
import com.pennsieve.core.utilities
import com.pennsieve.core.utilities.FutureEitherHelpers

import scala.concurrent.{ ExecutionContext, Future }
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.domain.{
  CoreError,
  NotFound,
  PermissionError,
  PredicateError
}
import com.pennsieve.traits.PostgresProfile.api._

case class TeamManager(secureOrganizationManager: SecureOrganizationManager) {

  val db: Database = secureOrganizationManager.db
  val actor: User = secureOrganizationManager.actor

  // admin
  def create(
    name: String,
    organization: Organization
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Team] = {
    val nodeId = NodeCodes.generateId(NodeCodes.teamCode)
    val team = Team(nodeId, name)

    for {
      _ <- secureOrganizationManager.hasPermission(
        organization,
        DBPermission.Administer
      )

      teamNames <- db
        .run(OrganizationsMapper.getTeams(organization.id).map(_.name).result)
        .toEitherT
      nameExists = teamNames.map(_.toLowerCase).toSet.contains(name.toLowerCase)
      _ <- FutureEitherHelpers.assert(!nameExists)(
        PredicateError("team names must be unique")
      )

      insertQuery = for {
        teamId <- TeamsMapper returning TeamsMapper.map(_.id) += team
        organizationTeamMapping = OrganizationTeam(
          organization.id,
          teamId,
          DBPermission.Administer
        )
        _ <- OrganizationTeamMapper += organizationTeamMapping
        team <- TeamsMapper.getById(teamId)
      } yield team

      team <- db
        .run(insertQuery.transactionally)
        .whenNone[CoreError](NotFound("error creating team"))
    } yield team
  }

  // admin permission
  def update(
    organization: Organization,
    teamNodeId: String,
    name: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Team] = {
    val uniqueNameQuery = TeamsMapper
      .join(OrganizationTeamMapper)
      .on(_.id === _.teamId)
      .filter {
        case (teamsTable, organizationTeamTable) =>
          teamsTable.name === name &&
            organizationTeamTable.organizationId === organization.id
      }
      .exists
    for {
      team <- getByNodeId(teamNodeId, DBPermission.Administer)
      isNameTaken <- db.run(uniqueNameQuery.result).toEitherT
      _ <- FutureEitherHelpers.assert[CoreError](!isNameTaken)(
        PredicateError("name must be unique")
      )
      _ <- db.run(TeamsMapper.updateName(team.id, name)).toEitherT
    } yield team.copy(name = name)
  }

  def teamOrganizationPermissionQuery
    : Query[(TeamsTable, Rep[DBPermission]), (Team, DBPermission), Seq] =
    TeamsMapper
      .join(OrganizationTeamMapper)
      .on(_.id === _.teamId)
      .joinLeft(OrganizationUserMapper)
      .on {
        case ((_, organizationTable), organizationUserTable) =>
          organizationTable.organizationId === organizationUserTable.organizationId &&
            organizationUserTable.userId === actor.id
      }
      .map[
        (TeamsTable, Rep[DBPermission]),
        (TeamsTable, Rep[DBPermission]),
        (Team, DBPermission)
      ] {
        case ((teamsTable, _), organizationUserTable) =>
          (
            teamsTable,
            organizationUserTable
              .map(_.permission)
              .getOrElse(DBPermission.NoPermission: DBPermission)
          )
      }

  def getWithPermission(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Team, DBPermission)] = {
    val query = teamOrganizationPermissionQuery
      .filter(_._1.id === id)
      .result
      .headOption

    db.run(query).whenNone[CoreError](NotFound(s"Team($id)"))
  }

  def get(
    id: Int,
    permission: DBPermission = DBPermission.Read
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Team] =
    for {
      result <- getWithPermission(id)
      (team, actorOrganizationPermission) = result
      _ <- FutureEitherHelpers.assert[CoreError](
        actor.isSuperAdmin || actorOrganizationPermission >= permission
      )(PermissionError(actor.nodeId, permission, s"Team($id)"))
    } yield team

  // admin permission
  def delete(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] = {
    for {
      _ <- get(id, DBPermission.Administer)
      result <- db.run(TeamsMapper.filter(_.id === id).delete).toEitherT
    } yield result
  }

  def getWithPermission(
    nodeId: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Team, DBPermission)] = {
    val query = teamOrganizationPermissionQuery
      .filter(_._1.nodeId === nodeId)
      .result
      .headOption

    db.run(query).whenNone[CoreError](NotFound(nodeId))
  }

  def getByNodeId(
    nodeId: String,
    permission: DBPermission = DBPermission.Read
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Team] =
    for {
      result <- getWithPermission(nodeId)
      (team, actorOrganizationPermission) = result
      _ <- FutureEitherHelpers.assert[CoreError](
        actorOrganizationPermission >= permission
      )(PermissionError(actor.nodeId, permission, nodeId))
    } yield team

  def getByNodeIds(
    nodeIds: Set[String],
    permission: DBPermission = DBPermission.Read
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[Team]] = {
    val query = teamOrganizationPermissionQuery
      .filter {
        case (teamsTable, actorsOrganizationPermission) =>
          teamsTable.nodeId.inSet(nodeIds) && actorsOrganizationPermission >= permission
      }
      .map(_._1)
    db.run(query.result).toEitherT
  }

  def getUsers(
    team: Team
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[User]] = {
    val query = teamUser
      .filter(_.teamId === team.id)
      .flatMap(_.user)
    for {
      _ <- get(team.id)
      users <- db.run(query.result).map(_.toList).toEitherT
    } yield users
  }

  // admin
  def removeUser(
    team: Team,
    user: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] =
    for {
      _ <- get(team.id, DBPermission.Administer)
      result <- db.run(TeamsMapper.deleteUser(team.id, user.id)).toEitherT
    } yield result

  // admin
  def addUser(
    team: Team,
    user: User,
    permission: DBPermission
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, TeamUser] = {
    val teamUserMapping = TeamUser(team.id, user.id, permission)

    for {
      _ <- get(team.id, DBPermission.Administer)
      _ <- db.run(teamUser += teamUserMapping).toEitherT
    } yield teamUserMapping
  }

  def getAdministrators(
    team: Team
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[User]] = {
    val query = teamUser
      .join(UserMapper)
      .on(_.userId === _.id)
      .filter(_._1.teamId === team.id)
      .map(_._2)
    db.run(query.result).toEitherT
  }
}
