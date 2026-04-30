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
import com.pennsieve.domain.{ CoreError, PredicateError }
import com.pennsieve.managers.{ SecureOrganizationManager, TeamManager }
import com.pennsieve.models.{ DBPermission, Team, TeamUser, User }

import scala.concurrent.{ ExecutionContext, Future }

/**
  * In-memory fake of the case-class `TeamManager`. Overrides `addUser` to
  * record team-membership in `InMemoryState` instead of writing to the DB.
  */
class FakeTeamManager(
  state: InMemoryState,
  orgId: Int
)(
  override val secureOrganizationManager: SecureOrganizationManager
) extends TeamManager(secureOrganizationManager) {

  override def addUser(
    team: Team,
    user: User,
    permission: DBPermission
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, TeamUser] = {
    if (user.isIntegrationUser)
      EitherT.leftT(PredicateError("Cannot add Integration User to a Team"))
    else {
      state.teamMemberships.put((orgId, team.id, user.id), permission)
      EitherT.rightT(TeamUser(team.id, user.id, permission))
    }
  }
}
