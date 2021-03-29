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

import com.pennsieve.domain.SqlError
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.models.{
  CognitoId,
  Degree,
  OrcidAuthorization,
  Organization,
  User
}

import java.time.ZonedDateTime
import java.util.UUID
import scala.concurrent.ExecutionContext

final class UserTable(tag: Tag)
    extends Table[User](tag, Some("pennsieve"), "users") {

  // set by the database
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)

  def email = column[String]("email")
  def password = column[String]("password")
  def firstName = column[String]("first_name")
  def middleInitial = column[Option[String]]("middle_initial")
  def lastName = column[String]("last_name")
  def degree = column[Option[Degree]]("degree")
  def credential = column[String]("credential")
  def color = column[String]("color")
  def url = column[String]("url")
  def authyId = column[Int]("authy_id")
  def isSuperAdmin = column[Boolean]("is_super_admin")
  def preferredOrganizationId = column[Option[Int]]("preferred_org_id")
  def status = column[Boolean]("status")
  def orcidAuthorization =
    column[Option[OrcidAuthorization]]("orcid_authorization")
  def nodeId = column[String]("node_id")

  def * =
    (
      nodeId,
      email,
      firstName,
      middleInitial,
      lastName,
      degree,
      password,
      credential,
      color,
      url,
      authyId,
      isSuperAdmin,
      preferredOrganizationId,
      status,
      orcidAuthorization,
      updatedAt,
      createdAt,
      id
    ).mapTo[User]
}

object UserMapper extends TableQuery(new UserTable(_)) {
  def getById(id: Int) = this.filter(_.id === id).result.headOption
  def getByNodeId(nodeId: String) =
    this.filter(_.nodeId === nodeId).result.headOption
  def getByNodeIds(nodeIds: Set[String]) =
    this.filter(_.nodeId inSet nodeIds).result
  def getId(nodeId: String) =
    this.filter(_.nodeId === nodeId).map(_.id).result.headOption
  def getByEmail(email: String) =
    this.filter(_.email.toLowerCase === email.toLowerCase).result.headOption

  def getByCognitoId(cognitoId: CognitoId): DBIO[Option[(User, CognitoUser)]] =
    this
      .join(CognitoUserMapper)
      .on(_.id === _.userId)
      .filter(_._2.cognitoId === cognitoId)
      .result
      .headOption

  def getUser(
    id: Int
  )(implicit
    executionContext: ExecutionContext
  ): DBIO[User] = {
    this
      .getById(id)
      .flatMap {
        case None => DBIO.failed(SqlError(s"No user with id $id exists"))
        case Some(user) => DBIO.successful(user)
      }
  }

  def getOrganizationByNodeId(id: Int, organizationId: String) =
    getOrganizations(id).filter(_.nodeId === organizationId).result.headOption

  def getOrganizations(id: Int) =
    (OrganizationsMapper join OrganizationUserMapper._getUsers(id) on (_.id === _.organizationId))
      .map(_._1)

  def getPackages(
    userId: Int,
    organization: Organization
  )(implicit
    ec: ExecutionContext
  ) = {
    val packages = new PackagesMapper(organization)
    packages.getByOwnerId(userId).result.map(_.toList)
  }

}
