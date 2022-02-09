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

package com.pennsieve.api

import java.time.ZonedDateTime
import cats.data._
import cats.implicits._
import com.pennsieve.api.AuthenticatedController.{getOrganizationId, getOrganizationIntId}
import com.pennsieve.audit.middleware.{AuditLogger, TraceId}
import com.pennsieve.auth.middleware.Jwt.Claim
import com.pennsieve.auth.middleware.{EncryptionKeyId, Jwt, OrganizationId, OrganizationNodeId, ServiceClaim, UserClaim, Validator}
import com.pennsieve.core.utilities.{FutureEitherHelpers, JwtAuthenticator}
import com.pennsieve.db._
import com.pennsieve.domain.{CoreError, InvalidJWT, MissingTraceId}
import com.pennsieve.helpers.APIContainers.{InsecureAPIContainer, SecureAPIContainer, SecureContainerBuilderType}
import com.pennsieve.helpers.either.EitherErrorHandler.implicits._
import com.pennsieve.helpers.either.EitherTErrorHandler.implicits._
import com.pennsieve.helpers.{ErrorLoggingSupport, ModelSerializers, ParamsSupport}
import com.pennsieve.models._
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.web.Settings
import com.typesafe.scalalogging.{LazyLogging, Logger}
import enumeratum.Json4s

import javax.servlet.http.HttpServletRequest
import org.json4s._
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.servlet.ServletBase
import org.scalatra.swagger.Swagger
import shapeless.syntax.inject._
import slick.dbio.{DBIOAction, Effect, NoStream}
import slick.sql.FixedSqlStreamingAction

import scala.concurrent._
import scala.concurrent.duration._
import scala.util.Try
import scala.util.matching.Regex

object AuthenticatedController {
  val bearerScheme: String = "bearer"
  val authorizationHeaderKey: String = "Authorization"
  val organizationHeaderKey = "X-ORGANIZATION-ID"
  val organizationIntHeaderKey = "X-ORGANIZATION-INT-ID"

  def getBearerToken(
    request: HttpServletRequest
  ): Either[ActionResult, String] = {
    val header = Option(request.getHeader(authorizationHeaderKey))

    header
      .map(_.split(" ", 2).toList)
      .filter(_.headOption.map(_.toLowerCase) == bearerScheme.some)
      .flatMap(_.lastOption)
      .orUnauthorized()
  }

  def getOrganizationId(
    claim: Claim,
    request: HttpServletRequest
  ): Option[String] =
    if (Validator.isServiceClaim(claim))
      Option(request.getHeader(organizationHeaderKey))
    else None

  def getOrganizationIntId(
    claim: Claim,
    request: HttpServletRequest
  ): Option[Int] =
    if (Validator.isServiceClaim(claim))
      Try(request.getHeader(organizationIntHeaderKey).toInt).toOption
    else None
}

/**
  * Base trait for all Pennsieve API endpoints
  *
  * Concrete controller instances need to mix in either `ScalatraServlet`
  * (default and preferred) or `ScalatraFilter` (if multiple classes need to
  * mount in the same URL namespace).
  *
  * See https://scalatra.org/getting-started/project-structure.html ("ScalatraServlet vs. ScalatraFilter")
  */
trait AuthenticatedController
    extends JacksonJsonSupport
    with PennsieveSwaggerSupport
    with ErrorLoggingSupport
    with ParamsSupport
    with FutureSupport
    with LazyLogging {

  self: ServletBase =>

  val insecureContainer: InsecureAPIContainer
  val secureContainerBuilder: SecureContainerBuilderType

  implicit val swagger: Swagger

  implicit val ec: ExecutionContext = executor
  override implicit val logger: Logger = logger

  protected implicit def jsonFormats: Formats =
    DefaultFormats ++ ModelSerializers.serializers

  implicit val jwtConfig: Jwt.Config = new Jwt.Config {
    val key: String = Settings.jwtKey
  }

  protected val applicationDescription: String = "Core API"

  before() {
    contentType = formats("json")
    AuthenticatedController.getBearerToken(request) match {
      case Right(bearerToken) =>
        Jwt
          .parseClaim(Jwt.Token(bearerToken))
          .toOption
          .filter(_.isValid)
          .orUnauthorized("Invalid authorization claim.")
          .recover {
            case error => halt(error.status, error.body)
          }
      case Left(error) => halt(error.status, error.body)
    }
  }

  /**
    * Parse a JWT claim out of a request.
    *
    * @param request
    * @return
    */
  private def getJwtClaim(
    request: HttpServletRequest
  ): Either[ActionResult, Jwt.Claim] =
    for {
      bearerToken <- AuthenticatedController
        .getBearerToken(request)
        .leftMap[ActionResult](e => Unauthorized(e.body))
      claim <- Jwt
        .parseClaim(Jwt.Token(bearerToken))
        .leftMap[ActionResult](
          _ => Unauthorized(InvalidJWT(bearerToken).getMessage)
        )
      _ <- if (claim.isValid) {
        Right(())
      } else {
        Left(Unauthorized(Error("Invalid authorization.")))
      }
    } yield claim

  /**
    * Validate if the given request has access to the specified organization.
    *
    * @param request
    * @param organizationId
    * @return
    */
  private def validateJwt(
    request: HttpServletRequest,
    organizationId: Int
  ): Either[ActionResult, Unit] = {
    for {
      claim <- getJwtClaim(request)
      result <- (
        if (Validator.hasOrganizationAccess(
            claim,
            OrganizationId(organizationId)
          )) {
          Some(())
        } else {
          None
        }
      ).orForbidden()
    } yield result
  }

  /**
    * This function wraps the functionality for getting the request's organization.
    * Services need to act on behalf of another user's organization.
    * This allows them to specify the organization via an X-ORGANIZATION-ID header.
    *
    * @param user
    * @param request
    * @return
    */
  private def getOrganizationFromHeader(
    claim: Claim
  )(implicit
    request: HttpServletRequest
  ): EitherT[Future, ActionResult, Organization] =
    if (Validator.isServiceClaim(claim)) {
      getOrganizationId(claim, request) match {
        case Some(organizationNodeId) =>
          insecureContainer.organizationManager
            .getByNodeId(organizationNodeId)
            .orError
        case None =>
          getOrganizationIntId(claim, request) match {
            case Some(organizationIntId) =>
              insecureContainer.organizationManager
                .get(organizationIntId)
                .orError
            case None =>
              EitherT.left(
                Future(
                  BadRequest(
                    s"service claims are required to specify either an ${AuthenticatedController.organizationHeaderKey} "
                      + s"or an ${AuthenticatedController.organizationIntHeaderKey} header."
                  )
                )
              )
          }
      }
    } else {
      EitherT.left(
        Future(
          InternalServerError(
            "A non-service claim tried to use the getOrganizationFromHeader function"
          )
        )
      )
    }

  /**
    * Get a secure container instance from a JWT.
    *
    * - If given a user claim, verify the user is in the correct role to interact with the organization.
    *
    * - If given a service claim, verify the `X-ORGANIZATION-(INT-)ID` header is present.
    *
    * @param request
    * @return
    */
  def getSecureContainer(
  )(implicit
    request: HttpServletRequest
  ): EitherT[Future, ActionResult, SecureAPIContainer] = {
    for {
      claim <- getJwtClaim(request).toEitherT[Future]

      secureContainer <- {
        claim.content match {
          // case: User
          //   Attempt to extract out a (user, organization) and use that to build a secure container:
          case _: UserClaim =>
            for {
              context <- {
                JwtAuthenticator
                  .userContext(insecureContainer, claim)
                  .coreErrorToActionResult()
              }
            } yield secureContainerBuilder(context.user, context.organization)

          // case: Service
          //   Check for the existence of an `X-ORGANIZATION-(INT)-ID` header that defines the organizational context
          //   the service claim will operate on.
          case _: ServiceClaim =>
            for {
              organization <- {
                getOrganizationFromHeader(claim)(request)
              }

              _ <- validateJwt(request, organization.id).toEitherT[Future]

            } yield secureContainerBuilder(User.serviceUser, organization)
        }
      }

    } yield secureContainer
  }

  /**
    * Return the unique Pennsieve trace ID embedded in the request if present.
    *
    * @param request
    * @return
    */
  def getTraceId(
    request: HttpServletRequest
  ): EitherT[Future, ActionResult, TraceId] = {
    Option(request.getHeader(AuditLogger.TRACE_ID_HEADER))
      .map(TraceId(_))
      .toRight(MissingTraceId: CoreError)
      .toEitherT[Future]
      .coreErrorToActionResult
  }

  def tryGetTraceId(
    request: HttpServletRequest
  ): EitherT[Future, ActionResult, Option[TraceId]] =
    EitherT.rightT[Future, ActionResult](
      Option(request.getHeader(AuditLogger.TRACE_ID_HEADER)).map(TraceId(_))
    )

  def getCognitoId(request: HttpServletRequest): Option[CognitoId] = {
    getJwtClaim(request).toOption.flatMap { claim =>
      claim.content match {
        case UserClaim(_, _, cognito, _) => cognito.map(_.id)
        case _ => None
      }
    }
  }

  def isBrowserSession(request: HttpServletRequest): Boolean = {
    getJwtClaim(request).exists { claim =>
      claim.content match {
        case UserClaim(_, _, Some(cognito), _) => cognito.isBrowser
        case _ => false
      }
    }
  }

  def isServiceClaim(request: HttpServletRequest): Boolean =
    getJwtClaim(request).exists(Validator.isServiceClaim)

}
