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

import com.pennsieve.models._
import com.pennsieve.traits.PostgresProfile.api._
import scala.concurrent.ExecutionContext

class DatasetTeamTable(schema: String, tag: Tag)
    extends Table[DatasetTeam](tag, Some(schema), "dataset_team") {

  def datasetId = column[Int]("dataset_id")
  def teamId = column[Int]("team_id")

  def permission = column[DBPermission]("permission_bit")
  def role = column[Option[Role]]("role")

  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)

  def pk = primaryKey("combined_pk", (datasetId, teamId))

  def * =
    (datasetId, teamId, permission, role, createdAt, updatedAt)
      .mapTo[DatasetTeam]
}

class DatasetTeamMapper(organization: Organization)
    extends TableQuery(new DatasetTeamTable(organization.schemaId, _)) {

  def getByDataset(datasetId: Int): Query[DatasetTeamTable, DatasetTeam, Seq] =
    this.filter(_.datasetId === datasetId)

  def getBy(datasetId: Int, teamId: Int) =
    this
      .filter(_.datasetId === datasetId)
      .filter(_.teamId === teamId)

  def teamsBy(
    datasetId: Int
  ): Query[(DatasetTeamTable, TeamsTable), (DatasetTeam, Team), Seq] =
    this
      .getByDataset(datasetId)
      .join(TeamsMapper)
      .on(_.teamId === _.id)

  def maxRoles(
    userId: Int
  )(implicit
    ec: ExecutionContext
  ): DBIOAction[Map[Int, Option[Role]], NoStream, Effect.Read] =
    this
      .join(TeamsMapper)
      .on(_.teamId === _.id)
      .join(teamUser)
      .on {
        case ((_, teamsTable), teamUserTable) =>
          teamsTable.id === teamUserTable.teamId &&
            teamUserTable.userId === userId
      }
      .map(row => row._1._1.datasetId -> row._1._1.role)
      .distinct
      .result
      .map { result =>
        result
          .groupBy(_._1)
          .map {
            case (resultDatasetId, group) =>
              resultDatasetId -> group.map(_._2).max
          }
      }

  def teams(
    datasetId: Int
  ): Query[(TeamsTable, OrganizationTeamTable), (Team, OrganizationTeam), Seq] =
    this
      .join(TeamsMapper)
      .on(_.teamId === _.id)
      .join(OrganizationTeamMapper)
      .on(_._1.teamId === _.teamId)
      .filter {
        case ((datasetTeamTable, _), _) =>
          datasetTeamTable.datasetId === datasetId
      }
      .map { case ((_, t), ot) => (t, ot) }

  def teamsWithRoles(
    datasetId: Int
  ): Query[(TeamsTable, Rep[Option[Role]]), (Team, Option[Role]), Seq] =
    this
      .teamsBy(datasetId)
      .map {
        case (datasetTeamTable, team) => (team, datasetTeamTable.role)
      }
}
