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
import com.pennsieve.dtos.APITokenSecretDTO
import com.pennsieve.models.{ CognitoId, Organization, TokenSecret }

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

  val unlinkedExternalUsers: mutable.ArrayBuffer[(String, String, String)] =
    mutable.ArrayBuffer.empty

  val deletedUsers: mutable.ArrayBuffer[String] =
    mutable.ArrayBuffer.empty

  val deletedUserAttributes: mutable.ArrayBuffer[(String, List[String])] =
    mutable.ArrayBuffer.empty

  val updatedUserAttributes: mutable.ArrayBuffer[(String, (String, String))] =
    mutable.ArrayBuffer.empty

  val disabledUsers: mutable.ArrayBuffer[String] =
    mutable.ArrayBuffer.empty

  val authenticatedUsers: mutable.ArrayBuffer[String] =
    mutable.ArrayBuffer.empty

  val userPasswordSets: mutable.ArrayBuffer[String] =
    mutable.ArrayBuffer.empty

  val usersWithLinkedOrcidId: mutable.ArrayBuffer[String] =
    // loggedInUser(me) and externalUser(other)
    mutable.ArrayBuffer() ++= List[String]("test@test.com", "test@external.com")

  def inviteUser(
    email: Email,
    suppressEmail: Boolean = false,
    verifyEmail: Boolean = true,
    invite_path: String = "invite",
    customMessage: Option[String] = None
  )(implicit
    ec: ExecutionContext
  ): Future[CognitoId.UserPoolId] = {
    sentInvites.append(email)
    Future.successful(CognitoId.UserPoolId.randomId())
  }

  def getCognitoId(
    username: String
  )(implicit
    ec: ExecutionContext
  ): Future[String] =
    Future.successful(CognitoId.UserPoolId.randomId().toString)

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
    secret: TokenSecret,
    organization: Organization
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
    Future.successful(())
  }

  def hasExternalUserLink(
    username: String,
    providerName: String
  )(implicit
    ec: ExecutionContext
  ): Future[Boolean] =
    Future.successful(usersWithLinkedOrcidId.contains(username))

  def unlinkExternalUser(
    providerName: String,
    attributeName: String,
    attributeValue: String
  )(implicit
    ec: ExecutionContext
  ): Future[Unit] = {
    unlinkedExternalUsers.append((providerName, attributeName, attributeValue))
    usersWithLinkedOrcidId -= attributeValue
    Future.successful(())
  }

  def deleteUser(
    username: String
  )(implicit
    ec: ExecutionContext
  ): Future[Unit] = {
    deletedUsers.append(username)
    Future.successful(())
  }

  def disableUser(
    username: String
  )(implicit
    ec: ExecutionContext
  ): Future[Unit] = {
    disabledUsers.append(username)
    Future.successful(())
  }

  def deleteUserAttributes(
    username: String,
    attributeNames: List[String]
  )(implicit
    ec: ExecutionContext
  ): Future[Unit] = {
    deletedUserAttributes.append((username, attributeNames))
    Future.successful(())
  }

  def updateUserAttribute(
    username: String,
    attributeName: String,
    attributeValue: String
  )(implicit
    ec: ExecutionContext
  ): Future[Boolean] = {
    updatedUserAttributes.append((username, (attributeName, attributeValue)))
    Future.successful(true)
  }

  def updateUserAttributes(
    username: String,
    attributes: Map[String, String]
  )(implicit
    ec: ExecutionContext
  ): Future[Boolean] = {
    attributes.foreach {
      case (name, value) =>
        updatedUserAttributes.append((username, (name, value)))
    }
    Future.successful(true)
  }

  def authenticateUser(
    username: String,
    password: String
  )(implicit
    ec: ExecutionContext
  ): Future[Boolean] = {
    authenticatedUsers.append(username)
    Future.successful(true)
  }

  def setUserPassword(
    username: String,
    password: String
  )(implicit
    ec: ExecutionContext
  ): Future[Boolean] = {
    userPasswordSets.append(username)
    Future.successful(true)
  }

  def reset(): Unit = {
    sentDeletes.clear()
    sentInvites.clear()
    sentTokenInvites.clear()
    reSentInvites.clear()
    sentOrganizationUpdates.clear()
    unlinkedExternalUsers.clear()
    deletedUsers.clear()
    deletedUserAttributes.clear()
    updatedUserAttributes.clear()
    disabledUsers.clear()
    authenticatedUsers.clear()
    userPasswordSets.clear()
  }
}
