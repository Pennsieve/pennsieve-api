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

import com.pennsieve.auth.middleware.DatasetPermission
import com.pennsieve.dtos.DimensionDTO
import com.pennsieve.helpers.APIContainers.{
  InsecureAPIContainer,
  SecureAPIContainer,
  SecureContainerBuilderType
}
import com.pennsieve.helpers.either.EitherTErrorHandler.implicits._
import com.pennsieve.helpers.ResultHandlers._
import com.pennsieve.models.{ DimensionAssignment, DimensionProperties }

import cats.data.EitherT
import cats.implicits._
import org.json4s
import org.json4s._
import org.scalatra._
import org.scalatra.swagger.Swagger
import scala.concurrent.{ ExecutionContext, Future }

case class DimensionPropertiesWithId(
  id: Int,
  name: String,
  length: Long,
  resolution: Option[Double],
  unit: Option[String],
  assignment: DimensionAssignment
)

class ImagingController(
  val insecureContainer: InsecureAPIContainer,
  val secureContainerBuilder: SecureContainerBuilderType,
  asyncExecutor: ExecutionContext
)(implicit
  val swagger: Swagger
) extends ScalatraServlet
    with AuthenticatedController {

  override protected implicit def executor: ExecutionContext = asyncExecutor

  override val swaggerTag = "Imaging"

  val getDimensionsOperation = (apiOperation[List[DimensionDTO]](
    "getDimensions"
  )
    summary "get dimensions for package"
    parameter pathParam[String]("packageId")
      .description("the ID of the package"))

  get("/:packageId/dimensions", operation(getDimensionsOperation)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, List[DimensionDTO]] = for {
        secureContainer <- getSecureContainer
        packageId <- paramT[String]("packageId")
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(packageId)
          .orNotFound
        (pkg, dataset) = packageAndDataset

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ViewFiles))(dataset)
          .coreErrorToActionResult

        dimensions <- secureContainer.dimensionManager.getAll(pkg).orError
      } yield DimensionDTO(dimensions, pkg)

      override val is = result.value.map(OkResult)
    }
  }

  val getDimensionOperation = (apiOperation[DimensionDTO]("getDimension")
    summary "get dimension for package"
    parameter pathParam[String]("packageId")
      .description("the ID of the package")
    parameter pathParam[String]("id").description("the ID of the dimension"))

  get("/:packageId/dimensions/:id", operation(getDimensionOperation)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, DimensionDTO] = for {
        secureContainer <- getSecureContainer
        id <- paramT[Int]("id")
        packageId <- paramT[String]("packageId")
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(packageId)
          .orNotFound
        (pkg, dataset) = packageAndDataset

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ViewFiles))(dataset)
          .coreErrorToActionResult

        dimension <- secureContainer.dimensionManager
          .get(id, pkg)
          .orNotFound
      } yield DimensionDTO(dimension, pkg)

      override val is = result.value.map(OkResult)
    }
  }

  val createDimensionOperation = (apiOperation[DimensionDTO]("createDimension")
    summary "creates a new dimension on a package"
    parameter pathParam[String]("packageId")
      .description("the ID of the package")
    parameter bodyParam[DimensionProperties]("body")
      .description("dimension to create"))

  post("/:packageId/dimensions", operation(createDimensionOperation)) {
    new AsyncResult {
      val result = for {
        secureContainer <- getSecureContainer
        packageId <- paramT[String]("packageId")
        properties <- extractOrErrorT[DimensionProperties](parsedBody)
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(packageId)
          .orNotFound
        (pkg, dataset) = packageAndDataset

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.EditFiles))(dataset)
          .coreErrorToActionResult
        _ <- secureContainer.datasetManager
          .assertNotLocked(dataset)
          .coreErrorToActionResult

        dimension <- secureContainer.dimensionManager
          .create(properties, pkg)
          .orError
      } yield DimensionDTO(dimension, pkg)

      override val is = result.value.map(CreatedResult)
    }
  }

  val createDimensionsOperation = (apiOperation[List[DimensionDTO]](
    "createDimensions"
  )
    summary "creates multiple new dimensions on a package"
    parameter pathParam[String]("packageId")
      .description("the ID of the package")
    parameter bodyParam[List[DimensionProperties]]("body")
      .description("dimensions to create"))

  post("/:packageId/dimensions/batch", operation(createDimensionsOperation)) {
    new AsyncResult {
      val result = for {
        secureContainer <- getSecureContainer
        packageId <- paramT[String]("packageId")
        batch <- extractOrErrorT[List[DimensionProperties]](parsedBody)
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(packageId)
          .orNotFound
        (pkg, dataset) = packageAndDataset

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.EditFiles))(dataset)
          .coreErrorToActionResult
        _ <- secureContainer.datasetManager
          .assertNotLocked(dataset)
          .coreErrorToActionResult
        dimensions <- secureContainer.dimensionManager
          .create(batch, pkg)
          .orError
      } yield DimensionDTO(dimensions, pkg)

      override val is = result.value.map(CreatedResult)
    }
  }

  val updateDimensionOperation = (apiOperation[DimensionDTO]("updateDimension")
    summary "updates a dimension on a package"
    parameter pathParam[Int]("id")
      .description("the ID of the dimension to update")
    parameter pathParam[String]("packageId")
      .description("the ID of the package")
    parameter bodyParam[DimensionProperties]("body")
      .description("dimension properties to update"))

  put("/:packageId/dimensions/:id", operation(updateDimensionOperation)) {
    new AsyncResult {
      val result = for {
        secureContainer <- getSecureContainer
        id <- paramT[Int]("id")
        packageId <- paramT[String]("packageId")
        body <- extractOrErrorT[DimensionProperties](parsedBody)
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(packageId)
          .orNotFound
        (pkg, dataset) = packageAndDataset

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.EditFiles))(dataset)
          .coreErrorToActionResult
        _ <- secureContainer.datasetManager
          .assertNotLocked(dataset)
          .coreErrorToActionResult
        original <- secureContainer.dimensionManager
          .get(id, pkg)
          .orNotFound
        updated = original.copy(
          name = body.name,
          length = body.length,
          resolution = body.resolution,
          unit = body.unit,
          assignment = body.assignment
        )
        dimension <- secureContainer.dimensionManager
          .update(updated, pkg)
          .orError
      } yield DimensionDTO(dimension, pkg)

      override val is = result.value.map(OkResult)
    }
  }

  val updateDimensionsOperation = (apiOperation[List[DimensionDTO]](
    "updateDimensions"
  )
    summary "updates multiple dimensions on a package"
    parameter pathParam[String]("packageId")
      .description("the ID of the package")
    parameter bodyParam[List[DimensionPropertiesWithId]]("body")
      .description("dimensions to update"))

  put("/:packageId/dimensions/batch", operation(updateDimensionsOperation)) {
    new AsyncResult {
      val result = for {
        secureContainer <- getSecureContainer
        packageId <- paramT[String]("packageId")
        body <- extractOrErrorT[List[DimensionPropertiesWithId]](parsedBody)
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(packageId)
          .orNotFound
        (pkg, dataset) = packageAndDataset
        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.EditFiles))(dataset)
          .coreErrorToActionResult
        _ <- secureContainer.datasetManager
          .assertNotLocked(dataset)
          .coreErrorToActionResult

        batch <- body.traverse(
          properties =>
            secureContainer.dimensionManager
              .get(properties.id, pkg)
              .orNotFound
              .map { original =>
                original.copy(
                  name = properties.name,
                  length = properties.length,
                  resolution = properties.resolution,
                  unit = properties.unit,
                  assignment = properties.assignment
                )
              }
        )
        dimensions <- secureContainer.dimensionManager
          .update(batch, pkg)
          .orError
      } yield DimensionDTO(dimensions, pkg)

      override val is = result.value.map(OkResult)
    }
  }

  val deleteDimensionOperation = (apiOperation[Int]("deleteDimension")
    summary "deletes a dimension from a package"
    parameter pathParam[Int]("id")
      .description("the ID of the dimension to delete")
    parameter pathParam[String]("packageId")
      .description("the ID of the package to delete from"))

  delete("/:packageId/dimensions/:id", operation(deleteDimensionOperation)) {
    new AsyncResult {
      val result = for {
        secureContainer <- getSecureContainer
        id <- paramT[Int]("id")
        packageId <- paramT[String]("packageId")
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(packageId)
          .orNotFound
        (pkg, dataset) = packageAndDataset

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.EditFiles))(dataset)
          .coreErrorToActionResult
        _ <- secureContainer.datasetManager
          .assertNotLocked(dataset)
          .coreErrorToActionResult
        dimension <- secureContainer.dimensionManager
          .get(id, pkg)
          .orNotFound
        deleted <- secureContainer.dimensionManager
          .delete(dimension, pkg)
          .orError
      } yield deleted

      override val is = result.value.map(OkResult)
    }
  }

  val deleteDimensionsOperation = (apiOperation[Unit]("deleteDimensions")
    summary "delete multiple dimensions from a package"
    parameter pathParam[String]("packageId")
      .description("the ID of the package to delete from")
    parameter bodyParam[List[Int]]("body")
      .description("IDs of dimensions to delete"))

  delete("/:packageId/dimensions/batch", operation(deleteDimensionsOperation)) {
    new AsyncResult {
      val result = for {
        secureContainer <- getSecureContainer
        packageId <- paramT[String]("packageId")
        ids <- extractOrErrorT[List[Int]](parsedBody)
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(packageId)
          .orNotFound
        (pkg, dataset) = packageAndDataset

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.EditFiles))(dataset)
          .coreErrorToActionResult
        _ <- secureContainer.datasetManager
          .assertNotLocked(dataset)
          .coreErrorToActionResult
        dimensions <- secureContainer.dimensionManager
          .get(ids.toSet, pkg)
          .orError
        _ <- secureContainer.dimensionManager
          .delete(dimensions, pkg)
          .orError
      } yield ()

      override val is = result.value.map(OkResult)
    }
  }

  val getDimensionsCountOperation = (apiOperation[Int]("getDimensionsCount")
    summary "return the number of dimensions a package has"
    parameter pathParam[String]("packageId")
      .description("the ID of the package to get the dimensions count for"))

  get("/:packageId/dimensions/count", operation(getDimensionsCountOperation)) {
    new AsyncResult {
      val result = for {
        secureContainer <- getSecureContainer
        packageId <- paramT[String]("packageId")
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(packageId)
          .orNotFound
        (pkg, dataset) = packageAndDataset

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ViewFiles))(dataset)
          .coreErrorToActionResult

        count <- secureContainer.dimensionManager.count(pkg).orError
      } yield count

      override val is = result.value.map(OkResult)
    }
  }

}
