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
import com.pennsieve.helpers.APIContainers.{
  InsecureAPIContainer,
  SecureContainerBuilderType
}
import com.pennsieve.helpers.ResultHandlers.OkResult
import com.pennsieve.helpers.either.EitherTErrorHandler.implicits._
import org.scalatra._
import org.scalatra.swagger.Swagger
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder
import scala.concurrent._

class InternalDataSetsController(
  val insecureContainer: InsecureAPIContainer,
  val secureContainerBuilder: SecureContainerBuilderType,
  asyncExecutor: ExecutionContext
)(implicit
  val swagger: Swagger
) extends ScalatraServlet
    with InternalAuthenticatedController {

  override protected implicit def executor: ExecutionContext = asyncExecutor

  override val pennsieveSwaggerTag = "DataSetsInternal"

  val touchDataSetOperation: OperationBuilder =
    (apiOperation[Unit]("touchDataSet")
      summary "touch the updatedAt timestamp for a data set (Internal Use Only) [deprecated]"
      parameters (
        pathParam[Int]("id").description("data set id")
      ) deprecate)

  post("/:id/touch", operation(touchDataSetOperation)) {

    response.setHeader(
      "Warning",
      "299 - 'touchDataSetOperation' is deprecated and will be removed on Nov 18 2025"
    )

    new AsyncResult {

      val result: EitherT[Future, ActionResult, Unit] = for {
        datasetId <- paramT[Int]("id")

        secureContainer <- getSecureContainer()
        _ <- secureContainer.datasetManager
          .touchUpdatedAtTimestamp(datasetId)
          .coreErrorToActionResult()

      } yield ()

      override val is = result.value.map(OkResult)
    }
  }

}
