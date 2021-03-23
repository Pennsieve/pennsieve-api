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
import com.pennsieve.models.{ DBPermission, TeamUser }

final class TeamUserTable(tag: Tag)
    extends Table[TeamUser](tag, Some("pennsieve"), "team_user") {

  def teamId = column[Int]("team_id")
  def userId = column[Int]("user_id")

  def permission = column[DBPermission]("permission_bit")

  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)

  def pk = primaryKey("combined_pk", (teamId, userId))

  def user = foreignKey("team_user_user_id_fkey", userId, UserMapper)(_.id)
  def team =
    foreignKey("team_user_team_id_fkey", teamId, TeamsMapper)(
      _.id,
      onDelete = ForeignKeyAction.Cascade
    )

  def * = (teamId, userId, permission, createdAt, updatedAt).mapTo[TeamUser]
}

object teamUser extends TableQuery(new TeamUserTable(_)) {
  def getByUser(id: Int) = this.filter(_.userId === id)
  def getByTeam(id: Int) = this.filter(_.teamId === id)
}
