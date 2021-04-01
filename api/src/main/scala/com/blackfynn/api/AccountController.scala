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
import com.pennsieve.aws.email.Email
import com.pennsieve.aws.cognito.{
  CognitoClient,
  CognitoConfig,
  CognitoJWTAuthenticator
}
import com.pennsieve.dtos.{ Builders, UserDTO }
import com.pennsieve.domain.{ CoreError, ThrowableError }
import com.pennsieve.helpers.APIContainers.InsecureAPIContainer
import com.pennsieve.helpers.ResultHandlers.OkResult
import com.pennsieve.helpers._
import com.pennsieve.helpers.either.EitherErrorHandler.implicits._
import com.pennsieve.helpers.either.EitherTErrorHandler.implicits._
import com.pennsieve.models.{ CognitoId, DBPermission, Degree }
import com.pennsieve.web.Settings
import javax.servlet.http.HttpServletRequest
import org.json4s._
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder
import org.scalatra.swagger._

import java.util.UUID
import scala.concurrent.{ ExecutionContext, Future }

case class CreateUserRequest(
  firstName: String,
  middleInitial: Option[String],
  lastName: String,
  degree: Option[Degree],
  title: String
)
case class CreateUserResponse(orgIds: Set[String], profile: UserDTO)

class AccountController(
  val insecureContainer: InsecureAPIContainer,
  cognitoConfig: CognitoConfig,
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

  val createAccountOperation
    : OperationBuilder = (apiOperation[CreateUserResponse]("createUser")
    summary "create a new user from a user invite"
    parameter bodyParam[CreateUserRequest]("body"))

  post("/", operation(createAccountOperation)) {
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
          .orUnauthorized

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
          .orBadRequest

        organizations <- insecureContainer.userManager
          .getOrganizations(newUser)
          .orError

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
}
