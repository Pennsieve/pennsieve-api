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

import com.pennsieve.models.Team
import com.pennsieve.traits.PostgresProfile.api._
import java.time.ZonedDateTime

import com.pennsieve.domain.SqlError

import scala.concurrent.ExecutionContext

final class TeamsTable(tag: Tag)
    extends Table[Team](tag, Some("pennsieve"), "teams") {

  // set by the database
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)

  def name = column[String]("name")
  def nodeId = column[String]("node_id")

  def * = (nodeId, name, createdAt, updatedAt, id).mapTo[Team]
}

object TeamsMapper extends TableQuery(new TeamsTable(_)) {
  def getById(id: Int) = this.filter(_.id === id).result.headOption
  def getByNodeId(nodeId: String) =
    this.filter(_.nodeId === nodeId).result.headOption

  def updateName(id: Int, name: String)(implicit ec: ExecutionContext) = {
    this.filter(_.id === id).map(_.name).update(name).map {
      case 1 => DBIO.successful(1)
      case _ => DBIO.failed(SqlError(s"Failed to update name of team $id"))
    }
  }

  def getOrganizations(id: Int) =
    (OrganizationsMapper join OrganizationTeamMapper.getByTeamId(id) on (_.id === _.organizationId))
      .map(_._1)

  def getUsers(id: Int) =
    (UserMapper join teamUser.getByTeam(id) on (_.id === _.userId)).map(_._1)

  def deleteUser(id: Int, userId: Int) =
    teamUser.getByTeam(id).filter(_.userId === userId).delete

  def deleteUser(ids: List[Int], userId: Int) =
    teamUser.filter(_.userId === userId).filter(_.teamId inSet ids).delete

}
