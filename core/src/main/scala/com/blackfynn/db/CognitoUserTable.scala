// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.db

import com.blackfynn.traits.PostgresProfile.api._
import com.blackfynn.models.CognitoId

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
