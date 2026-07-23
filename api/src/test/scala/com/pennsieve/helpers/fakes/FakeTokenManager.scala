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
import com.pennsieve.domain.{
  CoreError,
  NotFound,
  PermissionError,
  ThrowableError
}
import com.pennsieve.managers.SecureTokenManager
import com.pennsieve.models.DBPermission.{ Read, Write }
import com.pennsieve.models.{ Organization, Token, TokenSecret, User }
import com.pennsieve.traits.PostgresProfile.api.Database

import java.util.UUID
import scala.concurrent.{ ExecutionContext, Future }

/**
  * In-memory fake for SecureTokenManager. State lives in InMemoryState.tokens
  * keyed by token-string (the UUID); permission checks mirror the real
  * SecureTokenManager (only the actor can read/update/delete their own
  * tokens, and tokens can only be created on behalf of the actor).
  */
class FakeSecureTokenManager(state: InMemoryState, val actor: User)
    extends SecureTokenManager {

  def db: Database =
    sys.error(
      "FakeSecureTokenManager: a method not yet stubbed by your test tried " +
        "to use the database. Override the method on this fake."
    )

  override def create(
    name: String,
    user: User,
    organization: Organization,
    cognitoClient: CognitoClient
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Token, TokenSecret)] = {
    if (user.id != actor.id)
      EitherT.leftT(PermissionError(actor.nodeId, Write, ""))
    else {
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
      case Some(t) if t.userId == actor.id => EitherT.rightT(t)
      case Some(t) =>
        EitherT.leftT(PermissionError(actor.nodeId, Read, t.token))
      case None => EitherT.leftT(NotFound(s"Token ($uuid)"))
    }

  override def update(
    token: Token
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Token] =
    if (token.userId != actor.id)
      EitherT.leftT(PermissionError(actor.nodeId, Read, token.token))
    else {
      state.tokens.put(token.token, token)
      EitherT.rightT(token)
    }

  override def delete(
    token: Token,
    cognitoClient: CognitoClient
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] =
    if (token.userId != actor.id)
      EitherT.leftT(PermissionError(actor.nodeId, Read, token.token))
    else
      EitherT(
        cognitoClient
          .deleteClientToken(token.token)
          .map { _ =>
            state.tokens.remove(token.token)
            Right(1): Either[CoreError, Int]
          }
          .recover { case t => Left(ThrowableError(t)) }
      )
}
