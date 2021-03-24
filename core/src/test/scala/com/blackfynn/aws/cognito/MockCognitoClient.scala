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

package com.pennsieve.aws.cognito

import com.pennsieve.models.CognitoId
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.{ ExecutionContext, Future }

class MockCognito() extends CognitoClient {

  val sentInvites: mutable.ArrayBuffer[String] =
    mutable.ArrayBuffer.empty

  val sentTokenInvites: mutable.ArrayBuffer[String] =
    mutable.ArrayBuffer.empty

  val sentPasswordSets: mutable.ArrayBuffer[String] =
    mutable.ArrayBuffer.empty

  val sentDeletes: mutable.ArrayBuffer[String] =
    mutable.ArrayBuffer.empty

  val reSentInvites: mutable.Map[String, CognitoId] =
    mutable.Map.empty

  def adminCreateUser(
    email: String,
    userPoolId: String
  )(implicit
    ec: ExecutionContext
  ): Future[CognitoId] = {
    sentInvites.append(email)
    Future.successful(CognitoId.randomId())
  }

  def adminCreateToken(
    email: String,
    userPoolId: String
  )(implicit
    ec: ExecutionContext
  ): Future[CognitoId] = {
    sentTokenInvites.append(email)
    Future.successful(CognitoId.randomId())
  }

  def adminSetUserPassword(
    username: String,
    password: String,
    userPoolId: String
  )(implicit
    ec: ExecutionContext
  ): Future[Unit] = {
    sentPasswordSets.append(username)
    Future.successful(Unit)
  }

  def adminDeleteUser(
    email: String,
    userPoolId: String
  )(implicit
    ec: ExecutionContext
  ): Future[Unit] = {
    sentDeletes.append(email)
    Future.successful(Unit)
  }

  def getTokenPoolId(): String = {
    "__MOCK__tokenPoolId"
  }

  def getUserPoolId(): String = {
    "__MOCK__userPoolId"
  }

  def resendUserInvite(
    email: String,
    cognitoId: CognitoId
  )(implicit
    ec: ExecutionContext
  ): Future[CognitoId] = {
    reSentInvites.update(email, cognitoId)
    Future.successful(cognitoId)
  }

  def reset(): Unit = {
    sentDeletes.clear()
    sentInvites.clear()
    reSentInvites.clear()
  }
}
