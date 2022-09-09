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
import java.util.UUID.randomUUID

import cats.data.EitherT
import com.pennsieve.core.utilities.checkOrErrorT
import com.pennsieve.domain.{ CoreError, ServiceError }
import com.pennsieve.helpers.APIContainers.InsecureAPIContainer
import com.pennsieve.helpers.ResultHandlers.OkResult
import com.pennsieve.helpers.{ ErrorLoggingSupport, ParamsSupport }
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.{
  ActionResult,
  AsyncResult,
  FutureSupport,
  ScalatraServlet
}
import com.pennsieve.helpers.either.EitherTErrorHandler.implicits._
import cats.implicits._
import org.scalatra.swagger.Swagger
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._

import scala.concurrent.{ ExecutionContext, Future }
import com.typesafe.scalalogging.{ LazyLogging, Logger }

class HealthController(
  insecureContainer: InsecureAPIContainer,
  asyncExecutor: ExecutionContext
)(implicit
  override val swagger: Swagger
) extends ScalatraServlet
    with PennsieveSwaggerSupport
    with FutureSupport
    with LazyLogging {

  override protected implicit def executor: ExecutionContext = asyncExecutor
  override implicit lazy val logger: Logger = Logger("com.pennsieve")

  protected val applicationDescription: String = "Core API"

  override val pennsieveSwaggerTag = "Health"

  val getHealthOperation = (apiOperation[Unit]("getHealth")
    summary "performs a health check")

  get("/", operation(getHealthOperation)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Unit] = for {

        dbCheck <- insecureContainer.db
          .run(sql"select 1".as[Int])
          .map(_.contains(1))
          .toEitherT
          .coreErrorToActionResult

        _ <- checkOrErrorT[CoreError](dbCheck)(
          ServiceError("DB connection unavailable")
        ).coreErrorToActionResult

      } yield ()

      override val is = result.value.map(OkResult)
    }

  }
}
