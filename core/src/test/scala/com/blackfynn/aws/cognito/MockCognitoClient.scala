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

import com.pennsieve.aws.email.Email
import com.pennsieve.dtos.Secret
import com.pennsieve.models.CognitoId
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.{ ExecutionContext, Future }

class MockCognito() extends CognitoClient {

  val sentInvites: mutable.ArrayBuffer[Email] =
    mutable.ArrayBuffer.empty

  val sentTokenInvites: mutable.ArrayBuffer[String] =
    mutable.ArrayBuffer.empty

  val sentDeletes: mutable.ArrayBuffer[String] =
    mutable.ArrayBuffer.empty

  val reSentInvites: mutable.Map[Email, CognitoId.UserPoolId] =
    mutable.Map.empty

  val sentOrganizationUpdates: mutable.Map[String, String] =
    mutable.Map.empty

  def inviteUser(
    email: Email
  )(implicit
    ec: ExecutionContext
  ): Future[CognitoId.UserPoolId] = {
    sentInvites.append(email)
    Future.successful(CognitoId.UserPoolId.randomId())
  }

  def resendUserInvite(
    email: Email,
    cognitoId: CognitoId.UserPoolId
  )(implicit
    ec: ExecutionContext
  ): Future[CognitoId.UserPoolId] = {
    reSentInvites.update(email, cognitoId)
    Future.successful(cognitoId)
  }

  def createClientToken(
    token: String,
    secret: Secret
  )(implicit
    ec: ExecutionContext
  ): Future[CognitoId.TokenPoolId] = {
    sentTokenInvites.append(token)
    Future.successful(CognitoId.TokenPoolId.randomId())
  }

  def deleteClientToken(
    token: String
  )(implicit
    ec: ExecutionContext
  ): Future[Unit] = {
    sentDeletes.append(token)
    Future.successful(Unit)
  }

  def pushUserOrganizationAttribute(
    username: String,
    organization: String
  )(implicit
    ec: ExecutionContext
  ): Future[Unit] = {
    sentOrganizationUpdates.update(username, organization)
    Future.successful(())
  }

  def reset(): Unit = {
    sentDeletes.clear()
    sentInvites.clear()
    sentTokenInvites.clear()
    reSentInvites.clear()
    sentOrganizationUpdates.clear()
  }
}
