// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.api

import cats.implicits._
import com.blackfynn.helpers.APIContainers.{
  InsecureAPIContainer,
  SecureContainerBuilderType
}
import com.blackfynn.helpers.ResultHandlers.OkResult
import com.blackfynn.helpers.either.EitherTErrorHandler.implicits._
import com.blackfynn.models.OnboardingEventType
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

  override val swaggerTag = "Onboarding"

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
