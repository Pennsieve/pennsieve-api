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

import akka.Done
import akka.actor.ActorSystem
import cats.implicits._
import cats.data.EitherT
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits.FutureEitherT
import com.pennsieve.dtos.Builders.dataCanvasDTO
import com.pennsieve.dtos.DataCanvasDTO
import com.pennsieve.helpers.APIContainers.{
  InsecureAPIContainer,
  SecureContainerBuilderType
}
import com.pennsieve.helpers.ResultHandlers.{ CreatedResult, OkResult }
import com.pennsieve.helpers.either.EitherTErrorHandler.implicits._
import org.json4s.{ JNothing, JValue }
import org.scalatra.{ ActionResult, AsyncResult, ScalatraServlet }
import org.scalatra.swagger.Swagger

import scala.concurrent.{ ExecutionContext, Future }

// TODO:
//   1. do we need to deal with locking of data-canvases?
//   2. check authorization (see: secureContainer.authorizeDataset)
//   2. we should emit ChangeLog events
//   3. extract and utilize traceIds

case class CreateDataCanvasRequest(
  name: String,
  description: String,
  status: Option[String] = None
)

case class UpdateDataCanvasRequest(
  name: String,
  description: String,
  status: Option[String] = None
)

class DataCanvasController(
  val insecureContainer: InsecureAPIContainer,
  val secureContainerBuilder: SecureContainerBuilderType,
  implicit
  val system: ActorSystem,
  asyncExecutor: ExecutionContext
)(implicit
  val swagger: Swagger
) extends ScalatraServlet
    with AuthenticatedController {

  override protected implicit def executor: ExecutionContext = asyncExecutor

  override val swaggerTag = "DataCanvas"

  implicit class JValueExtended(value: JValue) {
    def hasField(childString: String): Boolean =
      (value \ childString) != JNothing
  }

  /**
    * GET a DataCanvas by its internal numeric identifier (`id`)
    */
  get(
    "/:id",
    operation(
      apiOperation[DataCanvasDTO]("getDataCanvas")
        summary "gets a data-canvas"
        parameters (
          pathParam[String]("id").description("data-canvas id")
        )
    )
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, DataCanvasDTO] = for {
        datacanvasId <- paramT[Int]("id")
        secureContainer <- getSecureContainer

        datacanvas <- secureContainer.dataCanvasManager
          .getById(datacanvasId)
          .coreErrorToActionResult

        dto <- dataCanvasDTO(datacanvas)(
          asyncExecutor,
          secureContainer,
          system,
          jwtConfig
        ).coreErrorToActionResult

      } yield dto

      override val is = result.value.map(OkResult(_))
    }
  }

  /**
    * Create a DataCanvas
    */
  post(
    "/",
    operation(
      apiOperation[DataCanvasDTO]("createDataCanvas")
        summary "creates a data-canvas"
        parameters (
          bodyParam[CreateDataCanvasRequest]("body")
            .description("name and properties of new data-canvas")
          )
    )
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, DataCanvasDTO] = for {
        secureContainer <- getSecureContainer
        body <- extractOrErrorT[CreateDataCanvasRequest](parsedBody)

        status <- body.status match {
          case Some(name) =>
            secureContainer.db
              .run(secureContainer.datasetStatusManager.getByName(name))
              .toEitherT
              .coreErrorToActionResult
          case None => // Use the default status
            secureContainer.db
              .run(secureContainer.datasetStatusManager.getDefaultStatus)
              .toEitherT
              .coreErrorToActionResult
        }

        newDataCanvas <- secureContainer.dataCanvasManager
          .create(body.name, body.description, statusId = Some(status.id))
          .coreErrorToActionResult

        // TODO: changelog event: Created DataCanvas

        dto <- dataCanvasDTO(newDataCanvas)(
          asyncExecutor,
          secureContainer,
          system,
          jwtConfig
        ).orError
      } yield dto

      override val is = result.value.map(CreatedResult)
    }
  }

  /**
    * Update a DataCanvas
    */
  put(
    "/:id",
    operation(
      apiOperation[DataCanvasDTO]("updateDataCanvas")
        summary "update a data-canvas"
        parameters (
          pathParam[Int]("id").description("data-canvas id"),
          bodyParam[UpdateDataCanvasRequest]("body")
            .description("name and properties of new data-canvas")
      )
    )
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, DataCanvasDTO] = for {
        id <- paramT[Int]("id")
        secureContainer <- getSecureContainer
        body <- extractOrErrorT[UpdateDataCanvasRequest](parsedBody)

        dataCanvas <- secureContainer.dataCanvasManager
          .getById(id)
          .orNotFound

        oldStatus <- secureContainer.datasetStatusManager
          .get(dataCanvas.statusId)
          .coreErrorToActionResult

        newStatus <- body.status match {
          case Some(name) => {
            secureContainer.db
              .run(secureContainer.datasetStatusManager.getByName(name))
              .toEitherT
              .coreErrorToActionResult
          }
          case None => // Keep existing status
            EitherT.rightT[Future, ActionResult](oldStatus)
        }

        updatedDataCanvas <- secureContainer.dataCanvasManager
          .update(
            dataCanvas.copy(
              name = body.name,
              description = body.description,
              statusId = newStatus.id
            )
          )
          .orBadRequest

        // TODO: changelog event: Updated DataCanvas

        dto <- dataCanvasDTO(updatedDataCanvas)(
          asyncExecutor,
          secureContainer,
          system,
          jwtConfig
        ).orError
      } yield dto

      override val is = result.value.map(CreatedResult)
    }
  }

  delete(
    "/:id",
    operation(
      apiOperation[Done]("deleteDataCanvas")
        summary "delete a data-canvas"
        parameters (
          pathParam[Int]("id").description("data-canvas id")
        )
    )
  ) {
    new AsyncResult() {
      val result: EitherT[Future, ActionResult, Done] = for {
        id <- paramT[Int]("id")
        secureContainer <- getSecureContainer

        dataCanvas <- secureContainer.dataCanvasManager
          .getById(id)
          .orNotFound()

        _ <- secureContainer.dataCanvasManager
          .delete(dataCanvas)
          .orForbidden

      } yield Done
      override val is = result.value.map(OkResult)
    }
  }
}
