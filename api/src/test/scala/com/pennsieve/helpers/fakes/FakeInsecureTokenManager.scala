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
import com.pennsieve.aws.cognito.CognitoClient
import com.pennsieve.domain.{ CoreError, NotFound, ThrowableError }
import com.pennsieve.managers.TokenManager
import com.pennsieve.models.{ Organization, Token, TokenSecret, User }
import com.pennsieve.traits.PostgresProfile.api.Database

import java.util.UUID
import scala.concurrent.{ ExecutionContext, Future }

/**
  * In-memory fake of the insecure `TokenManager`. Same backing store as
  * `FakeSecureTokenManager` (`InMemoryState.tokens`), but without the
  * actor-equals-target user check — the insecure variant is used by services
  * that act on behalf of someone else (e.g. integration users).
  */
class FakeInsecureTokenManager(state: InMemoryState) extends TokenManager {

  def db: Database =
    sys.error(
      "FakeInsecureTokenManager: a method not yet stubbed by your test " +
        "tried to use the database. Override the method on this fake."
    )

  override def create(
    name: String,
    user: User,
    organization: Organization,
    cognitoClient: CognitoClient
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Token, TokenSecret)] = {
    val tokenString = UUID.randomUUID.toString
    val secret = TokenSecret(UUID.randomUUID.toString)
    EitherT(
      cognitoClient
        .createClientToken(tokenString, secret, organization)
        .map { cognitoId =>
          val token = Token(
            name = name,
            token = tokenString,
            cognitoId = cognitoId,
            organizationId = organization.id,
            userId = user.id,
            id = state.newId()
          )
          state.tokens.put(tokenString, token)
          Right((token, secret)): Either[CoreError, (Token, TokenSecret)]
        }
        .recover { case t => Left(ThrowableError(t)) }
    )
  }

  override def get(
    user: User,
    organization: Organization
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[Token]] =
    EitherT.rightT(
      state.tokens.values
        .filter(t => t.userId == user.id && t.organizationId == organization.id)
        .toList
    )

  override def get(
    uuid: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Token] =
    state.tokens.get(uuid) match {
      case Some(t) => EitherT.rightT(t)
      case None => EitherT.leftT(NotFound(s"Token ($uuid)"))
    }

  override def getByUserId(
    userId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Token] =
    state.tokens.values.find(_.userId == userId) match {
      case Some(t) => EitherT.rightT(t)
      case None => EitherT.leftT(NotFound(s"Token by User ID ($userId)"))
    }

  override def update(
    token: Token
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Token] =
    state.tokens.get(token.token) match {
      case Some(_) =>
        state.tokens.put(token.token, token)
        EitherT.rightT(token)
      case None => EitherT.leftT(NotFound(s"Token (${token.token})"))
    }

  override def delete(
    token: Token,
    cognitoClient: CognitoClient
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] =
    EitherT(
      cognitoClient
        .deleteClientToken(token.token)
        .map { _ =>
          state.tokens.remove(token.token)
          Right(1): Either[CoreError, Int]
        }
        .recover { case t => Left(ThrowableError(t)) }
    )

  override def getOrganization(
    token: Token
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Organization] =
    state.organizations.get(token.organizationId) match {
      case Some(o) => EitherT.rightT(o)
      case None =>
        EitherT.leftT(NotFound(s"Organization ${token.organizationId}"))
    }
}
