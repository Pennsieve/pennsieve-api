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
import com.pennsieve.models.{
  CognitoId,
  Organization,
  Token,
  TokenSecret,
  User
}
import cats.data.EitherT
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.db.{ OrganizationsMapper, TokensMapper }
import cats.implicits._
import com.pennsieve.core.utilities.FutureEitherHelpers
import com.pennsieve.domain.{ CoreError, NotFound, PermissionError }
import com.pennsieve.models.TokenSecret
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
  ): EitherT[Future, CoreError, (Token, TokenSecret)] = {
    val tokenString = java.util.UUID.randomUUID.toString
    val secret = TokenSecret(java.util.UUID.randomUUID.toString)

    for {
      cognitoId <- cognitoClient
        .createClientToken(tokenString, secret, organization)
        .toEitherT

      token = Token(name, tokenString, cognitoId, organization.id, user.id)

      tokenId <- db
        .run((TokensMapper returning TokensMapper.map(_.id)) += token)
        .toEitherT

      token <- db
        .run(TokensMapper.getById(tokenId))
        .whenNone[CoreError](NotFound(s"Token (${token.id}"))
    } yield (token, secret)
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

  def getByCognitoId(
    cognitoId: CognitoId.TokenPoolId
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Token] =
    db.run(TokensMapper.getByCognitoId(cognitoId))
      .whenNone[CoreError](NotFound(s"Token by Cognito ID ($cognitoId)"))

  def getByUserId(
    userId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Token] =
    db.run(TokensMapper.getByUser(userId))
      .whenNone[CoreError](NotFound(s"Token by User ID ($userId)"))

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
    val query = for {
      result <- TokensMapper.filter(_.id === token.id).delete
      _ <- DBIO.from(cognitoClient.deleteClientToken(token.token))
    } yield result

    db.run(query.transactionally).toEitherT
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
  ): EitherT[Future, CoreError, (Token, TokenSecret)] =
    for {
      _ <- FutureEitherHelpers.assert[CoreError](user.id == actor.id)(
        PermissionError(actor.nodeId, Write, "")
      )
      result <- super.create(name, user, organization, cognitoClient)
    } yield result

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

  override def getByCognitoId(
    cognitoId: CognitoId.TokenPoolId
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Token] =
    for {
      token <- super.getByCognitoId(cognitoId)
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
      _ <- super.update(token)
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
      result <- super.delete(token, cognitoClient)
    } yield result
  }

}
