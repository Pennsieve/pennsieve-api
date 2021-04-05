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
import com.pennsieve.models.CognitoId
import com.pennsieve.domain.{ NotFound, PredicateError }
import com.pennsieve.dtos.Secret
import cats.data._
import cats.implicits._
import scala.compat.java8.FutureConverters._
import scala.concurrent.Future
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderAsyncClient
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.services.cognitoidentityprovider.model.{
  AdminCreateUserRequest,
  AdminCreateUserResponse,
  AdminDeleteUserRequest,
  AdminDeleteUserResponse,
  AdminSetUserPasswordRequest,
  AdminUpdateUserAttributesRequest,
  AdminUpdateUserAttributesResponse,
  AttributeType,
  DeliveryMediumType,
  MessageActionType,
  UserType
}
import scala.concurrent.ExecutionContext
import java.util.UUID
import scala.collection.JavaConverters._
import scala.collection.mutable
import net.ceedubs.ficus.Ficus._
import com.typesafe.config.Config

trait CognitoClient {
  def inviteUser(
    email: Email
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
    secret: Secret
  )(implicit
    ec: ExecutionContext
  ): Future[CognitoId.TokenPoolId]

  def deleteClientToken(
    token: String
  )(implicit
    ec: ExecutionContext
  ): Future[Unit]

  def pushUserOrganizationAttribute(
    username: String,
    organization: String
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
    */
  def inviteUser(
    email: Email
  )(implicit
    ec: ExecutionContext
  ): Future[CognitoId.UserPoolId] = {
    val request = AdminCreateUserRequest
      .builder()
      .userPoolId(cognitoConfig.userPool.id)
      .username(email.address)
      .userAttributes(
        List(
          AttributeType.builder().name("email").value(email.address).build(),
          AttributeType.builder().name("email_verified").value("true").build()
        ).asJava
      )
      .desiredDeliveryMediums(List(DeliveryMediumType.EMAIL).asJava)
      .build()
    adminCreateUser(request).map(CognitoId.UserPoolId(_))
  }

  /**
    * The client token pool is configured to only allow username + password
    * auth, and users cannot reset passwords.
    */
  def createClientToken(
    token: String,
    secret: Secret
  )(implicit
    ec: ExecutionContext
  ): Future[CognitoId.TokenPoolId] = {

    val createUserRequest = AdminCreateUserRequest
      .builder()
      .userPoolId(cognitoConfig.tokenPool.id)
      .username(token)
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
    * Provide information in Cognito about which organization a token is scoped to.
    */
  def pushUserOrganizationAttribute(
    username: String,
    organization: String
  )(implicit
    ec: ExecutionContext
  ): Future[Unit] = {
    val request = AdminUpdateUserAttributesRequest
      .builder()
      .username(username)
      .userPoolId(cognitoConfig.userPool.id)
      .userAttributes(
        List(
          AttributeType
            .builder()
            .name("custom:organization_node_id")
            .value(username)
            .build()
        ).asJava
      )
      .build()
    adminUpdateUserAttributes(request)
  }

  /**
    * Update a user's attributes and parse Cognito ID from response.
    */
  private def adminUpdateUserAttributes(
    request: AdminUpdateUserAttributesRequest
  )(implicit
    ec: ExecutionContext
  ): Future[Unit] = {
    for {
      cognitoResponse <- client
        .adminUpdateUserAttributes(request)
        .toScala
    } yield ()
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
