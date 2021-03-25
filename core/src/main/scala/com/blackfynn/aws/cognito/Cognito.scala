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
import com.pennsieve.domain.{ NotFound, PredicateError }
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
  def adminCreateUser(
    email: String,
    userPoolId: String
  )(implicit
    ec: ExecutionContext
  ): Future[CognitoId]

  def adminCreateToken(
    email: String,
    userPoolId: String
  )(implicit
    ec: ExecutionContext
  ): Future[CognitoId]

  def adminDeleteUser(
    email: String,
    userPoolId: String
  )(implicit
    ec: ExecutionContext
  ): Future[Unit]

  def adminSetUserPassword(
    username: String,
    password: String,
    userPoolId: String
  )(implicit
    ec: ExecutionContext
  ): Future[Unit]

  def getUserPoolId(): String

  def getTokenPoolId(): String

  def resendUserInvite(
    email: String,
    cognitoId: CognitoId
  )(implicit
    ec: ExecutionContext
  ): Future[CognitoId]
}

object Cognito {

  def apply(config: Config): Cognito = {

    val cognitoConfig = CognitoConfig(config)

    new Cognito(
      CognitoIdentityProviderAsyncClient
        .builder()
        .region(cognitoConfig.region)
        .httpClientBuilder(NettyNioAsyncHttpClient.builder())
        .build(),
      cognitoConfig
    )
  }
}

class Cognito(
  val client: CognitoIdentityProviderAsyncClient,
  cognitoConfig: CognitoConfig
) extends CognitoClient {

  def adminCreate(
    request: AdminCreateUserRequest
  )(implicit
    ec: ExecutionContext
  ): Future[CognitoId] = {
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

  def adminCreateUser(
    email: String,
    userPoolId: String
  )(implicit
    ec: ExecutionContext
  ): Future[CognitoId] = {
    val request = AdminCreateUserRequest
      .builder()
      .userPoolId(userPoolId)
      .username(email)
      .desiredDeliveryMediums(List(DeliveryMediumType.EMAIL).asJava)
      .build()
    adminCreate(request)
  }

  def adminCreateToken(
    username: String,
    userPoolId: String
  )(implicit
    ec: ExecutionContext
  ): Future[CognitoId] = {
    val request = AdminCreateUserRequest
      .builder()
      .userPoolId(userPoolId)
      .username(username)
      .build()
    adminCreate(request)
  }

  def adminSetUserPassword(
    username: String,
    password: String,
    userPoolId: String
  )(implicit
    ec: ExecutionContext
  ): Future[Unit] = {
    val request = AdminSetUserPasswordRequest
      .builder()
      .password(password)
      .permanent(true)
      .userPoolId(userPoolId)
      .username(username)
      .build()

    for {
      cognitoResponse <- client
        .adminSetUserPassword(request)
        .toScala

      // TODO(jesse): Check the status of the cognitoResponse? Return Failure for non
      // 200 codes.
      //
      // cognitoId <- parseCognitoId(cognitoResponse.user()) match {
      //   case Some(cognitoId) => Future.successful(cognitoId)
      //   case None =>
      //     Future.failed(NotFound("Could not parse Cognito ID from response"))
      // }
    } yield ()
  }

  def adminDeleteUser(
    email: String,
    userPoolId: String
  )(implicit
    ec: ExecutionContext
  ): Future[Unit] = {
    val request = AdminDeleteUserRequest
      .builder()
      .userPoolId(userPoolId)
      .username(email)
      .build()

    for {
      cognitoResponse <- client
        .adminDeleteUser(request)
        .toScala

      // TODO(jesse): Check the status of the cognitoResponse? Return Failure for non
      // 200 codes.
      //
      // cognitoId <- parseCognitoId(cognitoResponse.user()) match {
      //   case Some(cognitoId) => Future.successful(cognitoId)
      //   case None =>
      //     Future.failed(NotFound("Could not parse Cognito ID from response"))
      // }
    } yield ()
  }

  def getTokenPoolId(): String = {
    cognitoConfig.tokenPool.id
  }

  def getUserPoolId(): String = {
    cognitoConfig.userPool.id
  }

  def resendUserInvite(
    email: String,
    cognitoId: CognitoId
  )(implicit
    ec: ExecutionContext
  ): Future[CognitoId] = {

    // TODO: sanity check if user already exists before sending?
    val resendRequest = AdminCreateUserRequest
      .builder()
      .userPoolId(cognitoConfig.userPool.id)
      .username(email)
      .desiredDeliveryMediums(List(DeliveryMediumType.EMAIL).asJava)
      .messageAction(MessageActionType.RESEND)
      .build()

    for {
      response <- client
        .adminCreateUser(resendRequest)
        .toScala

      _ <- parseCognitoId(response.user()) match {
        case Some(responseId) if responseId != cognitoId =>
          Future.failed(
            PredicateError(
              s"Mismatched Cognito ID: expected $cognitoId but got $responseId"
            )
          )
        case Some(_) => Future.successful(())
        case None =>
          Future.failed(NotFound("Could not parse Cognito ID from response"))
      }

    } yield cognitoId
  }

  /**
    * Parse Cognito ID from the "sub" user attribute
    */
  def parseCognitoId(user: UserType): Option[CognitoId] =
    user
      .attributes()
      .asScala
      .find(_.name() == "sub")
      .map(_.value())
      .flatMap(s => Either.catchNonFatal(UUID.fromString(s)).toOption)
      .map(CognitoId(_))
}
