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
import cats.syntax.either._
import com.pennsieve.auth.middleware.Jwt
import com.pennsieve.domain.{ CoreError, ServiceError }
import com.pennsieve.models.{ Token, TokenSecret }
import com.typesafe.scalalogging.StrictLogging
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.syntax.EncoderOps
import io.circe.{ Decoder, Encoder }

import scala.concurrent.{ ExecutionContext, Future }

case class CreateWorkflowRequest(
  workflowName: String,
  description: String,
  secret: String,
  datasetIntId: Int
)

object CreateWorkflowRequest {
  implicit def encoder: Encoder[CreateWorkflowRequest] =
    deriveEncoder[CreateWorkflowRequest]
  implicit def decoder: Decoder[CreateWorkflowRequest] =
    deriveDecoder[CreateWorkflowRequest]
}

trait IntegrationServiceClient {
  val integrationServiceHost: String

  implicit val system: ActorSystem
  implicit val ec: ExecutionContext

  def postWorkflows(
    request: CreateWorkflowRequest,
    tokenSecret: (Token, TokenSecret),
    token: Jwt.Token
  ): EitherT[Future, CoreError, HttpResponse]
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
              val error = ServiceError(
                s"Error communicating with the integration service: ${resp.toString}"
              )
              logger.error(error.message)
              Left[CoreError, HttpResponse](error)
            }
        )
    )
  }

  def postWorkflows(
    request: CreateWorkflowRequest,
    tokenSecret: (Token, TokenSecret),
    token: Jwt.Token
  ): EitherT[Future, CoreError, HttpResponse] = {
    val requestData = ByteString(request.asJson.noSpaces)
    makeRequest(
      HttpRequest(
        HttpMethods.POST,
        s"$integrationServiceHost/workflows",
        entity = HttpEntity
          .Strict(ContentType(MediaTypes.`application/json`), requestData)
      ),
      token
    )
  }

}
