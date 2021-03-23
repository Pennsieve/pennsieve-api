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
import com.pennsieve.models.{ CognitoId, DBPermission, UserInvite }

import java.time.ZonedDateTime

final class UserInvitesTable(tag: Tag)
    extends Table[UserInvite](tag, Some("pennsieve"), "user_invite") {

  // set by the database
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)

  def organizationId = column[Int]("organization_id")
  def email = column[String]("email")
  def cognitoId = column[CognitoId]("cognito_id")

  def firstName = column[String]("first_name")
  def lastName = column[String]("last_name")
  def permission = column[DBPermission]("permission_bit")
  def nodeId = column[String]("node_id")

  // TODO remove these fields
  def validUntil = column[ZonedDateTime]("valid_until")

  def * =
    (
      nodeId,
      organizationId,
      email,
      firstName,
      lastName,
      permission,
      cognitoId,
      validUntil,
      createdAt,
      updatedAt,
      id
    ).mapTo[UserInvite]
}

object UserInvitesMapper extends TableQuery(new UserInvitesTable(_)) {
  def get(id: Int) = this.filter(_.id === id).result.headOption
  def getNodeId(id: Int) =
    this.filter(_.id === id).map(_.nodeId).result.headOption
  def getId(nodeId: String) =
    this.filter(_.nodeId === nodeId).map(_.id).result.headOption

  def getById(id: Int) = this.filter(_.id === id).result.headOption

  def getByNodeId(nodeId: String) =
    this.filter(_.nodeId === nodeId).result.headOption

  def getByCognitoId(cognitoId: CognitoId) =
    this.filter(_.cognitoId === cognitoId).result

  def getByEmail(email: String) =
    this.filter(_.email.toLowerCase === email.toLowerCase)
  def getByOrganizationId(organizationId: Int) =
    this.filter(_.organizationId === organizationId)

  def getOrganization(id: Int) =
    (OrganizationsMapper join this.filter(_.id === id) on (_.id === _.organizationId))
      .map(_._1)
      .result
      .headOption

  def delete(id: Int) = this.filter(_.id === id).delete
}
