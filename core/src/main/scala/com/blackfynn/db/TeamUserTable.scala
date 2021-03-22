// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.db

import java.time.ZonedDateTime

import com.blackfynn.traits.PostgresProfile.api._
import com.blackfynn.models.{ DBPermission, TeamUser }

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
