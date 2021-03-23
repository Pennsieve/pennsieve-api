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

import java.time.ZonedDateTime

import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.models.{ DBPermission, OrganizationTeam, SystemTeamType }

final class OrganizationTeamTable(tag: Tag)
    extends Table[OrganizationTeam](tag, Some("pennsieve"), "organization_team") {

  def organizationId = column[Int]("organization_id")
  def teamId = column[Int]("team_id")

  def permission = column[DBPermission]("permission_bit")

  def systemTeamType = column[Option[SystemTeamType]]("system_team_type")

  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)

  def pk = primaryKey("combined_pk", (organizationId, teamId))

  def * =
    (organizationId, teamId, permission, systemTeamType, createdAt, updatedAt)
      .mapTo[OrganizationTeam]
}

object OrganizationTeamMapper extends TableQuery(new OrganizationTeamTable(_)) {
  def getByTeamId(teamId: Int) = this.filter(_.teamId === teamId)
  def getByOrganizationId(organizationId: Int) =
    this.filter(_.organizationId === organizationId)
}
