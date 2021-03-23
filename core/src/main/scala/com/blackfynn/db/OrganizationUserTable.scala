// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.db

import java.time.ZonedDateTime
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.models.{ DBPermission, OrganizationUser }

final class OrganizationUserTable(tag: Tag)
    extends Table[OrganizationUser](tag, Some("pennsieve"), "organization_user") {

  def organizationId = column[Int]("organization_id")
  def userId = column[Int]("user_id")

  def permission = column[DBPermission]("permission_bit")

  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)

  def pk = primaryKey("combined_pk", (organizationId, userId))

  def * =
    (organizationId, userId, permission, createdAt, updatedAt)
      .mapTo[OrganizationUser]
}

object OrganizationUserMapper extends TableQuery(new OrganizationUserTable(_)) {
  def _getUsers(id: Int) = this.filter(_.userId === id)
  def _getOrganizations(id: Int) = this.filter(_.organizationId === id)

  def getBy(userId: Int, organizationId: Int) =
    this
      .filter(_.userId === userId)
      .filter(_.organizationId === organizationId)

  def getOwnersAndAdministrators(organizationId: Int) =
    this
      .join(UserMapper)
      .on {
        case (organizationUsers, users) =>
          organizationUsers.userId === users.id &&
            organizationUsers.organizationId === organizationId &&
            (organizationUsers.permission === (DBPermission.Owner: DBPermission) || organizationUsers.permission === (DBPermission.Administer: DBPermission))
      }
}
