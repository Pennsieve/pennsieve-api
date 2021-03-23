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

import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.models.CognitoId

import java.time.ZonedDateTime
import java.util.UUID

case class CognitoUser(cognitoId: CognitoId, userId: Int)

/**
  * Correlate Cognito -> Blackfynn users.
  *
  * Note: this is designed so that multiple Cognito logins can be associated
  * with the same Blackfynn account, eg when we integration ORCID logins.
  */
final class CognitoUserTable(tag: Tag)
    extends Table[CognitoUser](tag, Some("pennsieve"), "cognito_users") {

  def cognitoId = column[CognitoId]("cognito_id")
  def userId = column[Int]("user_id")

  def * = (cognitoId, userId).mapTo[CognitoUser]
}

object CognitoUserMapper extends TableQuery(new CognitoUserTable(_)) {}
