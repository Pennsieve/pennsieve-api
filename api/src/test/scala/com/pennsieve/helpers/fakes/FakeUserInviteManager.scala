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

package com.pennsieve.helpers.fakes

import cats.data.EitherT
import com.pennsieve.aws.cognito.CognitoClient
import com.pennsieve.aws.email.Email
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.domain.{ CoreError, NotFound, PredicateError }
import com.pennsieve.managers.{ UserInviteManager, UserManager }
import com.pennsieve.models.{
  CognitoId,
  DBPermission,
  NodeCodes,
  Organization,
  UserInvite
}
import com.pennsieve.traits.PostgresProfile.api.Database

import java.time.{ Duration, ZonedDateTime }
import scala.concurrent.{ ExecutionContext, Future }

class FakeUserInviteManager(state: InMemoryState) extends UserInviteManager {

  def db: Database =
    sys.error(
      "FakeUserInviteManager: a method not yet stubbed by your test tried " +
        "to use the database. Override the method on this fake."
    )

  override def createOrRefreshUserInvite(
    organization: Organization,
    email: String,
    firstName: String,
    lastName: String,
    permission: DBPermission,
    ttl: Duration,
    customMessage: Option[String] = None
  )(implicit
    userManager: UserManager,
    cognitoClient: CognitoClient,
    ec: ExecutionContext
  ): EitherT[Future, CoreError, UserInvite] = {
    val normalized = email.trim.toLowerCase
    for {
      exists <- userManager.emailExists(normalized)
      _ <- if (exists)
        EitherT.leftT[Future, Unit](
          PredicateError(s"Email: $email already in use"): CoreError
        )
      else EitherT.rightT[Future, CoreError](())

      existing = state.userInvites.values
        .find(i => i.email == normalized && i.organizationId == organization.id)

      invite <- existing match {
        case Some(i) =>
          // Refresh — re-send invite via cognitoClient.
          cognitoClient
            .resendUserInvite(Email(i.email), i.cognitoId)
            .toEitherT
            .map(_ => i)

        case None =>
          // Steal cognito ID from another org's invite if it exists.
          val borrowedCognitoId = state.userInvites.values
            .find(_.email == normalized)
            .map(_.cognitoId)

          for {
            cognitoId <- borrowedCognitoId match {
              case Some(id) =>
                EitherT.rightT[Future, CoreError](id)
              case None =>
                cognitoClient
                  .inviteUser(Email(normalized), customMessage = customMessage)
                  .toEitherT
            }

            id = state.newId()
            row = UserInvite(
              nodeId = NodeCodes.generateId(NodeCodes.userCode),
              organizationId = organization.id,
              email = normalized,
              firstName = firstName.trim,
              lastName = lastName.trim,
              permission = permission,
              cognitoId = cognitoId,
              validUntil = ZonedDateTime.now().plus(ttl),
              id = id
            )
          } yield {
            state.userInvites.put(id, row)
            row
          }
      }
    } yield invite
  }

  override def isValid(
    userInvite: UserInvite,
    date: ZonedDateTime = ZonedDateTime.now
  ): Boolean = userInvite.validUntil.isAfter(date)

  override def get(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, UserInvite] =
    state.userInvites.get(id) match {
      case Some(i) => EitherT.rightT(i)
      case None => EitherT.leftT(NotFound(s"UserInvite ($id)"): CoreError)
    }

  override def getByNodeId(
    nodeId: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, UserInvite] =
    state.userInvites.values.find(_.nodeId == nodeId) match {
      case Some(i) => EitherT.rightT(i)
      case None => EitherT.leftT(NotFound(s"UserInvite ($nodeId)"): CoreError)
    }

  override def getByCognitoId(
    cognitoId: CognitoId.UserPoolId
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[UserInvite]] =
    EitherT.rightT(
      state.userInvites.values.filter(_.cognitoId == cognitoId).toList
    )

  override def getByEmail(
    email: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[UserInvite]] = {
    val normalized = email.trim.toLowerCase
    EitherT.rightT(
      state.userInvites.values.filter(_.email == normalized).toList
    )
  }

  override def getByOrganization(
    organization: Organization
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[UserInvite]] =
    EitherT.rightT(
      state.userInvites.values
        .filter(_.organizationId == organization.id)
        .toList
        .sortBy(_.id)
    )

  override def getOrganization(
    userInvite: UserInvite
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Organization] =
    state.organizations.get(userInvite.organizationId) match {
      case Some(o) => EitherT.rightT(o)
      case None =>
        EitherT.leftT(NotFound(s"UserInvite (${userInvite.id})"): CoreError)
    }

  override def delete(
    userInvite: UserInvite
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] = {
    state.userInvites.remove(userInvite.id) match {
      case Some(_) => EitherT.rightT(1)
      case None => EitherT.rightT(0)
    }
  }

  override def refreshUserInvite(
    userInvite: UserInvite
  )(implicit
    ec: ExecutionContext,
    cognitoClient: CognitoClient
  ): EitherT[Future, CoreError, UserInvite] =
    cognitoClient
      .resendUserInvite(Email(userInvite.email), userInvite.cognitoId)
      .toEitherT
      .map(_ => userInvite)
}
