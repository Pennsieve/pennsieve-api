// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.api

import cats.data._
import cats.implicits._
import com.blackfynn.aws.email.Email
import com.blackfynn.core.utilities.PasswordBuddy.passwordEntropy
import com.blackfynn.dtos.{ Builders, UserDTO }
import com.blackfynn.helpers.APIContainers.InsecureAPIContainer
import com.blackfynn.helpers.ResultHandlers.OkResult
import com.blackfynn.helpers._
import com.blackfynn.helpers.either.EitherErrorHandler.implicits._
import com.blackfynn.helpers.either.EitherTErrorHandler.implicits._
import com.blackfynn.models.{ CognitoId, DBPermission, Degree }
import com.blackfynn.web.Settings
import javax.servlet.http.HttpServletRequest
import org.json4s._
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder
import org.scalatra.swagger._

import java.util.UUID
import scala.concurrent.{ ExecutionContext, Future }

case class EmailPasswordResetResponse(message: String)

case class ResetPasswordRequest(resetToken: String, newPassword: String)
case class ResetPasswordResponse(
  sessionToken: String,
  organization: String,
  profile: UserDTO,
  message: String
)

case class CreateUserRequest(
  firstName: String,
  middleInitial: Option[String],
  lastName: String,
  degree: Option[Degree],
  title: String
)
case class CreateUserResponse(
  orgIds: Set[String],
  sessionId: String,
  profile: UserDTO
)

class AccountController(
  val insecureContainer: InsecureAPIContainer,
  asyncExecutor: ExecutionContext
)(implicit
  val swagger: Swagger
) extends ScalatraServlet
    with ErrorLoggingSupport
    with JacksonJsonSupport
    with ParamsSupport
    with PennsieveSwaggerSupport
    with FutureSupport {

  override val swaggerTag = "Account"

  protected implicit def executor: ExecutionContext = asyncExecutor

  protected implicit val jsonFormats
    : Formats = DefaultFormats ++ ModelSerializers.serializers

  protected val applicationDescription: String = "Core API"

  before() {
    contentType = formats("json")
  }

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
  override protected def shouldParseBody(
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

  // PASSWORD RESET
  //////////////////////////////

  def isValidPassword(password: String): Either[ActionResult, _] = {
    // passes if string contains at least one letter and one number
    if (passwordEntropy(password) > 59)
      Right(())
    else
      Left(BadRequest(Error(Settings.password_validation_error_message)))
  }

  val resetPasswordOperation
    : OperationBuilder = (apiOperation[ResetPasswordResponse]("resetPassword")
    summary "resets a users password"
    parameter bodyParam[ResetPasswordRequest]("body"))

  post("/reset", operation(resetPasswordOperation)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, ResetPasswordResponse] = for {
        form <- parseRequestBody[ResetPasswordRequest].toEitherT[Future]

        resetSession <- insecureContainer.sessionManager
          .get(form.resetToken)
          .orUnauthorized
          .toEitherT[Future]
        user <- resetSession
          .user()(insecureContainer.userManager, executor)
          .orUnauthorized

        _ = insecureContainer.redisManager.set("badLoginCount", user.nodeId, 0)
        _ <- isValidPassword(form.newPassword).toEitherT[Future]

        resetUser <- insecureContainer.userManager
          .resetUserPassword(user, form.newPassword)
          .orError
        dto <- Builders
          .userDTO(user, storage = None)(
            insecureContainer.organizationManager,
            insecureContainer.pennsieveTermsOfServiceManager,
            insecureContainer.customTermsOfServiceManager,
            executor
          )
          .orError

        session <- insecureContainer.sessionManager
          .generateBrowserSession(user, Settings.sessionTimeout)
          .orError
      } yield {
        insecureContainer.sessionManager.remove(resetSession)

        ResetPasswordResponse(
          sessionToken = session.uuid,
          organization = session.organizationId,
          profile = dto,
          message = "Password has been reset"
        )
      }

      val is = result.value.map(OkResult(_))
    }
  }

  val resetPasswordEmailOperation
    : OperationBuilder = (apiOperation[EmailPasswordResetResponse](
    "resetPasswordEmail"
  )
    summary "requests a reset password event for a user"
    parameter pathParam[String]("email").description("account to reset"))

  post("/:email/reset", operation(resetPasswordEmailOperation)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, EmailPasswordResetResponse] =
        for {
          email <- param[String]("email").toEitherT[Future]
          user <- insecureContainer.userManager
            .getByEmail(email.toLowerCase)
            .orBadRequest
          temporarySession <- insecureContainer.sessionManager
            .generateTemporarySession(user, Settings.password_reset_time_limit)
            .orBadRequest

          preferredOrganization <- insecureContainer.userManager
            .getPreferredOrganization(user)
            .orError
          userPermission <- insecureContainer.organizationManager
            .getUserPermission(preferredOrganization, user)
            .orError

          message = insecureContainer.messageTemplates
            .passwordReset(user.email, token = temporarySession.uuid)
          _ <- insecureContainer.emailer
            .sendEmail(
              to = Email(user.email),
              from = Settings.support_email,
              message = message,
              subject = "Password reset"
            )
            .leftMap(error => InternalServerError(error.getMessage))
            .toEitherT[Future]
        } yield EmailPasswordResetResponse("Email Sent")

      val is = result.value.map(OkResult(_))
    }
  }

  val createAccountOperation
    : OperationBuilder = (apiOperation[CreateUserResponse]("createUser")
    summary "create a new user from a create user token"
    parameter bodyParam[CreateUserRequest]("body"))

  post("/", operation(createAccountOperation)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, CreateUserResponse] = for {
        request <- parseRequestBody[CreateUserRequest].toEitherT[Future]

        // TODO: parse Cognito ID from Cognito JWT header
        //        cognitoId <- AuthenticatedController.getBearerToken(request).toEitherT
        cognitoId = CognitoId.randomId()

        // TODO: remove this
        password = "NO PASSWORD"

        newUser <- insecureContainer.userManager
          .createFromInvite(
            cognitoId = cognitoId,
            firstName = request.firstName,
            middleInitial = request.middleInitial,
            lastName = request.lastName,
            degree = request.degree,
            title = request.title,
            password = password
          )(
            insecureContainer.organizationManager,
            insecureContainer.userInviteManager,
            executor
          )
          .orBadRequest

        session <- insecureContainer.sessionManager
          .generateBrowserSession(newUser, Settings.sessionTimeout)
          .orError

        organizations <- insecureContainer.userManager
          .getOrganizations(newUser)
          .orError

        dto = Builders.userDTO(
          user = newUser,
          organizationNodeId = None,
          permission = None,
          storage = None,
          pennsieveTermsOfService = None,
          customTermsOfService = Seq.empty,
          role = None
        )
      } yield
        CreateUserResponse(
          orgIds = organizations.map(_.nodeId).toSet,
          sessionId = session.uuid,
          profile = dto
        )

      val is = result.value.map(OkResult)
    }
  }
}
