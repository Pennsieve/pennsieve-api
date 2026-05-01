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

package com.pennsieve.helpers.fakes

import cats.data.EitherT
import com.pennsieve.domain.{ CoreError, NotFound, PredicateError }
import com.pennsieve.managers.{ SecureOrganizationManager, TeamManager }
import com.pennsieve.models.{
  DBPermission,
  NodeCodes,
  Organization,
  Team,
  TeamUser,
  User
}

import scala.concurrent.{ ExecutionContext, Future }

/**
  * In-memory fake of the case-class `TeamManager`. Backs everything onto
  * `InMemoryState.teams` / `teamMemberships` rather than the database.
  */
class FakeTeamManager(
  state: InMemoryState,
  orgId: Int
)(
  override val secureOrganizationManager: SecureOrganizationManager
) extends TeamManager(secureOrganizationManager) {

  private def organization: Organization =
    state.organizations.getOrElse(
      orgId,
      throw new RuntimeException(s"Organization($orgId) not found in state")
    )

  override def create(
    name: String,
    organization: Organization
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Team] = {
    val nameLower = name.toLowerCase
    val taken = state.teams.values.exists(t => t.name.toLowerCase == nameLower)
    if (taken)
      EitherT.leftT(PredicateError("team names must be unique"))
    else {
      val id = state.newId()
      val team = Team(NodeCodes.generateId(NodeCodes.teamCode), name, id = id)
      state.teams.put((organization.id, id), team)
      EitherT.rightT(team)
    }
  }

  override def update(
    organization: Organization,
    teamNodeId: String,
    name: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Team] = {
    state.teams.values.find(_.nodeId == teamNodeId) match {
      case None => EitherT.leftT(NotFound(teamNodeId): CoreError)
      case Some(team) =>
        val taken =
          state.teams.values.exists(t => t.name == name && t.id != team.id)
        if (taken)
          EitherT.leftT(PredicateError("name must be unique"): CoreError)
        else {
          val updated = team.copy(name = name)
          state.teams.put((organization.id, team.id), updated)
          EitherT.rightT(updated)
        }
    }
  }

  override def get(
    id: Int,
    permission: DBPermission = DBPermission.Read
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Team] =
    state.teams.get((orgId, id)) match {
      case Some(t) => EitherT.rightT(t)
      case None => EitherT.leftT(NotFound(s"Team($id)"): CoreError)
    }

  override def getByNodeId(
    nodeId: String,
    permission: DBPermission = DBPermission.Read
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Team] =
    state.teams.values.find(_.nodeId == nodeId) match {
      case Some(t) => EitherT.rightT(t)
      case None => EitherT.leftT(NotFound(nodeId): CoreError)
    }

  override def getByNodeIds(
    nodeIds: Set[String],
    permission: DBPermission = DBPermission.Read
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[Team]] =
    EitherT.rightT(
      state.teams.values.filter(t => nodeIds.contains(t.nodeId)).toSeq
    )

  override def delete(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] = {
    state.teams.remove((orgId, id))
    val removed = state.teamMemberships.collect {
      case (k @ (o, tid, _), _) if o == orgId && tid == id => k
    }.toList
    removed.foreach(state.teamMemberships.remove)
    EitherT.rightT(1)
  }

  override def getUsers(
    team: Team
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[User]] = {
    val userIds = state.teamMemberships.collect {
      case ((o, tid, uid), _) if o == orgId && tid == team.id => uid
    }.toSet
    EitherT.rightT(userIds.flatMap(state.users.get).toList.sortBy(_.id))
  }

  override def removeUser(
    team: Team,
    user: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] = {
    state.teamMemberships.remove((orgId, team.id, user.id))
    EitherT.rightT(1)
  }

  override def addUser(
    team: Team,
    user: User,
    permission: DBPermission
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, TeamUser] =
    if (user.isIntegrationUser)
      EitherT.leftT(PredicateError("Cannot add Integration User to a Team"))
    else {
      state.teamMemberships.put((orgId, team.id, user.id), permission)
      EitherT.rightT(TeamUser(team.id, user.id, permission))
    }

  override def getAdministrators(
    team: Team
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[User]] =
    getUsers(team).map(_.toSeq)
}
