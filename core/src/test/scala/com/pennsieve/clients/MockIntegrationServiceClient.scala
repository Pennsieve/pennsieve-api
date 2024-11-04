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
import akka.http.scaladsl.model.HttpResponse
import cats.data.EitherT
import com.pennsieve.auth.middleware.Jwt
import com.pennsieve.domain.CoreError
import com.pennsieve.models.{ Token, TokenSecret }

import scala.concurrent.{ ExecutionContext, Future }

class MockIntegrationServiceClient(
)(implicit
  override val system: ActorSystem,
  override val ec: ExecutionContext
) extends IntegrationServiceClient {
  override val integrationServiceHost = "test-integration-service-url"

  def postWorkflows(
    request: CreateWorkflowRequest,
    token: Jwt.Token
  ): EitherT[Future, CoreError, HttpResponse] = {
    EitherT.rightT[Future, CoreError](HttpResponse(201))
  }
}
