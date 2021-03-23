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
    email: String
  )(implicit
    ec: ExecutionContext
  ): Future[CognitoId]

  def resendUserInvite(
    email: String,
    cognitoId: CognitoId
  )(implicit
    ec: ExecutionContext
  ): Future[CognitoId]
}

object Cognito {

  def fromConfig(config: Config): Cognito = {

    val region: Region = config
      .as[Option[String]]("cognito.region")
      .map(Region.of(_))
      .getOrElse(Region.US_EAST_1)

    val userPoolId = config.as[String]("cognito.user_pool_id")

    val tokenPoolId = config.as[String]("cognito.token_pool_id")

    new Cognito(
      client = CognitoIdentityProviderAsyncClient
        .builder()
        .region(region)
        .httpClientBuilder(NettyNioAsyncHttpClient.builder())
        .build(),
      userPoolId = userPoolId,
      tokenPoolId = tokenPoolId
    )
  }
}

class Cognito(
  val client: CognitoIdentityProviderAsyncClient,
  userPoolId: String,
  tokenPoolId: String
) extends CognitoClient {

  def adminCreateUser(
    email: String
  )(implicit
    ec: ExecutionContext
  ): Future[CognitoId] = {

    val request = AdminCreateUserRequest
      .builder()
      .userPoolId(userPoolId)
      .username(email)
      .desiredDeliveryMediums(List(DeliveryMediumType.EMAIL).asJava)
      .build()

    for {
      cognitoResponse <- client
        .adminCreateUser(request)
        .toScala

      cognitoId <- parseCognitoId(cognitoResponse.user()) match {
        case Some(cognitoId) => Future.successful(cognitoId)
        case None =>
          Future.failed(NotFound("Could not parse Cognito ID from respoonse"))
      }
    } yield cognitoId
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
      .userPoolId(userPoolId)
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
