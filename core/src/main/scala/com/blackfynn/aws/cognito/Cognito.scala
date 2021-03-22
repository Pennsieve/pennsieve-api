// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.aws.cognito

import com.blackfynn.models.CognitoId
import com.blackfynn.domain.{ NotFound, PredicateError }
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
    } yield Future.successful(Unit)
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
    } yield Future.successful(Unit)
  }

  def getTokenPoolId(): String = {
    tokenPoolId
  }

  def getUserPoolId(): String = {
    userPoolId
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
