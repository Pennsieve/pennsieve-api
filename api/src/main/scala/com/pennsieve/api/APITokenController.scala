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

import cats.data.EitherT
import cats.implicits._
import com.pennsieve.aws.cognito.CognitoClient
import com.pennsieve.dtos.{ APITokenDTO, APITokenSecretDTO }
import com.pennsieve.helpers.APIContainers.{
  InsecureAPIContainer,
  SecureContainerBuilderType
}
import com.pennsieve.helpers.ResultHandlers.{ CreatedResult, OkResult }
import com.pennsieve.helpers.either.EitherTErrorHandler.implicits._
import org.scalatra._
import org.scalatra.swagger.Swagger

import scala.concurrent.{ ExecutionContext, Future }

case class CreateTokenRequest(name: String)
case class UpdateTokenRequest(name: String)

class APITokenController(
  val insecureContainer: InsecureAPIContainer,
  val secureContainerBuilder: SecureContainerBuilderType,
  cognitoClient: CognitoClient,
  asyncExecutor: ExecutionContext
)(implicit
  val swagger: Swagger
) extends ScalatraServlet
    with AuthenticatedController {

  override protected implicit def executor: ExecutionContext = asyncExecutor

  override val pennsieveSwaggerTag = "API Token"

  /*
   * All routes in this controller should only be accessed via login-based authentication
   */
  before() {
    if (!isBrowserSession(request)) {
      halt(403, "Forbidden.")
    }
  }

  post(
    "/",
    operation(
      apiOperation[APITokenSecretDTO]("createAPIToken")
        summary "creates an API Token for the requesting User"
        parameter bodyParam[CreateTokenRequest]("body")
          .description("name of the API Token")
    )
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, APITokenSecretDTO] = for {
        secureContainer <- getSecureContainer()
        organization = secureContainer.organization
        body <- extractOrError[CreateTokenRequest](parsedBody).toEitherT[Future]
        tokenSecret <- secureContainer.tokenManager
          .create(body.name, secureContainer.user, organization, cognitoClient)
          .orError()
      } yield APITokenSecretDTO(tokenSecret)

      val is = result.value.map(CreatedResult)
    }
  }

  get(
    "/",
    operation(
      apiOperation[List[APITokenDTO]]("getAPITokens")
        summary "gets all the API Tokens the requesting User has access to"
    )
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, List[APITokenDTO]] = for {
        secureContainer <- getSecureContainer()
        organization = secureContainer.organization
        tokens <- secureContainer.tokenManager
          .get(secureContainer.user, organization)
          .orError()
      } yield tokens.map(t => APITokenDTO(t))

      val is = result.value.map(OkResult)
    }
  }

  put(
    "/:uuid",
    operation(
      apiOperation[Unit]("updateAPIToken")
        summary "updates the API Token if the requesting User has access to it"
        parameters (
          pathParam[String]("uuid").description("API Token UUID"),
          bodyParam[UpdateTokenRequest]("body").description("API Token Updates")
      )
    )
  ) {

    new AsyncResult {
      val result = for {
        tokenUUID <- paramT[String]("uuid")
        secureContainer <- getSecureContainer()
        token <- secureContainer.tokenManager.get(tokenUUID).orError()
        body <- extractOrError[CreateTokenRequest](parsedBody).toEitherT[Future]
        updatedToken <- secureContainer.tokenManager
          .update(token.copy(name = body.name))
          .orError()
      } yield APITokenDTO(updatedToken)

      override val is = result.value.map(OkResult)
    }
  }

  delete(
    "/:uuid",
    operation(
      apiOperation[Unit]("deleteAPIToken")
        summary "deletes API Token if the requesting User has access to it"
        parameters pathParam[String]("uuid").description("API Token UUID")
    )
  ) {
    new AsyncResult {
      val result = for {
        tokenUUID <- paramT[String]("uuid")
        secureContainer <- getSecureContainer()
        token <- secureContainer.tokenManager.get(tokenUUID).orError()
        deleted <- secureContainer.tokenManager
          .delete(token, cognitoClient)
          .orError()
      } yield deleted

      override val is = result.value.map(OkResult)
    }
  }

}
