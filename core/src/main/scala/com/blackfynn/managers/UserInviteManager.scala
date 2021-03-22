package com.blackfynn.managers

import java.time.{ Duration, ZonedDateTime }
import java.util.UUID

import cats.data._
import cats.implicits._
import com.blackfynn.aws.cognito.CognitoClient
import com.blackfynn.aws.email.{ Email, EmailToSend }
import com.blackfynn.core.utilities.MessageTemplates

import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration._
import com.blackfynn.traits.PostgresProfile.api._
import com.blackfynn.db.UserInvitesMapper
import com.blackfynn.managers.OrganizationManager.AddEmailResult
import com.blackfynn.models._
import com.blackfynn.core.utilities.FutureEitherHelpers.assert
import com.blackfynn.core.utilities.FutureEitherHelpers.implicits._
import com.blackfynn.domain.{
  CoreError,
  NotFound,
  PredicateError,
  ThrowableError
}
import scala.collection.JavaConverters._
import scala.util.Try
import java.util.UUID

/**
  * TODO: this class does not clean up invites that are never use and are
  * expired. That will need to be handled in the future.
  */
class UserInviteManager(db: Database) {

  def generateInviteTokenData(ttl: Duration): (String, ZonedDateTime) =
    (UUID.randomUUID().toString, ZonedDateTime.now().plus(ttl))

  def createOrRefreshUserInvite(
    organization: Organization,
    email: String,
    firstName: String,
    lastName: String,
    permission: DBPermission,
    ttl: Duration
  )(implicit
    userManager: UserManager,
    cognitoClient: CognitoClient,
    ec: ExecutionContext
  ): EitherT[Future, CoreError, UserInvite] =
    for {
      exists <- userManager.emailExists(email)
      _ <- assert(!exists)(PredicateError(s"Email: $email already in use"))

      invite <- db
        .run(
          UserInvitesMapper
            .filter(_.email.toLowerCase === email.toLowerCase)
            .filter(_.organizationId === organization.id)
            .result
            .headOption
        )
        .toEitherT
        .flatMap {
          // Invite already exists for this organization - refresh
          case Some(invite) => refreshUserInvite(invite)

          // No invite for this user and organization - create a new one.  If
          // this email address is invited to another organization steal the
          // Cognito ID. Otherwise create a new invite with new Cognito user
          //
          // TODO: clean this up. Move Cognito IDs and emails to a separate
          // table with proper constraints.
          case None =>
            val token = UUID.randomUUID().toString
            val nodeId = NodeCodes.generateId(NodeCodes.userCode)
            val validUntil = ZonedDateTime.now().plus(ttl)

            for {
              maybeCognitoId <- db
                .run(
                  UserInvitesMapper
                    .filter(_.email.toLowerCase === email.toLowerCase)
                    .map(_.cognitoId)
                    .take(1)
                    .result
                    .headOption
                )
                .toEitherT

              cognitoId <- maybeCognitoId match {
                case Some(id) => EitherT.rightT[Future, CoreError](id)
                case None =>
                  cognitoClient
                    .adminCreateUser(
                      email.trim.toLowerCase,
                      cognitoClient.getUserPoolId()
                    )
                    .toEitherT
              }

              invite <- db
                .run(
                  (UserInvitesMapper returning UserInvitesMapper) += UserInvite(
                    nodeId = nodeId,
                    organizationId = organization.id,
                    email = email.trim.toLowerCase,
                    firstName = firstName.trim,
                    lastName = lastName.trim,
                    permission = permission,
                    cognitoId = cognitoId,
                    validUntil = validUntil
                  )
                )
                .toEitherT
            } yield invite
        }
    } yield invite

  def isValid(
    userInvite: UserInvite,
    date: ZonedDateTime = ZonedDateTime.now
  ): Boolean =
    userInvite.validUntil.isAfter(date)

  def get(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, UserInvite] = {
    db.run(UserInvitesMapper.getById(id))
      .whenNone((NotFound(s"UserInvite ($id)")))
  }

  def getByNodeId(
    nodeId: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, UserInvite] = {
    db.run(UserInvitesMapper.getByNodeId(nodeId))
      .whenNone((NotFound(s"UserInvite ($nodeId)")))
  }

  def getByCognitoId(
    cognitoId: CognitoId
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[UserInvite]] =
    db.run(UserInvitesMapper.getByCognitoId(cognitoId)).map(_.toList).toEitherT

  def getByEmail(
    email: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[UserInvite]] = {
    db.run(UserInvitesMapper.getByEmail(email).result).map(_.toList).toEitherT
  }

  def getByOrganization(
    organization: Organization
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[UserInvite]] = {
    db.run(UserInvitesMapper.getByOrganizationId(organization.id).result)
      .map(_.toList)
      .toEitherT
  }

  def getOrganization(
    userInvite: UserInvite
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Organization] = {
    db.run(UserInvitesMapper.getOrganization(userInvite.id))
      .whenNone((NotFound(s"UserInvite (${userInvite.id})")))
  }

  // TODO: delete in Cognito?

  def delete(
    userInvite: UserInvite
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] = {
    db.run(UserInvitesMapper.delete(userInvite.id)).toEitherT
  }

  def refreshUserInvite(
    userInvite: UserInvite
  )(implicit
    ec: ExecutionContext,
    cognitoClient: CognitoClient
  ): EitherT[Future, CoreError, UserInvite] =
    cognitoClient
      .resendUserInvite(userInvite.email, userInvite.cognitoId)
      .toEitherT
      .map(_ => userInvite)
}
