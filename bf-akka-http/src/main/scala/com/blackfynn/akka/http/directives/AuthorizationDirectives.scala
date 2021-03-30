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

import java.time.ZonedDateTime
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.directives.Credentials
import cats.data.EitherT
import cats.implicits._
import com.pennsieve.aws.cognito.CognitoJWTAuthenticator
import com.pennsieve.auth.middleware.{ Jwt, UserClaim }
import com.pennsieve.aws.cognito.CognitoConfig
import com.pennsieve.core.utilities.{
  FutureEitherHelpers,
  JwtAuthenticator,
  OrganizationManagerContainer,
  SessionManagerContainer,
  TokenManagerContainer,
  UserAuthContext,
  UserManagerContainer
}
import com.pennsieve.domain.{ CoreError, Error, ThrowableError }
import com.pennsieve.domain.Sessions.{
  APISession,
  BrowserSession,
  Session,
  TemporarySession
}
import com.pennsieve.models.{ CognitoId, Organization, User }
import com.pennsieve.utilities.Container
import net.ceedubs.ficus.Ficus._

import scala.concurrent.{ ExecutionContext, Future }

object AuthorizationDirectives {

  type AuthorizationContainer = Container
    with SessionManagerContainer
    with TokenManagerContainer
    with OrganizationManagerContainer

  type JwtAuthContainer = Container
    with OrganizationManagerContainer
    with UserManagerContainer

  /**
    * Given a string, attempt to resolve the string as a session, returning the associated session, user, and organization.
    *
    * @param container
    * @param token
    * @param ec
    * @return
    */
  private def authenticateFromSession(
    container: AuthorizationContainer,
    token: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Session, User, Organization)] = {
    for {
      session <- container.sessionManager.get(token).toEitherT[Future]
      _ <- validateSession(container, session)
      user <- container.userManager.getByNodeId(session.userId)
      organization <- container.organizationManager.getByNodeId(
        session.organizationId
      )
    } yield (session, user, organization)
  }

  /**
    * Given a string, attempt to resolve the string as a session, returning the associated user auth context.
    *
    * @param container
    * @param token
    * @param ec
    * @return
    */
  private def extractContextFromSession(
    container: AuthorizationContainer,
    token: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, UserAuthContext] = {
    authenticateFromSession(container, token).map {
      case (session, user, organization) =>
        UserAuthContext(
          user = user,
          organization = organization,
          cognitoId = None
        )
    }
  }

  /**
    * Check the validity of a session.
    *
    * @param session
    * @return
    */
  private def validateSession(
    container: AuthorizationContainer,
    session: Session
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] = {
    session.`type` match {
      case TemporarySession => FutureEitherHelpers.unit

      case BrowserSession =>
        for {
          _ <- session
            .refresh(
              container.config
                .as[Option[Int]]("authentication.session_timeout")
                .getOrElse(3600) // default to one hour
            )(container.redisManager)
            .toEitherT[Future]
        } yield ()

      case APISession(token: String) =>
        container.tokenManager
          .get(token)
          .map { t =>
            Future {
              container.tokenManager.update(
                t.copy(lastUsed = Some(ZonedDateTime.now))
              )
            }
            ()
          }
          .leftMap[CoreError] { _ =>
            container.sessionManager.remove(session)
            Error("Invalid API Session")
          }
    }
  }

  /**
    * Authenticate the request by interpreting the `Authorization` header token first as a JWT, or failing that,
    * as an API session.
    *
    * @param container
    * @param realm
    * @param ec
    * @return
    */
  private def authenticateFromJwtOrSession(
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
              JwtAuthenticator
                .userContextFromToken(container, Jwt.Token(token)) recoverWith {
                case _ => extractContextFromSession(container, token)
              }
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
      cognitoId <- CognitoJWTAuthenticator
        .validateJwt(token)
        .map(_.id)
        .leftMap(ThrowableError(_))
        .toEitherT[Future]

      authContext <- cognitoId match {
        case id: CognitoId.UserPoolId =>
          for {
            // TODO: single query for all this
            user <- container.userManager.getByCognitoId(id)
            // TODO: Better tracking of organizations w/ sessions etc
            preferredOrganizationId <- user._1.preferredOrganizationId match {
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
          } yield UserAuthContext(user._1, organization, None)

        case id: CognitoId.TokenPoolId =>
          for {
            // TODO: single query for all this
            token <- container.tokenManager.getByCognitoId(id)
            user <- container.userManager.get(token.userId)
            organization <- container.organizationManager.get(
              token.organizationId
            )
          } yield UserAuthContext(user, organization, None)

      }
    } yield authContext

  }

  /**
    * Authenticate the request by interpreting the `Authorization` header token as an API session.
    *
    * @param container
    * @param realm
    * @param ec
    * @return
    */
  def session(
    container: AuthorizationContainer,
    realm: String
  )(implicit
    ec: ExecutionContext
  ): Directive1[(Session, User, Organization)] = {
    authenticateOAuth2Async(realm = realm, authenticator = {
      case Credentials.Provided(token) =>
        authenticateFromSession(container, token).value.map(_.toOption)
      case _ => Future.successful(None)
    })
  }

  /**
    * Attempt to authenticate a Pennsieve admin user with a JWT or API session, returning a context object.
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
    authenticateFromJwtOrSession(container, realm).flatMap { context =>
      authorize(context.user.isSuperAdmin) & provide(context)
    }
  }

  /**
    * Attempt to authenticate a regular Pennsieve user with a JWT or API session, returning a context object.
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
    authenticateFromJwtOrSession(container, realm).flatMap(provide)

  /**
    * Attempt to authenticate a regular Pennsieve user with a JWT, returning a context object.
    *
    * @param container
    * @param realm
    * @param config
    * @param ec
    * @return
    */
  def jwtUser(
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
