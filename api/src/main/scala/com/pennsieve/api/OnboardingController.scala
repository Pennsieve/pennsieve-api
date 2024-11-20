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
import com.pennsieve.helpers.either.EitherTErrorHandler.implicits._
import org.scalatra.swagger.Swagger
import org.scalatra.{ ActionResult, AsyncResult, Gone, ScalatraServlet }

import scala.concurrent.{ ExecutionContext, Future }

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

  // Onboarding endpoints have been removed.

  get("/events") {
    new AsyncResult {
      override val is: Future[ActionResult] = Future.successful(Gone())
    }
  }

  post("/events") {
    new AsyncResult {
      override val is: Future[ActionResult] = Future.successful(Gone())
    }
  }
}
