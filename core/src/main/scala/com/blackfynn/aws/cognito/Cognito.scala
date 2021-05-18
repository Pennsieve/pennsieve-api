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

import cats.implicits._
import com.pennsieve.aws.email.Email
import com.pennsieve.domain.{ NotFound, PredicateError }
import com.pennsieve.dtos.Secret
import com.pennsieve.models.{ CognitoId, Organization }
import com.typesafe.config.Config
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderAsyncClient
import software.amazon.awssdk.services.cognitoidentityprovider.model.{
  AdminCreateUserRequest,
  AdminDeleteUserRequest,
  AdminSetUserPasswordRequest,
  AttributeType,
  DeliveryMediumType,
  MessageActionType,
  UserType
}

import java.util.UUID
import scala.collection.JavaConverters._
import scala.compat.java8.FutureConverters._
import scala.concurrent.{ ExecutionContext, Future }

trait CognitoClient {

  def inviteUser(
    email: Email,
    suppressEmail: Boolean = false,
    verifyEmail: Boolean = true,
    selfService: Boolean = true
  )(implicit
    ec: ExecutionContext
  ): Future[CognitoId.UserPoolId]

  def resendUserInvite(
    email: Email,
    cognitoId: CognitoId.UserPoolId
  )(implicit
    ec: ExecutionContext
  ): Future[CognitoId.UserPoolId]

  def createClientToken(
    token: String,
    secret: Secret,
    organization: Organization
  )(implicit
    ec: ExecutionContext
  ): Future[CognitoId.TokenPoolId]

  def deleteClientToken(
    token: String
  )(implicit
    ec: ExecutionContext
  ): Future[Unit]
}

object Cognito {

  def apply(cognitoConfig: CognitoConfig): Cognito =
    new Cognito(
      CognitoIdentityProviderAsyncClient
        .builder()
        .region(cognitoConfig.region)
        .httpClientBuilder(NettyNioAsyncHttpClient.builder())
        .build(),
      cognitoConfig
    )

  def apply(config: Config): Cognito =
    Cognito(CognitoConfig(config))
}

class Cognito(
  val client: CognitoIdentityProviderAsyncClient,
  val cognitoConfig: CognitoConfig
) extends CognitoClient {

  /**
    * Verify user email addresses on Cognito account creation. Users only need
    * to use the verification flow if they sign themselves up, which we don't
    * support yet.
    *
    * If suppressEmail=false, Cognito will not send an invite email (for initial
    * account import).
    */
  def inviteUser(
    email: Email,
    suppressEmail: Boolean = false,
    verifyEmail: Boolean = true,
    selfService: Boolean = false
  )(implicit
    ec: ExecutionContext
  ): Future[CognitoId.UserPoolId] = {
    val randomUppercaseChar =
      scala.util.Random.shuffle(('A' to 'Z').toList).head
    val builder = AdminCreateUserRequest
      .builder()
      .userPoolId(cognitoConfig.userPool.id)
      .username(email.address)
      .temporaryPassword(UUID.randomUUID().toString() + randomUppercaseChar)
      .userAttributes(
        List(
          AttributeType.builder().name("email").value(email.address).build(),
          AttributeType
            .builder()
            .name("self_service")
            .value(selfService.toString())
            .build(),
          AttributeType
            .builder()
            .name("email_verified")
            .value(verifyEmail.toString())
            .build()
        ).asJava
      )
      .desiredDeliveryMediums(List(DeliveryMediumType.EMAIL).asJava)

    val request =
      if (suppressEmail)
        builder.messageAction(MessageActionType.SUPPRESS).build()
      else builder.build()

    adminCreateUser(request).map(CognitoId.UserPoolId(_))
  }

  /**
    * The client token pool is configured to only allow username + password
    * auth, and users cannot reset passwords.
    */
  def createClientToken(
    token: String,
    secret: Secret,
    organization: Organization
  )(implicit
    ec: ExecutionContext
  ): Future[CognitoId.TokenPoolId] = {

    val createUserRequest = AdminCreateUserRequest
      .builder()
      .userPoolId(cognitoConfig.tokenPool.id)
      .username(token)
      .userAttributes(
        List(
          AttributeType
            .builder()
            .name("custom:organization_node_id")
            .value(organization.nodeId)
            .build(),
          AttributeType
            .builder()
            .name("custom:organization_id")
            .value(organization.id.toString)
            .build()
        ).asJava
      )
      .build()

    val setPasswordRequest = AdminSetUserPasswordRequest
      .builder()
      .password(secret.plaintext)
      .permanent(true)
      .userPoolId(cognitoConfig.tokenPool.id)
      .username(token)
      .build()

    for {
      cognitoId <- adminCreateUser(createUserRequest)

      _ <- client
        .adminSetUserPassword(setPasswordRequest)
        .toScala

    } yield CognitoId.TokenPoolId(cognitoId)
  }

  def deleteClientToken(
    token: String
  )(implicit
    ec: ExecutionContext
  ): Future[Unit] = {
    val request = AdminDeleteUserRequest
      .builder()
      .userPoolId(cognitoConfig.tokenPool.id)
      .username(token)
      .build()

    client
      .adminDeleteUser(request)
      .toScala
      .map(_ => ())
  }

  def resendUserInvite(
    email: Email,
    cognitoId: CognitoId.UserPoolId
  )(implicit
    ec: ExecutionContext
  ): Future[CognitoId.UserPoolId] = {

    // TODO: sanity check if user already exists before sending?
    val resendRequest = AdminCreateUserRequest
      .builder()
      .userPoolId(cognitoConfig.userPool.id)
      .username(email.address)
      .desiredDeliveryMediums(List(DeliveryMediumType.EMAIL).asJava)
      .messageAction(MessageActionType.RESEND)
      .build()

    for {
      responseId <- adminCreateUser(resendRequest).map(CognitoId.UserPoolId(_))

      _ <- if (responseId != cognitoId)
        Future.failed(
          PredicateError(
            s"Mismatched Cognito ID: expected $cognitoId but got $responseId"
          )
        )
      else
        Future.successful(())

    } yield cognitoId
  }

  /**
    * Create Cognito user and parse Cognito ID from response.
    */
  private def adminCreateUser(
    request: AdminCreateUserRequest
  )(implicit
    ec: ExecutionContext
  ): Future[UUID] = {
    for {
      cognitoResponse <- client
        .adminCreateUser(request)
        .toScala

      cognitoId <- parseCognitoId(cognitoResponse.user()) match {
        case Some(cognitoId) => Future.successful(cognitoId)
        case None =>
          Future.failed(NotFound("Could not parse Cognito ID from response"))
      }
    } yield cognitoId
  }

  /**
    * Parse Cognito ID from the "sub" user attribute
    */
  private def parseCognitoId(user: UserType): Option[UUID] =
    user
      .attributes()
      .asScala
      .find(_.name() == "sub")
      .map(_.value())
      .flatMap(s => Either.catchNonFatal(UUID.fromString(s)).toOption)
}
