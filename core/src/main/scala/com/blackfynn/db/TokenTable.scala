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

package com.pennsieve.db

import java.time.ZonedDateTime

import com.pennsieve.models.{ CognitoId, Token }
import com.pennsieve.traits.PostgresProfile.api._

// TODO: unique cognito_id
final class TokenTable(tag: Tag)
    extends Table[Token](tag, Some("pennsieve"), "tokens") {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def name = column[String]("name")
  def token = column[String]("token")
  def secret = column[String]("secret")
  def cognitoId = column[CognitoId.TokenPoolId]("cognito_id")
  def organizationId = column[Int]("organization_id")
  def userId = column[Int]("user_id")
  def lastUsed = column[Option[ZonedDateTime]]("last_used")
  def createdAt =
    column[ZonedDateTime]("created_at", O.AutoInc) // set by the database on insert

  def * =
    (
      name,
      token,
      secret,
      cognitoId,
      organizationId,
      userId,
      lastUsed,
      createdAt,
      id
    ).mapTo[Token]
}

object TokensMapper extends TableQuery(new TokenTable(_)) {
  def getById(id: Int) = this.filter(_.id === id).result.headOption
  def getByToken(token: String) =
    this.filter(_.token === token).result.headOption
  def getByUser(userId: Int) =
    this.filter(_.userId === userId).result.headOption

  def getByCognitoId(cognitoId: CognitoId.TokenPoolId) =
    this.filter(_.cognitoId === cognitoId).result.headOption
}
