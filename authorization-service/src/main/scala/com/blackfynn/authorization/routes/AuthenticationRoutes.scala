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

package com.pennsieve.authorization.routes

import akka.http.scaladsl.model.headers.{ Authorization, HttpCookie }
import akka.http.scaladsl.model.{ HttpHeader, HttpResponse }
import akka.http.scaladsl.model.StatusCodes.{
  Accepted,
  BadRequest,
  Created,
  OK
}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{
  MalformedRequestContentRejection,
  RejectionHandler,
  Route
}

import cats.data.EitherT
import cats.implicits._

import com.typesafe.scalalogging.LazyLogging

import com.pennsieve.akka.http.RouteService
import com.pennsieve.authorization.Router.ResourceContainer
import com.pennsieve.authorization.utilities.exceptions._
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.dtos.{ Builders, UserDTO }
import com.pennsieve.traits.PostgresProfile.api._

import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

import net.ceedubs.ficus.Ficus._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

case class APILoginRequest(tokenId: String, secret: String)
object APILoginRequest {
  implicit val encoder: Encoder[APILoginRequest] =
    deriveEncoder[APILoginRequest]
  implicit val decoder: Decoder[APILoginRequest] =
    deriveDecoder[APILoginRequest]
}

case class APILoginResponse(
  session_token: String,
  organization: String,
  expires_in: Int
)
object APILoginResponse {
  implicit val encoder: Encoder[APILoginResponse] =
    deriveEncoder[APILoginResponse]
  implicit val decoder: Decoder[APILoginResponse] =
    deriveDecoder[APILoginResponse]
}

case class LoginRequest(email: String, password: String)
object LoginRequest {
  implicit val encoder: Encoder[LoginRequest] =
    deriveEncoder[LoginRequest]
  implicit val decoder: Decoder[LoginRequest] =
    deriveDecoder[LoginRequest]
}

case class LoginResponse(
  sessionToken: Option[String],
  organization: Option[String],
  profile: Option[UserDTO],
  message: String = "Welcome to Pennsieve"
)
object LoginResponse {
  implicit val encoder: Encoder[LoginResponse] =
    deriveEncoder[LoginResponse]
  implicit val decoder: Decoder[LoginResponse] =
    deriveDecoder[LoginResponse]
}

case class TemporaryLoginResponse(sessionToken: Option[String], message: String)
object TemporaryLoginResponse {
  implicit val encoder: Encoder[TemporaryLoginResponse] =
    deriveEncoder[TemporaryLoginResponse]
  implicit val decoder: Decoder[TemporaryLoginResponse] =
    deriveDecoder[TemporaryLoginResponse]
}

case class TwoFactorLoginRequest(token: String)
object TwoFactorLoginRequest {
  implicit val encoder: Encoder[TwoFactorLoginRequest] =
    deriveEncoder[TwoFactorLoginRequest]
  implicit val decoder: Decoder[TwoFactorLoginRequest] =
    deriveDecoder[TwoFactorLoginRequest]
}

class AuthenticationRoutes(
  implicit
  container: ResourceContainer,
  executionContext: ExecutionContext
) extends RouteService
    with LazyLogging {

  /*
   * To prevent unwanted request body parameters from being returned in an error
   * response e.g. a supplied password or API secret
   */
  def malformedRequestRejectionHandler =
    RejectionHandler
      .newBuilder()
      .handle {
        case _: MalformedRequestContentRejection =>
          complete(
            HttpResponse(
              BadRequest,
              entity = "The request content was malformed."
            )
          )
      }
      .result()

  val routes: Route = pathPrefix("authentication") {
    handleRejections(malformedRequestRejectionHandler) { login ~ apiLogin } ~ twoFactorLogin ~ logout
  }
  val sessionTokenName: String =
    container.config.as[String]("authentication.session_token")
  val parentDomain: String =
    container.config.as[String]("authentication.parent_domain")
  val apiSessionTimeout: Int =
    container.config.as[Int]("authentication.api_session_timeout")
  val badLoginLimit: Int =
    container.config.as[Int]("authentication.bad_login_limit")
  val sessionTimeout: Int =
    container.config.as[Int]("authentication.session_timeout")
  val temporarySessionTimeout: Int =
    container.config.as[Int]("authentication.temporary_session_timeout")

  def extractSessionToken: HttpHeader => Option[String] = {
    case authorization: Authorization => Some(authorization.credentials.token())
    case _ => None
  }

  def checkBadLoginCount(principal: String): Either[Throwable, Int] = {
    container.sessionManager
      .badLoginCount(principal)
      .asRight
      .filterOrElse(
        badLoginCount => badLoginCount < badLoginLimit,
        InvalidLoginAttemps
      )
  }

  def login: Route =
    (path("login") & post & entity(as[LoginRequest])) { body =>
      val result: EitherT[Future, Throwable, Either[
        TemporaryLoginResponse,
        LoginResponse
      ]] = for {
        user <- container.userManager
          .getByEmail(body.email)
          .leftMap[Throwable](_ => new UserNotFound(body.email))

        _ <- checkBadLoginCount(user.email).toEitherT[Future]

        _ <- container.sessionManager
          .validateSecret(user.nodeId, body.password, user.password)
          .leftMap[Throwable](_ => BadPassword)
          .toEitherT[Future]

        session <- if (user.hasTwoFactorConfigured) {
          container.sessionManager
            .generateTemporarySession(user, temporarySessionTimeout)
            .leftMap[Throwable](identity)
        } else {
          container.sessionManager
            .generateBrowserSession(user, sessionTimeout)
            .leftMap[Throwable](identity)
        }

        dto <- Builders
          .userDTO(user, storage = None)(
            container.organizationManager,
            container.pennsieveTermsOfServiceManager,
            container.customTermsOfServiceManager,
            executionContext
          )
          .leftMap[Throwable](identity)

      } yield {
        if (user.hasTwoFactorConfigured) {
          TemporaryLoginResponse(
            Some(session.uuid),
            "Please complete login with two factor authentication"
          ).asLeft
        } else {
          LoginResponse(
            Some(session.uuid),
            Some(session.organizationId),
            Some(dto)
          ).asRight
        }
      }

      onSuccess(result.value) {
        case Right(response) =>
          response match {
            case Right(login) => {
              setCookie(
                HttpCookie(
                  sessionTokenName,
                  value = login.sessionToken.get,
                  domain = Some(parentDomain)
                )
              ) {
                complete((OK, login))
              }
            }
            case Left(temporaryLogin) => complete((Accepted, temporaryLogin))
          }
        case Left(error) => complete(error.toResponse)
      }
    }

  def logout: Route =
    (path("logout") & post & headerValue(extractSessionToken)) { token =>
      val result: EitherT[Future, Throwable, Unit] = for {
        session <- container.sessionManager
          .get(token)
          .leftMap[Throwable](_ => new SessionTokenNotFound(token))
          .toEitherT[Future]
        _ <- session
          .user()(container.userManager, executionContext)
          .leftMap[Throwable](identity)
        _ <- container.sessionManager
          .remove(token)
          .leftMap[Throwable](identity)
          .toEitherT[Future]
      } yield ()

      onSuccess(result.value) {
        case Right(_) => {
          deleteCookie(sessionTokenName) {
            complete(OK)
          }
        }
        case Left(error) => complete(error.toResponse)
      }
    }

  def apiLogin: Route =
    (path("api" / "session") & post & entity(as[APILoginRequest])) { body =>
      val result: EitherT[Future, Throwable, APILoginResponse] = for {
        token <- container.tokenManager
          .get(body.tokenId)
          .leftMap[Throwable](_ => new APITokenNotFound(body.tokenId))

        _ <- checkBadLoginCount(body.tokenId).toEitherT[Future]

        _ <- container.sessionManager
          .validateSecret(token.token, body.secret, token.secret)
          .leftMap[Throwable](_ => BadSecret)
          .toEitherT[Future]

        session <- container.sessionManager
          .generateAPISession(token, apiSessionTimeout, container.tokenManager)
          .leftMap[Throwable](identity)
      } yield
        APILoginResponse(
          session.uuid,
          session.organizationId,
          apiSessionTimeout
        )

      onSuccess(result.value) {
        case Right(session) => complete((Created, session))
        case Left(error) => complete(error.toResponse)
      }
    }

  def twoFactorLogin: Route =
    (path("login" / "twofactor") & post & entity(as[TwoFactorLoginRequest]) & headerValue(
      extractSessionToken
    )) { (body, sessionToken) =>
      val result: EitherT[Future, Throwable, LoginResponse] = for {
        temporarySession <- container.sessionManager
          .get(sessionToken)
          .leftMap[Throwable](_ => new SessionTokenNotFound(sessionToken))
          .toEitherT[Future]

        user <- temporarySession
          .user()(container.userManager, executionContext)
          .leftMap[Throwable](identity)

        verification = container.authy.getTokens
          .verify(user.authyId, body.token)
        _ <- Right(())
          .ensure(new BadTwoFactorToken(verification.getError.getMessage))(
            _ => verification.isOk
          )
          .toEitherT[Future]

        _ <- container.sessionManager
          .remove(sessionToken)
          .leftMap[Throwable](identity)
          .toEitherT[Future]

        session <- container.sessionManager
          .generateBrowserSession(user)
          .leftMap[Throwable](identity)

        dto <- Builders
          .userDTO(user, storage = None)(
            container.organizationManager,
            container.pennsieveTermsOfServiceManager,
            container.customTermsOfServiceManager,
            executionContext
          )
          .leftMap[Throwable](identity)
      } yield
        LoginResponse(
          Some(session.uuid),
          Some(session.organizationId),
          Some(dto)
        )

      onSuccess(result.value) {
        case Right(session) => {
          setCookie(
            HttpCookie(
              sessionTokenName,
              value = session.sessionToken.get,
              domain = Some(parentDomain)
            )
          ) {
            complete((OK, session))
          }

        }
        case Left(error) => complete(error.toResponse)
      }
    }

}
