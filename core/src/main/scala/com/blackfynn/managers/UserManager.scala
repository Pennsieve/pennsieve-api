// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

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
import io.github.nremond.SecureHash
import java.util.UUID

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Random

class UserManager(db: Database) {

  private def randomPassword: String = {
    val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
    Stream
      .continually(Random.nextInt(alphabet.length))
      .map(alphabet)
      .take(20)
      .mkString
  }

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

  def resetUserPassword(
    user: User,
    newPassword: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, User] = {
    val hashedPassword = SecureHash.createHash(newPassword)

    for {
      _ <- db
        .run(
          UserMapper
            .filter(_.id === user.id)
            .map(_.password)
            .update(hashedPassword)
        )
        .toEitherT
      updatedUser <- get(user.id)
    } yield updatedUser
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
    * @param password
    * @param organizationManager
    * @return
    */
  def createFromInvite(
    cognitoId: CognitoId,
    firstName: String,
    middleInitial: Option[String],
    lastName: String,
    degree: Option[Degree],
    title: String,
    password: String
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

      user: User = User(
        NodeCodes.generateId(NodeCodes.userCode),
        headInvite.email.trim.toLowerCase,
        firstName,
        middleInit,
        lastName,
        degree,
        password = "",
        credential = title
      )

      newUser <- create(user, Some(password))
      cognitoUser <- db
        .run(
          CognitoUserMapper returning CognitoUserMapper += CognitoUser(
            cognitoId = cognitoId,
            userId = newUser.id
          )
        )
        .toEitherT

      organizations <- invites.traverse(
        invite => userInviteManager.getOrganization(invite)
      )

      _ <- organizations
        .zip(invites)
        .map {
          case (organization, inv) =>
            organizationManager.addUser(organization, newUser, inv.permission)
        }
        .sequence

      _ <- invites.traverse(invite => userInviteManager.delete(invite))
    } yield newUser

  // TODO remove this
  def create(
    user: User,
    cleartextPassword: Option[String]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, User] = {
    val password = cleartextPassword.getOrElse(randomPassword)
    val hashedPassword = SecureHash.createHash(password)
    val newUser = user.copy(color = randomColor, password = hashedPassword)

    for {
      exists <- emailExists(user.email)
      _ <- assert(!exists)(PredicateError("email must be unique"))
      createdUser <- db
        .run(UserMapper.returning(UserMapper) += newUser)
        .toEitherT
    } yield createdUser
  }

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
    cognitoId: CognitoId
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
