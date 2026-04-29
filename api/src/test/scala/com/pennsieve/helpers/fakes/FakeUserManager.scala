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
import cats.implicits._
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.core.utilities.checkAndNormalizeInitial
import com.pennsieve.domain.{ CoreError, NotFound, PredicateError }
import com.pennsieve.managers.{ OrganizationManager, UserManager }
import com.pennsieve.models.{
  CognitoId,
  DBPermission,
  Degree,
  NodeCodes,
  Organization,
  User
}
import com.pennsieve.traits.PostgresProfile.api.Database

import scala.concurrent.{ ExecutionContext, Future }

class FakeUserManager(state: InMemoryState) extends UserManager {

  def db: Database =
    sys.error(
      "FakeUserManager: a method not yet stubbed by your test tried to use " +
        "the database. Override the method on this fake."
    )

  override def get(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, User] =
    state.users.get(id) match {
      case Some(u) => EitherT.rightT(u)
      case None => EitherT.leftT(NotFound(s"User ($id)"))
    }

  override def getByEmail(
    email: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, User] =
    state.users.values.find(_.email.equalsIgnoreCase(email)) match {
      case Some(u) => EitherT.rightT(u)
      case None => EitherT.leftT(NotFound(s"User ($email)"))
    }

  override def getByNodeId(
    nodeId: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, User] =
    state.users.values.find(_.nodeId == nodeId) match {
      case Some(u) => EitherT.rightT(u)
      case None => EitherT.leftT(NotFound(s"User ($nodeId)"))
    }

  override def emailExists(
    email: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Boolean] =
    EitherT.rightT(state.users.values.exists(_.email.equalsIgnoreCase(email)))

  override def create(
    user: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, User] =
    if (user.email.nonEmpty &&
      state.users.values.exists(_.email.equalsIgnoreCase(user.email)))
      EitherT.leftT(PredicateError("email must be unique or empty"))
    else {
      val withId = user.copy(id = state.newId())
      state.users.put(withId.id, withId)
      EitherT.rightT(withId)
    }

  override def update(
    user: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, User] =
    state.users.get(user.id) match {
      case Some(_) =>
        // Mirror the Postgres `lowercase_email_on_insert_trigger` (also fires
        // on UPDATE) so the fake matches real-DB behavior.
        val normalized = user.copy(email = user.email.toLowerCase)
        state.users.put(user.id, normalized)
        EitherT.rightT(normalized)
      case None => EitherT.leftT(NotFound(s"User (${user.id})"))
    }

  override def updateEmail(
    user: User,
    newEmail: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, User] = {
    val trimmed = newEmail.trim.toLowerCase
    if (state.users.values.exists(u => u.id != user.id && u.email == trimmed))
      EitherT.leftT(PredicateError("email must be unique"))
    else
      state.users.get(user.id) match {
        case Some(existing) =>
          val updated = existing.copy(email = trimmed)
          state.users.put(user.id, updated)
          EitherT.rightT(updated)
        case None => EitherT.leftT(NotFound(s"User (${user.id})"))
      }
  }

  override def getByCognitoId(
    cognitoId: CognitoId.UserPoolId
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, User] =
    state.users.values.find(_.cognitoId.contains(cognitoId)) match {
      case Some(u) => EitherT.rightT(u)
      case None => EitherT.leftT(NotFound(s"Cognito User ($cognitoId)"))
    }

  override def getOrganizations(
    user: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[Organization]] =
    EitherT.rightT(
      state.orgUserPermissions
        .collect { case ((orgId, uid), _) if uid == user.id => orgId }
        .flatMap(state.organizations.get)
        .toList
        .sortBy(_.id)
    )

  override def createFromSelfServiceSignUp(
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
      welcomeOrg <- organizationManager.getBySlug("welcome_to_pennsieve")
      newUser <- create(
        User(
          NodeCodes.generateId(NodeCodes.userCode),
          email.trim.toLowerCase,
          firstName,
          middleInit,
          lastName,
          degree,
          credential = title,
          cognitoId = Some(cognitoId),
          preferredOrganizationId = Some(welcomeOrg.id)
        )
      )
      _ <- organizationManager.addUser(welcomeOrg, newUser, DBPermission.Guest)
    } yield newUser
}
