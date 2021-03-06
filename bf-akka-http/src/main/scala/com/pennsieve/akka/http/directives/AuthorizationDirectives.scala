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

package com.pennsieve.akka.http.directives

import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.directives.Credentials
import cats.data.EitherT
import cats.implicits._
import java.time.{ Instant, ZonedDateTime }

import com.pennsieve.aws.cognito.CognitoJWTAuthenticator
import com.pennsieve.auth.middleware.{ Jwt, UserClaim }
import com.pennsieve.aws.cognito.CognitoConfig
import com.pennsieve.core.utilities.{
  FutureEitherHelpers,
  JwtAuthenticator,
  OrganizationManagerContainer,
  TokenManagerContainer,
  UserAuthContext,
  UserManagerContainer
}
import com.pennsieve.domain.{ CoreError, Error, ThrowableError }
import com.pennsieve.models.{ CognitoId, Organization, User }
import com.pennsieve.utilities.Container
import net.ceedubs.ficus.Ficus._

import scala.concurrent.{ ExecutionContext, Future }

object AuthorizationDirectives {

  type AuthorizationContainer = Container
    with UserManagerContainer
    with TokenManagerContainer
    with OrganizationManagerContainer

  type JwtAuthContainer = Container
    with OrganizationManagerContainer
    with UserManagerContainer

  /**
    * Authenticate the request by interpreting the `Authorization` header token
    * first as a Cognito JWT, or failing that, an internal JWT
    *
    * @param container
    * @param realm
    * @param ec
    * @return
    */
  private def authenticateFromJwt(
    container: AuthorizationContainer,
    realm: String
  )(implicit
    config: Jwt.Config,
    cognitoConfig: CognitoConfig,
    ec: ExecutionContext
  ): Directive1[UserAuthContext] = {

    authenticateOAuth2Async(
      realm = realm,
      authenticator = {
        case Credentials.Provided(token) =>
          (userContextFromCognitoJwt(container, token) recoverWith {
            case _ =>
              JwtAuthenticator.userContextFromToken(container, Jwt.Token(token))
          }).value.map(_.toOption)
        case _ => Future.successful(None)
      }
    )
  }

  def userContextFromCognitoJwt(
    container: AuthorizationContainer,
    token: String
  )(implicit
    config: CognitoConfig,
    ec: ExecutionContext
  ): EitherT[Future, CoreError, UserAuthContext] = {
    for {
      cognitoPayload <- CognitoJWTAuthenticator
        .validateJwt(token)
        .leftMap(ThrowableError(_))
        .toEitherT[Future]

      cognitoId = cognitoPayload.id
      authContext <- cognitoId match {
        case id: CognitoId.UserPoolId =>
          for {
            // TODO: single query for all this
            user <- container.userManager.getByCognitoId(id)
            // TODO: Better tracking of organizations w/ sessions etc
            preferredOrganizationId <- user.preferredOrganizationId match {
              case Some(id) => EitherT.rightT[Future, CoreError](id)
              case None =>
                EitherT.leftT[Future, Int](
                  com.pennsieve.domain
                    .NotFound("User has no preferred organzation.")
                )
            }
            organization <- container.organizationManager.get(
              preferredOrganizationId
            )
          } yield UserAuthContext(user, organization, Some(cognitoPayload))

        case id: CognitoId.TokenPoolId =>
          for {
            // TODO: single query for all this
            token <- container.tokenManager.getByCognitoId(id)
            user <- container.userManager.get(token.userId)
            organization <- container.organizationManager.get(
              token.organizationId
            )
          } yield UserAuthContext(user, organization, Some(cognitoPayload))

      }
    } yield authContext

  }

  /**
    * Attempt to authenticate an admin user with a Pennsieve JWT or Cognito JWT , returning a context object.
    *
    * @param container
    * @param realm
    * @param config
    * @param ec
    * @return
    */
  def admin(
    container: AuthorizationContainer,
    realm: String
  )(implicit
    config: Jwt.Config,
    cognitoConfig: CognitoConfig,
    ec: ExecutionContext
  ): Directive1[UserAuthContext] = {
    authenticateFromJwt(container, realm).flatMap { context =>
      authorize(context.user.isSuperAdmin) & provide(context)
    }
  }

  /**
    * Attempt to authenticate a regular user with a Pennsieve JWT or Cognito JWT, returning a context object.
    *
    * @param container
    * @param realm
    * @param config
    * @param ec
    * @return
    */
  def user(
    container: AuthorizationContainer,
    realm: String
  )(implicit
    config: Jwt.Config,
    cognitoConfig: CognitoConfig,
    ec: ExecutionContext
  ): Directive1[UserAuthContext] =
    authenticateFromJwt(container, realm).flatMap(provide)

  /**
    * Attempt to authenticate a regular Pennsieve user with an internal Pennsieve JWT, returning a context object.
    *
    * @param container
    * @param realm
    * @param config
    * @param ec
    * @return
    */
  def internalJwtUser(
    container: JwtAuthContainer,
    realm: String
  )(implicit
    config: Jwt.Config,
    ec: ExecutionContext
  ): Directive1[UserAuthContext] =
    authenticateOAuth2Async(
      realm = realm,
      authenticator = {
        case Credentials.Provided(token) =>
          JwtAuthenticator
            .userContextFromToken(container, Jwt.Token(token))
            .value
            .map(_.toOption)
        case _ => Future.successful(None)
      }
    ).flatMap(provide)
}
