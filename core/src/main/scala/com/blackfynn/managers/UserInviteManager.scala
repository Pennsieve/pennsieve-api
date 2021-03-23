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

import java.time.{ Duration, ZonedDateTime }
import java.util.UUID

import cats.data._
import cats.implicits._
import com.pennsieve.aws.cognito.CognitoClient
import com.pennsieve.aws.email.{ Email, EmailToSend }
import com.pennsieve.core.utilities.MessageTemplates

import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration._
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.db.UserInvitesMapper
import com.pennsieve.managers.OrganizationManager.AddEmailResult
import com.pennsieve.models._
import com.pennsieve.core.utilities.FutureEitherHelpers.assert
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.domain.{
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
                    .adminCreateUser(email.trim.toLowerCase)
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
