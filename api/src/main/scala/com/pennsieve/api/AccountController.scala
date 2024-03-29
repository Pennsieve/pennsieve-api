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

import cats.data._
import cats.implicits._
import com.blackfynn.clients.AntiSpamChallengeClient
import com.pennsieve.aws.cognito.{
  CognitoClient,
  CognitoConfig,
  CognitoJWTAuthenticator
}
import com.pennsieve.aws.email.Email
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.domain.{
  CoreError,
  InvalidChallengeResponseError,
  ThrowableError
}
import com.pennsieve.dtos.{ Builders, UserDTO }
import com.pennsieve.helpers.APIContainers.InsecureAPIContainer
import com.pennsieve.helpers.ResultHandlers.OkResult
import com.pennsieve.helpers._
import com.pennsieve.helpers.either.EitherTErrorHandler.implicits._
import com.pennsieve.models.DBPermission.Guest
import com.pennsieve.models.Degree
import org.json4s._
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder
import org.scalatra.swagger._
import com.typesafe.scalalogging.{ LazyLogging, Logger }

import javax.servlet.http.HttpServletRequest
import scala.concurrent.{ ExecutionContext, Future }

case class CreateUserRequest(
  firstName: String,
  middleInitial: Option[String],
  lastName: String,
  degree: Option[Degree],
  title: String
)

case class CreateUserWithRecaptchaRequest(
  firstName: String,
  middleInitial: Option[String],
  lastName: String,
  email: String,
  degree: Option[Degree],
  title: Option[String],
  recaptchaToken: String
)

case class CreateUserResponse(orgIds: Set[String], profile: UserDTO)

class AccountController(
  val insecureContainer: InsecureAPIContainer,
  cognitoConfig: CognitoConfig,
  cognitoClient: CognitoClient,
  antiSpamChallengeClient: AntiSpamChallengeClient,
  asyncExecutor: ExecutionContext
)(implicit
  val swagger: Swagger
) extends ScalatraServlet
    with ErrorLoggingSupport
    with JacksonJsonSupport
    with ParamsSupport
    with PennsieveSwaggerSupport
    with FutureSupport
    with LazyLogging {

  override val pennsieveSwaggerTag = "Account"

  protected implicit def executor: ExecutionContext = asyncExecutor
  override implicit lazy val logger: Logger = Logger("com.pennsieve")

  protected implicit val jsonFormats
    : Formats = DefaultFormats ++ ModelSerializers.serializers

  protected val applicationDescription: String = "Core API"

  before() {
    contentType = formats("json")
  }

  //TODO The comments below refer to Scalatra 2.6.x and are out of date as of 2.7.x.
  // However, the underlying concern about logging passwords on parse failures still seems to be an issue in 2.7.x:
  // https://github.com/scalatra/scalatra/blob/dc46304149bab7b251b7558232a8bb80cb03c1f9/json/src/main/scala/org/scalatra/json/JsonSupport.scala#L27
  // The major change is that Scalatra no longer provides a shouldParseBody method to override.
  // I think this is okay since we never call parsedBody, so the logs should be clean.
  // The 2.7.x approach to this may be to override the new method JsonSupport.transformRequestBody()
  // using something like parseRequestBody() below and then use parsedBody in our operations.
  // The tests for this class are turned off, so I'm making minimal changes now.

  /*
   * Note: We turn off automatic parsing on the request body in JsonSupport
   * for all routes in this controller because Scalatra logs (!) JSON parsing
   * failures when calling parseRequestBody (via parsedBody) which is
   * automatically called for any route where this is function returns true.
   *
   * This was causing our API to log passwords in plain text!
   *
   * See:
   *   - https://github.com/scalatra/scalatra/blob/v2.6.3/json/src/main/scala/org/scalatra/json/JsonSupport.scala#L83-L92
   *   - https://github.com/scalatra/scalatra/blob/v2.6.3/json/src/main/scala/org/scalatra/json/JsonSupport.scala#L48-L51
   */
  /*override*/
  protected def shouldParseBody(
    fmt: String
  )(implicit
    request: HttpServletRequest
  ): Boolean = false

  /*
   * Note: We use readJsonFromBody here instead of parsedBody because
   * Scalatra logs (!) JSON parsing failures when calling
   * parseRequestBody (via parsedBody) from JsonSupport (via JacksonJsonSupport).
   *
   * This was causing our API to log passwords in plain text!
   *
   * See: https://github.com/scalatra/scalatra/blob/v2.6.3/json/src/main/scala/org/scalatra/json/JsonSupport.scala#L48-L51
   */
  private def parseRequestBody[T: scala.reflect.Manifest](
    implicit
    request: HttpServletRequest
  ): Either[ActionResult, T] = {
    for {
      json <- Either
        .catchNonFatal[JValue](readJsonFromBody(request.body))
        .leftMap(_ => BadRequest("invalid json in request body"))
      request <- extractOrError[T](json)
        .leftMap(_ => BadRequest("invalid request body"))
    } yield request
  }

  val createAccountFromInviteOperation
    : OperationBuilder = (apiOperation[CreateUserResponse](
    "createUserFromInvite"
  )
    summary "create a new user from a user invite"
    parameter bodyParam[CreateUserRequest]("body"))

  post("/", operation(createAccountFromInviteOperation)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, CreateUserResponse] = for {
        createRequest <- parseRequestBody[CreateUserRequest].toEitherT[Future]

        jwt <- AuthenticatedController
          .getBearerToken(request)
          .toEitherT[Future]

        cognitoId <- CognitoJWTAuthenticator
          .validateJwt(jwt)(cognitoConfig)
          .flatMap(_.id.asUserPoolId)
          .leftMap[CoreError](ThrowableError(_))
          .toEitherT[Future]
          .orUnauthorized()

        newUser <- insecureContainer.userManager
          .createFromInvite(
            cognitoId = cognitoId,
            firstName = createRequest.firstName,
            middleInitial = createRequest.middleInitial,
            lastName = createRequest.lastName,
            degree = createRequest.degree,
            title = createRequest.title
          )(
            insecureContainer.organizationManager,
            insecureContainer.userInviteManager,
            executor
          )
          .orBadRequest()

        welcomeOrganization <- insecureContainer.organizationManager
          .getBySlug("welcome_to_pennsieve")
          .orError()

        _ <- insecureContainer.organizationManager
          .addUser(welcomeOrganization, newUser, Guest)
          .orError()

        organizations <- insecureContainer.userManager
          .getOrganizations(newUser)
          .orError()

        preferredOrganization = newUser.preferredOrganizationId.flatMap(
          id => organizations.find(_.id == id)
        )

        dto = Builders.userDTO(
          user = newUser,
          organizationNodeId = preferredOrganization.map(_.nodeId),
          permission = None,
          storage = None,
          pennsieveTermsOfService = None,
          customTermsOfService = Seq.empty,
          role = None
        )
      } yield
        CreateUserResponse(
          orgIds = organizations.map(_.nodeId).toSet,
          profile = dto
        )

      val is = result.value.map(OkResult)
    }
  }

  val updateAccountFromInviteOperation
    : OperationBuilder = (apiOperation[CreateUserResponse](
    "updateUserFromInvite"
  )
    summary "update user from a user invite"
    parameter bodyParam[CreateUserRequest]("body"))

  put("/", operation(updateAccountFromInviteOperation)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, CreateUserResponse] = for {
        updateRequest <- parseRequestBody[CreateUserRequest].toEitherT[Future]

        jwt <- AuthenticatedController
          .getBearerToken(request)
          .toEitherT[Future]

        cognitoId <- CognitoJWTAuthenticator
          .validateJwt(jwt)(cognitoConfig)
          .flatMap(_.id.asUserPoolId)
          .leftMap[CoreError](ThrowableError(_))
          .toEitherT[Future]
          .orUnauthorized()

        user <- insecureContainer.userManager
          .getByCognitoId(cognitoId)
          .coreErrorToActionResult()

        updatedUser <- insecureContainer.userManager
          .update(
            user.copy(
              firstName = updateRequest.firstName,
              middleInitial = updateRequest.middleInitial,
              lastName = updateRequest.lastName,
              degree = updateRequest.degree,
              credential = updateRequest.title
            )
          )
          .coreErrorToActionResult()

        organizations <- insecureContainer.userManager
          .getOrganizations(updatedUser)
          .orError()

        preferredOrganization = updatedUser.preferredOrganizationId.flatMap(
          id => organizations.find(_.id == id)
        )

        dto = Builders.userDTO(
          user = updatedUser,
          organizationNodeId = preferredOrganization.map(_.nodeId),
          permission = None,
          storage = None,
          pennsieveTermsOfService = None,
          customTermsOfService = Seq.empty,
          role = None
        )

      } yield
        CreateUserResponse(
          orgIds = organizations.map(_.nodeId).toSet,
          profile = dto
        )
      val is = result.value.map(OkResult)
    }
  }

  val selfServiceUserSignUp
    : OperationBuilder = (apiOperation[CreateUserResponse]("signUpUser")
    summary "Self-service sign up a new user account"
    parameter bodyParam[CreateUserWithRecaptchaRequest]("body"))

  post("/sign-up", operation(selfServiceUserSignUp)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, CreateUserResponse] = for {

        createRequest <- parseRequestBody[CreateUserWithRecaptchaRequest]
          .toEitherT[Future]

        isVerified <- antiSpamChallengeClient
          .verifyToken(createRequest.recaptchaToken)
          .toEitherT
          .coreErrorToActionResult()

        _ <- (if (isVerified) {
                EitherT.rightT[Future, CoreError](())
              } else {
                EitherT.leftT[Future, Unit](
                  InvalidChallengeResponseError: CoreError
                )
              }).coreErrorToActionResult()

        cognitoId <- cognitoClient
          .inviteUser(
            email = Email(createRequest.email),
            suppressEmail = false,
            verifyEmail = true,
            invitePath = "self-service"
          )
          .toEitherT
          .coreErrorToActionResult()

        user <- insecureContainer.userManager
          .createFromSelfServiceSignUp(
            cognitoId = cognitoId,
            email = createRequest.email,
            firstName = createRequest.firstName,
            middleInitial = createRequest.middleInitial,
            lastName = createRequest.lastName,
            degree = createRequest.degree,
            title = createRequest.title.getOrElse("") // ugly, but needed for a simpler signup flow
          )(insecureContainer.organizationManager, asyncExecutor)
          .coreErrorToActionResult()

        organizations <- insecureContainer.userManager
          .getOrganizations(user)
          .orError()

        preferredOrganization = user.preferredOrganizationId.flatMap(
          id => organizations.find(_.id == id)
        )

        dto = Builders.userDTO(
          user = user,
          organizationNodeId = preferredOrganization.map(_.nodeId),
          permission = None,
          storage = None,
          pennsieveTermsOfService = None,
          customTermsOfService = Seq.empty,
          role = None
        )

      } yield
        CreateUserResponse(
          orgIds = organizations.map(_.nodeId).toSet,
          profile = dto
        )

      val is = result.value.map(OkResult)
    }
  }
}
