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

import cats.implicits._
import com.pennsieve.helpers.APIContainers.{
  InsecureAPIContainer,
  SecureContainerBuilderType
}
import com.pennsieve.helpers.ResultHandlers.OkResult
import com.pennsieve.helpers.either.EitherTErrorHandler.implicits._
import com.pennsieve.models.OnboardingEventType
import org.scalatra.swagger.Swagger
import org.scalatra.{ AsyncResult, ScalatraServlet }

import scala.concurrent.ExecutionContext

class OnboardingController(
  val insecureContainer: InsecureAPIContainer,
  val secureContainerBuilder: SecureContainerBuilderType,
  asyncExecutor: ExecutionContext
)(implicit
  val swagger: Swagger
) extends ScalatraServlet
    with AuthenticatedController {

  override val pennsieveSwaggerTag = "Onboarding"

  override protected implicit def executor: ExecutionContext = asyncExecutor

  val getEventsOperation = (apiOperation[Seq[OnboardingEventType]]("getEvents")
    summary "Gets all onboarding events for the current user")

  get("/events", operation(getEventsOperation)) {
    new AsyncResult {
      val result = for {
        secureContainer <- getSecureContainer
        user = secureContainer.user
        events <- secureContainer.onboardingManager.getEvents(user.id).orError
      } yield events

      override val is = result.value.map(OkResult)
    }
  }

  val postEventOperation = (apiOperation[Seq[OnboardingEventType]]("postEvent")
    summary "Adds a new onboarding event for the current user"
    parameter bodyParam[OnboardingEventType]("body")
      .description("the event to add"))

  post("/events", operation(postEventOperation)) {
    new AsyncResult {
      val result = for {
        secureContainer <- getSecureContainer
        user = secureContainer.user
        event <- extractOrErrorT[OnboardingEventType](parsedBody)
        count <- secureContainer.onboardingManager
          .addEvent(user.id, event)
          .orError
      } yield count

      override val is = result.value.map(OkResult)
    }
  }
}
