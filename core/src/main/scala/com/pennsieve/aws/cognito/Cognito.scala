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
import com.pennsieve.domain.{ Error, NotFound, PredicateError }
import com.pennsieve.models.TokenSecret
import com.pennsieve.models.{ CognitoId, Organization }
import com.typesafe.config.Config
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderAsyncClient
import software.amazon.awssdk.services.cognitoidentityprovider.model.{
  AdminCreateUserRequest,
  AdminDeleteUserAttributesRequest,
  AdminDeleteUserRequest,
  AdminDisableProviderForUserRequest,
  AdminDisableUserRequest,
  AdminGetUserRequest,
  AdminSetUserPasswordRequest,
  AdminUpdateUserAttributesRequest,
  AttributeType,
  CognitoIdentityProviderResponse,
  DeliveryMediumType,
  InitiateAuthRequest,
  MessageActionType,
  ProviderUserIdentifierType,
  UserType,
  UsernameExistsException
}

import java.util.UUID
import scala.jdk.CollectionConverters._
import scala.compat.java8.FutureConverters._
import scala.concurrent.{ ExecutionContext, Future }
import scala.compat.java8.OptionConverters._

// Helper to unwrap exception so we can match on cause
object CausedBy {
  def unapply(e: Throwable): Option[Throwable] = Option(e.getCause)
}

trait CognitoClient {

  def inviteUser(
    email: Email,
    suppressEmail: Boolean = false,
    verifyEmail: Boolean = true,
    invitePath: String = "invite",
    customMessage: Option[String] = None
  )(implicit
    ec: ExecutionContext
  ): Future[CognitoId.UserPoolId]

  def getCognitoId(
    username: String
  )(implicit
    ec: ExecutionContext
  ): Future[String]

  def resendUserInvite(
    email: Email,
    cognitoId: CognitoId.UserPoolId
  )(implicit
    ec: ExecutionContext
  ): Future[CognitoId.UserPoolId]

  def createClientToken(
    token: String,
    secret: TokenSecret,
    organization: Organization
  )(implicit
    ec: ExecutionContext
  ): Future[CognitoId.TokenPoolId]

  def deleteClientToken(
    token: String
  )(implicit
    ec: ExecutionContext
  ): Future[Unit]

  def hasExternalUserLink(
    username: String,
    providerName: String
  )(implicit
    ec: ExecutionContext
  ): Future[Boolean]

  def unlinkExternalUser(
    providerName: String,
    attributeName: String,
    attributeValue: String
  )(implicit
    ec: ExecutionContext
  ): Future[Unit]

  def deleteUser(
    username: String
  )(implicit
    ec: ExecutionContext
  ): Future[Unit]

  def disableUser(
    username: String
  )(implicit
    ec: ExecutionContext
  ): Future[Unit]

  def deleteUserAttributes(
    username: String,
    attributeNames: List[String]
  )(implicit
    ec: ExecutionContext
  ): Future[Unit]

  def updateUserAttribute(
    username: String,
    attributeName: String,
    attributeValue: String
  )(implicit
    ec: ExecutionContext
  ): Future[Boolean]

  def updateUserAttributes(
    username: String,
    attributes: Map[String, String]
  )(implicit
    ec: ExecutionContext
  ): Future[Boolean]

  def authenticateUser(
    username: String,
    password: String
  )(implicit
    ec: ExecutionContext
  ): Future[Boolean]

  def setUserPassword(
    username: String,
    password: String
  )(implicit
    ec: ExecutionContext
  ): Future[Boolean]
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

  def generatePassword(): String =
    UUID.randomUUID().toString() + scala.util.Random
      .shuffle(('A' to 'Z').toList)
      .head
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
    invitePath: String = "invite",
    customMessage: Option[String] = None
  )(implicit
    ec: ExecutionContext
  ): Future[CognitoId.UserPoolId] = {
    val builder = AdminCreateUserRequest
      .builder()
      .userPoolId(cognitoConfig.userPool.id)
      .username(email.address)
      .temporaryPassword(Cognito.generatePassword())
      .userAttributes(
        List(
          AttributeType.builder().name("email").value(email.address).build(),
          AttributeType
            .builder()
            .name("custom:invite_path")
            .value(invitePath)
            .build(),
          AttributeType
            .builder()
            .name("email_verified")
            .value(verifyEmail.toString())
            .build()
        ).asJava
      )
      .clientMetadata(
        Map("customMessage" -> customMessage.getOrElse("")).asJava
      )
      .desiredDeliveryMediums(List(DeliveryMediumType.EMAIL).asJava)

    val request =
      if (suppressEmail)
        builder.messageAction(MessageActionType.SUPPRESS).build()
      else builder.build()

    adminCreateUser(request).map(CognitoId.UserPoolId(_))
  }

  def getCognitoId(
    username: String
  )(implicit
    ec: ExecutionContext
  ): Future[String] = {
    val request = AdminGetUserRequest
      .builder()
      .userPoolId(cognitoConfig.userPool.id)
      .username(username)
      .build()

    for {
      result <- client
        .adminGetUser(request)
        .toScala
        .map(response => response.username())
    } yield result
  }

  /**
    * The client token pool is configured to only allow username + password
    * auth, and users cannot reset passwords.
    */
  def createClientToken(
    token: String,
    secret: TokenSecret,
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

  def hasExternalUserLink(
    username: String,
    providerName: String
  )(implicit
    ec: ExecutionContext
  ): Future[Boolean] = {
    // lookup user
    val request = AdminGetUserRequest
      .builder()
      .userPoolId(cognitoConfig.userPool.id)
      .username(username)
      .build()

    for {
      attributes <- client
        .adminGetUser(request)
        .toScala
        .map(response => response.userAttributes())

      // TODO: if there is an "identities" entry, we will need to filter on `providerName`
      // TODO: this will be imperative once we support multiple Identity Providers
      // TODO: for now, we can determine by the presence of a non-empty value (it will be ORCID)
      result = attributes.asScala.toList
        .filter(_.name.equals("identities"))
        .map(!_.value.equals("[]"))
        .foldLeft(false)(_ || _)

    } yield result
  }

  /**
    * Unlink an external Identity Provider (Idp) account from a Cognito user
    *
    * @param providerName
    * @param attributeName
    * @param attributeValue
    */
  def unlinkExternalUser(
    providerName: String,
    attributeName: String,
    attributeValue: String
  )(implicit
    ec: ExecutionContext
  ): Future[Unit] = {
    val request = AdminDisableProviderForUserRequest
      .builder()
      .userPoolId(cognitoConfig.userPool.id)
      .user(
        ProviderUserIdentifierType
          .builder()
          .providerName(providerName)
          .providerAttributeName(attributeName)
          .providerAttributeValue(attributeValue)
          .build()
      )
      .build()

    client
      .adminDisableProviderForUser(request)
      .toScala
      .map(_ => ())
  }

  /**
    * Delete a user from the Cognito User Pool
    *
    * @param username
    * @param ec
    * @return
    */
  def deleteUser(
    username: String
  )(implicit
    ec: ExecutionContext
  ): Future[Unit] = {
    val request = AdminDeleteUserRequest
      .builder()
      .userPoolId(cognitoConfig.userPool.id)
      .username(username)
      .build()

    client
      .adminDeleteUser(request)
      .toScala
      .map(_ => ())
  }

  def disableUser(
    username: String
  )(implicit
    ec: ExecutionContext
  ): Future[Unit] = {
    val request = AdminDisableUserRequest
      .builder()
      .userPoolId(cognitoConfig.userPool.id)
      .username(username)
      .build()

    client
      .adminDisableUser(request)
      .toScala
      .map(_ => ())

  }

  /**
    * Deletes an attribute from a Cognito user
    *
    * @param username
    * @param attributeName
    * @param ec
    * @return
    */
  def deleteUserAttributes(
    username: String,
    attributeNames: List[String]
  )(implicit
    ec: ExecutionContext
  ): Future[Unit] = {
    val request = AdminDeleteUserAttributesRequest
      .builder()
      .userPoolId(cognitoConfig.userPool.id)
      .username(username)
      .userAttributeNames(attributeNames.asJava)
      .build()

    client
      .adminDeleteUserAttributes(request)
      .toScala
      .map(_ => ())
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
        .recoverWith {
          // The exception we want to match on is wrapped in a CompletionException
          case CausedBy(ex: UsernameExistsException) =>
            Future.failed(com.pennsieve.domain.UsernameExistsError(ex))
        }

      cognitoId <- parseCognitoId(cognitoResponse.user()) match {
        case Some(cognitoId) => Future.successful(cognitoId)
        case None =>
          Future.failed(NotFound("Could not parse Cognito ID from response"))
      }
    } yield cognitoId
  }

  def updateUserAttribute(
    username: String,
    attributeName: String,
    attributeValue: String
  )(implicit
    ec: ExecutionContext
  ): Future[Boolean] = {
    val request = AdminUpdateUserAttributesRequest
      .builder()
      .userPoolId(cognitoConfig.userPool.id)
      .username(username)
      .userAttributes(
        AttributeType
          .builder()
          .name(attributeName)
          .value(attributeValue)
          .build()
      )
      .build()

    for {
      cognitoResponse <- client
        .adminUpdateUserAttributes(request)
        .toScala

      response <- cognitoResponse.sdkHttpResponse().statusCode() match {
        case 200 => Future.successful(true)
        case 400 => Future.failed(Error(extractErrorResponse(cognitoResponse)))
      }
    } yield response
  }

  def updateUserAttributes(
    username: String,
    attributes: Map[String, String]
  )(implicit
    ec: ExecutionContext
  ): Future[Boolean] = {
    val userAttributes = attributes
      .map {
        case (name, value) =>
          AttributeType
            .builder()
            .name(name)
            .value(value)
            .build()
      }
      .toSeq
      .asJava

    val request = AdminUpdateUserAttributesRequest
      .builder()
      .userPoolId(cognitoConfig.userPool.id)
      .username(username)
      .userAttributes(userAttributes)
      .build()

    for {
      cognitoResponse <- client
        .adminUpdateUserAttributes(request)
        .toScala

      response <- cognitoResponse.sdkHttpResponse().statusCode() match {
        case 200 => Future.successful(true)
        case 400 => Future.failed(Error(extractErrorResponse(cognitoResponse)))
      }
    } yield response
  }

  def authenticateUser(
    username: String,
    password: String
  )(implicit
    ec: ExecutionContext
  ): Future[Boolean] = {
    val request = InitiateAuthRequest
      .builder()
      .clientId(cognitoConfig.userPool.appClientId)
      .authFlow("USER_PASSWORD_AUTH")
      .authParameters(
        Map("USERNAME" -> username, "PASSWORD" -> password).asJava
      )
      .build

    for {
      response <- client.initiateAuth(request).toScala
      result <- response.sdkHttpResponse().statusCode() match {
        case 200 => Future.successful(true)
        case _ => Future.failed(Error(extractErrorResponse(response)))
      }
    } yield result
  }

  def setUserPassword(
    username: String,
    password: String
  )(implicit
    ec: ExecutionContext
  ): Future[Boolean] = {
    val request = AdminSetUserPasswordRequest
      .builder()
      .userPoolId(cognitoConfig.userPool.id)
      .username(username)
      .password(password)
      .permanent(true)
      .build()

    for {
      response <- client.adminSetUserPassword(request).toScala

      result <- response.sdkHttpResponse().statusCode() match {
        case 200 => Future.successful(true)
        case _ => Future.failed(Error(extractErrorResponse(response)))
      }
    } yield result
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

  private def extractErrorResponse(
    cognitoResponse: CognitoIdentityProviderResponse
  ): String =
    cognitoResponse.sdkHttpResponse().statusText().asScala match {
      case Some(message) => message
      case None => "An error occurred"
    }
}
