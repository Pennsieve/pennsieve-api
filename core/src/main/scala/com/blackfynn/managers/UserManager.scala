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

package com.pennsieve.managers

import com.pennsieve.core.utilities.FutureEitherHelpers.assert
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.core.utilities.{
  checkAndNormalizeInitial,
  checkOrErrorT,
  FutureEitherHelpers
}
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.db._
import com.pennsieve.models.{
  CognitoId,
  DBPermission,
  Degree,
  NodeCodes,
  Organization,
  Package,
  Team,
  User,
  UserInvite
}
import cats.data._
import cats.implicits._
import com.pennsieve
import com.pennsieve.domain.{ CoreError, NotFound, PredicateError }
import java.util.UUID

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Random

class UserManager(db: Database) {

  private def randomColor: String = {
    val colors = List(
      "#342E37",
      "#F9CB40",
      "#FF715B",
      "#654597",
      "#F45D01",
      "#DF2935",
      "#00635D",
      "#4C212A",
      "#00635D",
      "#7765E3",
      "#B74F6F",
      "#EE8434",
      "#3B28CC",
      "#5FBFF9",
      "#474647"
    )
    Random.shuffle(colors).head
  }

  def emailExists(
    email: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Boolean] = {
    db.run(
        UserMapper
          .filter(_.email.toLowerCase === email.toLowerCase)
          .exists
          .result
      )
      .toEitherT
  }

  def getByEmail(
    email: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, User] = {
    db.run(UserMapper.getByEmail(email))
      .whenNone(NotFound(s"Email ($email)"))
  }

  def getByNodeIds(
    nodeIds: Set[String]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[User]] = {
    db.run(UserMapper.getByNodeIds(nodeIds))
      .map(_.toList)
      .toEitherT
  }

  def update(
    user: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, User] =
    for {
      emailUser <- db
        .run(
          UserMapper
            .filter(_.email.toLowerCase === user.email.toLowerCase)
            .result
            .headOption
        )
        .toEitherT
      _ <- assert(emailUser.isDefined && emailUser.get.email == user.email)(
        PredicateError("email must be unique")
      )

      _ <- db.run(UserMapper.filter(_.id === user.id).update(user)).toEitherT
      newUser <- get(user.id)
    } yield newUser

  def updateEmail(
    user: User,
    newEmail: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, User] =
    for {
      exists <- emailExists(newEmail)
      _ <- assert(!exists)(PredicateError("email must be unique"))

      _ <- db
        .run(
          UserMapper
            .filter(_.id === user.id)
            .map(_.email)
            .update(newEmail.trim.toLowerCase)
        )
        .toEitherT
      updatedUser <- get(user.id)
    } yield updatedUser

  def getPreferredOrganizationId(
    organizationNodeId: Option[String],
    defaultId: Option[Int]
  )(implicit
    organizationManager: OrganizationManager,
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Option[Int]] = {
    organizationNodeId match {
      case Some(nodeId) =>
        organizationManager
          .getByNodeId(nodeId)
          .map(o => Some(o.id))
      case None => Right(defaultId).toEitherT[Future]
    }
  }

  /**
    * This expects the implicit organizationManager and userInviteManager
    * to be insecure.
    *
    * @param inviteToken
    * @param firstName
    * @param lastName
    * @param title
    * @param organizationManager
    * @return
    */
  def createFromInvite(
    cognitoId: CognitoId.UserPoolId,
    firstName: String,
    middleInitial: Option[String],
    lastName: String,
    degree: Option[Degree],
    title: String
  )(implicit
    organizationManager: OrganizationManager,
    userInviteManager: UserInviteManager,
    ec: ExecutionContext
  ): EitherT[Future, CoreError, User] =
    for {
      invites <- userInviteManager.getByCognitoId(cognitoId)

      headInvite <- invites.headOption match {
        case Some(invite) => EitherT.rightT[Future, CoreError](invite)
        case None =>
          EitherT.leftT[Future, UserInvite](
            NotFound(s"Invite for Cognito ID $cognitoId")
          )
      }

      _ <- assert(invites.forall(_.email == headInvite.email))(
        PredicateError("Multiple invites but email address does not match")
      )

      middleInit <- checkAndNormalizeInitial(middleInitial).toEitherT[Future]

      user <- create(
        User(
          NodeCodes.generateId(NodeCodes.userCode),
          headInvite.email.trim.toLowerCase,
          firstName,
          middleInit,
          lastName,
          degree,
          credential = title,
          preferredOrganizationId = Some(headInvite.organizationId)
        )
      )

      cognitoUser <- db
        .run(CognitoUserMapper.create(cognitoId, user))
        .toEitherT

      organizations <- invites.traverse(
        invite => userInviteManager.getOrganization(invite)
      )

      _ <- organizations
        .zip(invites)
        .map {
          case (organization, inv) =>
            organizationManager.addUser(organization, user, inv.permission)
        }
        .sequence

      _ <- invites.traverse(invite => userInviteManager.delete(invite))
    } yield user

  /**
    * Creates a User after user has signed up for the platform via the challenge-response self-service endpoint.
    *
    * @param inviteToken
    * @param firstName
    * @param lastName
    * @param title
    * @param organizationManager
    * @return
    */
  def createFromSelfServiceSignUp(
    cognitoId: CognitoId.UserPoolId,
    email: String,
    firstName: String,
    middleInitial: Option[String],
    lastName: String,
    degree: Option[Degree],
    title: String
  )(implicit
    organizationManager: OrganizationManager,
    ec: ExecutionContext
  ): EitherT[Future, CoreError, User] =
    for {
      middleInit <- checkAndNormalizeInitial(middleInitial).toEitherT[Future]

      sandboxOrganization <- organizationManager.get(1) // TODO: replace this with the ID of the sandbox organization

      user <- create(
        User(
          NodeCodes.generateId(NodeCodes.userCode),
          email.trim.toLowerCase,
          firstName,
          middleInit,
          lastName,
          degree,
          credential = title,
          preferredOrganizationId = Some(sandboxOrganization.id)
        )
      )

      _cognitoUser <- db
        .run(CognitoUserMapper.create(cognitoId, user))
        .toEitherT

      _ <- organizationManager.addUser(
        sandboxOrganization,
        user,
        DBPermission.Write
      )

    } yield user

  def create(
    user: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, User] =
    for {
      exists <- emailExists(user.email)
      _ <- assert(!exists)(PredicateError("email must be unique"))
      createdUser <- db
        .run(UserMapper.returning(UserMapper) += user.copy(color = randomColor))
        .toEitherT
    } yield createdUser

  def get(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, User] = {
    db.run(UserMapper.getById(id))
      .whenNone((NotFound(s"User ($id)")))
  }

  def getByNodeId(
    nodeId: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, User] = {
    db.run(UserMapper.getByNodeId(nodeId))
      .whenNone((NotFound(s"User ($nodeId)")))
  }

  def getByCognitoId(
    cognitoId: CognitoId.UserPoolId
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (User, CognitoUser)] = {
    db.run(UserMapper.getByCognitoId(cognitoId))
      .whenNone((NotFound(s"Cognito User ($cognitoId)")))
  }

  def getPreferredOrganization(
    user: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Organization] = {
    user.preferredOrganizationId match {
      case Some(orgId) =>
        db.run(OrganizationsMapper.getById(orgId))
          .whenNone(
            NotFound(s"no preferred organization id for user: (${user.id})")
          )
      case None =>
        db.run(
            UserMapper
              .getOrganizations(user.id)
              .result
              .headOption
          )
          .whenNone(NotFound(s"no org memberships for user: (${user.id})"))
    }
  }

  def getOrganizationByNodeId(
    user: User,
    organizationId: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Organization] =
    db.run(UserMapper.getOrganizationByNodeId(user.id, organizationId))
      .whenNone(NotFound(organizationId))

  def getOrganizations(
    user: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[Organization]] =
    db.run(UserMapper.getOrganizations(user.id).result).map(_.toList).toEitherT

  def getTeams(
    user: User,
    organization: Organization
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[Team]] = {
    val query = OrganizationTeamMapper
      .join(TeamsMapper)
      .on {
        case (organizationTeamTable, teamsTable) =>
          organizationTeamTable.teamId === teamsTable.id &&
            organizationTeamTable.organizationId === organization.id
      }
      .join(teamUser)
      .on {
        case ((_, teamsTable), teamUserTable) =>
          teamsTable.id === teamUserTable.teamId &&
            teamUserTable.userId === user.id
      }
      .map {
        case ((_, teamsTable), _) =>
          teamsTable
      }

    db.run(query.result).toEitherT
  }

  def getPackages(
    user: User,
    organization: Organization
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[Package]] = {
    db.run(UserMapper.getPackages(user.id, organization)).toEitherT
  }

}
