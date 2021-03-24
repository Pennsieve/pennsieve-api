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

import com.pennsieve.aws.cognito.CognitoClient
import com.pennsieve.models.{ Organization, Token, User }
import io.github.nremond.SecureHash
import cats.data.EitherT
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.db.{ OrganizationsMapper, TokensMapper }
import cats.implicits._
import com.pennsieve.core.utilities.FutureEitherHelpers
import com.pennsieve.domain.{ CoreError, NotFound, PermissionError }
import com.pennsieve.dtos.Secret
import com.pennsieve.models.DBPermission.{ Read, Write }

import scala.concurrent.{ ExecutionContext, Future }

class TokenManager(db: Database) {

  def create(
    name: String,
    user: User,
    organization: Organization,
    cognitoClient: CognitoClient
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Token, Secret)] = {
    val tokenString = java.util.UUID.randomUUID.toString
    val secret = java.util.UUID.randomUUID.toString

    val token = Token(
      name,
      tokenString,
      SecureHash.createHash(secret),
      organization.id,
      user.id
    )

    for {
      _ <- cognitoClient
        .adminCreateToken(tokenString, cognitoClient.getTokenPoolId())
        .toEitherT
      _ <- cognitoClient
        .adminSetUserPassword(
          tokenString,
          secret,
          cognitoClient.getTokenPoolId()
        )
        .toEitherT
      tokenId <- db
        .run((TokensMapper returning TokensMapper.map(_.id)) += token)
        .toEitherT
      token <- db
        .run(TokensMapper.getById(tokenId))
        .whenNone[CoreError](NotFound(s"Token (${token.id}"))
    } yield (token, Secret(secret))
  }

  def get(
    user: User,
    organization: Organization
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[Token]] =
    db.run(
        TokensMapper
          .filter(
            t => (t.userId === user.id && t.organizationId === organization.id)
          )
          .result
      )
      .toEitherT
      .map(_.toSet.toList)

  def get(
    uuid: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Token] =
    db.run(TokensMapper.getByToken(uuid))
      .whenNone[CoreError](NotFound(s"Token ($uuid)"))

  def update(
    token: Token
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Token] =
    for {
      _ <- db
        .run(TokensMapper.filter(_.id === token.id).update(token))
        .toEitherT
      _ <- db
        .run(TokensMapper.getById(token.id))
        .whenNone[CoreError](NotFound(s"Token (${token.id}"))
    } yield token

  def delete(
    token: Token,
    cognitoClient: CognitoClient
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] = {
    for {
      _ <- cognitoClient
        .adminDeleteUser(token.name, cognitoClient.getTokenPoolId())
        .toEitherT
      result <- db.run(TokensMapper.filter(_.id === token.id).delete).toEitherT
    } yield result
  }

  def getOrganization(
    token: Token
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Organization] =
    db.run(OrganizationsMapper.getById(token.organizationId))
      .whenNone[CoreError](NotFound(s"Organization ${token.organizationId}"))

}

class SecureTokenManager(actor: User, db: Database) extends TokenManager(db) {

  override def create(
    name: String,
    user: User,
    organization: Organization,
    cognitoClient: CognitoClient
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Token, Secret)] = {
    val tokenString = java.util.UUID.randomUUID.toString
    val secret = java.util.UUID.randomUUID.toString
    val token = Token(
      name,
      tokenString,
      SecureHash.createHash(secret),
      organization.id,
      user.id
    )

    for {
      _ <- FutureEitherHelpers.assert[CoreError](token.userId == actor.id)(
        PermissionError(actor.nodeId, Write, "")
      )
      _ <- cognitoClient
        .adminCreateToken(tokenString, cognitoClient.getTokenPoolId())
        .toEitherT
      _ <- cognitoClient
        .adminSetUserPassword(tokenString, secret, cognitoClient.getTokenPoolId())
        .toEitherT
      tokenId <- db
        .run((TokensMapper returning TokensMapper.map(_.id)) += token)
        .toEitherT
      token <- db
        .run(TokensMapper.getById(tokenId))
        .whenNone[CoreError](NotFound(s"Token (${token.id}"))
    } yield (token, Secret(secret))
  }

  override def get(
    uuid: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Token] =
    for {
      token <- super.get(uuid)(ec)
      _ <- FutureEitherHelpers.assert[CoreError](token.userId == actor.id)(
        PermissionError(actor.nodeId, Read, token.token)
      )
    } yield token

  override def update(
    token: Token
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Token] =
    for {
      _ <- FutureEitherHelpers.assert[CoreError](token.userId == actor.id)(
        PermissionError(actor.nodeId, Read, token.token)
      )
      _ <- db
        .run(TokensMapper.filter(_.id === token.id).update(token))
        .toEitherT
      _ <- db
        .run(TokensMapper.getById(token.id))
        .whenNone[CoreError](NotFound(s"Token (${token.id}"))
    } yield token

  override def delete(
    token: Token,
    cognitoClient: CognitoClient
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] = {

    for {
      _ <- FutureEitherHelpers.assert[CoreError](token.userId == actor.id)(
        PermissionError(actor.nodeId, Read, token.token)
      )
      _ <- cognitoClient
        .adminDeleteUser(token.name, cognitoClient.getTokenPoolId())
        .toEitherT
      result <- db.run(TokensMapper.filter(_.id === token.id).delete).toEitherT
    } yield result
  }
}
