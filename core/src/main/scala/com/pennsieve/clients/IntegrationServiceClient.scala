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

package com.pennsieve.clients

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.util.ByteString
import cats.data.EitherT
import cats.instances.future._
import cats.syntax.either._
import com.pennsieve.auth.middleware.Jwt
import com.pennsieve.domain.CoreError
import com.pennsieve.utilities.Container
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.{ ExecutionContext, Future }

case class CreateWorkflowRequest(
  workflowName: String,
  description: String,
  secret: String,
  datasetId: String
)

case class IntegrationWorkflow(workflowName: String)

trait IntegrationServiceClient {
  val integrationServiceHost: String

  implicit val system: ActorSystem
  implicit val ec: ExecutionContext

  def postWorkflows(
    workflowName: String,
    token: Jwt.Token
  ): EitherT[Future, CoreError, HttpResponse]
}

class LocalIntegrationServiceClient(
)(implicit
  override val system: ActorSystem,
  override val ec: ExecutionContext
) extends IntegrationServiceClient {
  override val integrationServiceHost = "test-integration-service-url"

  def postWorkflows(
    workflowName: String,
    token: Jwt.Token
  ): EitherT[Future, CoreError, HttpResponse] = {
    EitherT.rightT[Future, CoreError](HttpResponse(201))
  }
}

class IntegrationServiceClientImpl(
  override val integrationServiceHost: String
)(implicit
  override val system: ActorSystem,
  override val ec: ExecutionContext
) extends IntegrationServiceClient
    with StrictLogging {

  def makeRequest(
    req: HttpRequest,
    token: Jwt.Token
  ): EitherT[Future, CoreError, HttpResponse] = {
    EitherT(
      Http()
        .singleRequest(
          req.addHeader(
            headers.Authorization(headers.OAuth2BearerToken(token.value))
          )
        )
        .map(
          resp =>
            if (resp.status.isSuccess) {
              resp.asRight
            } else {
//              val error = CoreError(
//                s"Error communicating with the integration service: ${resp.toString}"
//              )
//              logger.error(error.message)
              Left[CoreError, HttpResponse](null)
            }
        )
    )
  }

  def postWorkflows(
    workflowName: String,
    token: Jwt.Token
  ): EitherT[Future, CoreError, HttpResponse] = {
    val byteArray = Array[Byte](1, 2, 3, 4)
    val byteString = ByteString(byteArray)
    makeRequest(
      HttpRequest(
        HttpMethods.POST,
        s"$integrationServiceHost/workflows",
        entity = HttpEntity
          .Strict(ContentType(MediaTypes.`application/json`), byteString)
      ),
      token
    )
  }

}

trait IntegrationServiceContainer { self: Container =>
  implicit val system: ActorSystem
  implicit val ec: ExecutionContext

  val integrationServiceHost: String
  val integrationServiceClient: IntegrationServiceClient
}

trait IntegrationServiceContainerImpl extends IntegrationServiceContainer {
  self: Container =>
  implicit val system: ActorSystem
  implicit val ec: ExecutionContext

  val integrationServiceHost: String
  override lazy val integrationServiceClient: IntegrationServiceClient =
    new IntegrationServiceClientImpl(integrationServiceHost)
}

trait LocalIntegrationServiceContainer extends IntegrationServiceContainer {
  self: Container =>
  implicit val system: ActorSystem
  implicit val ec: ExecutionContext

  val integrationServiceHost: String
  override val integrationServiceClient: IntegrationServiceClient =
    new LocalIntegrationServiceClient
}
