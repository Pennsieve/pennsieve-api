// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.managers

import cats.syntax.either._
import com.authy.AuthyApiClient
import com.authy.api.{ User => AuthyUser, Users => AuthyUsers }
import com.pennsieve.api.Error
import com.pennsieve.models.User
import com.typesafe.scalalogging.LazyLogging
import org.scalatra.{ ActionResult, InternalServerError }

object AuthyManager extends LazyLogging {

  def createAuthyUser(
    user: User,
    phoneNumber: String,
    countryCode: String
  )(
    authyUsers: AuthyUsers
  ): Either[ActionResult, AuthyUser] = {
    val authyUser = authyUsers.createUser(user.email, phoneNumber, countryCode)
    if (authyUser.isOk) {
      Either.right(authyUser)
    } else {
      val msg = s"Authy user creation failed: ${authyUser.getError.toString}"
      Either.left(InternalServerError(Error(msg)))
    }
  }

  def deleteAuthyUser(
    user: User
  )(
    authyClient: AuthyApiClient
  ): Either[ActionResult, User] = {
    val response = authyClient.getUsers.deleteUser(user.authyId)
    if (response.isOk) {
      Right(user.copy(authyId = 0))
    } else {
      val msg = s"Error deleting authy user: ${response.getError.getMessage}"
      Left(InternalServerError(Error(msg)))
    }
  }

  def validateAuthyToken(
    user: User,
    token: String
  )(
    authyClient: AuthyApiClient
  ): Either[ActionResult, User] = {
    val verification = authyClient.getTokens.verify(user.authyId, token)
    if (verification.isOk) {
      Right(user)
    } else {
      val msg =
        s"Two Factor validation failed: ${verification.getError.getMessage}"
      Left(InternalServerError(Error(msg)))
    }
  }

}
