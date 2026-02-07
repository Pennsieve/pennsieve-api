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

import cats.data.EitherT
import cats.implicits._
import com.pennsieve.audit.middleware.Auditor
import com.pennsieve.auth.middleware.DatasetPermission
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.domain.IntegrityError
import com.pennsieve.dtos._
import com.pennsieve.helpers.APIContainers.{
  InsecureAPIContainer,
  SecureContainerBuilderType
}
import com.pennsieve.helpers.Colors
import com.pennsieve.helpers.ResultHandlers.{ CreatedResult, OkResult }
import com.pennsieve.helpers.either.EitherTErrorHandler.implicits._
import com.pennsieve.models.{ AnnotationLayer, PathElement }
import org.scalatra.swagger.Swagger
import org.scalatra.{
  ActionResult,
  AsyncResult,
  BadRequest,
  InternalServerError,
  ScalatraServlet
}

import scala.concurrent.{ ExecutionContext, Future }

case class UpdateLayerRequest(name: String, color: Option[String])
case class CreateLayerRequest(
  name: String,
  annotatedItem: String,
  color: Option[String]
)

case class CreateAnnotationRequest(
  description: String,
  annotatedItem: String,
  layerId: Option[Int] = None,
  properties: List[ModelPropertyRO] = Nil,
  path: Option[List[PathElement]] = None
)
case class UpdateAnnotationRequest(description: String, layerId: Option[Int])
case class AnnotationResponse(
  annotation: AnnotationDTO,
  userMap: Option[Map[String, UserDTO]],
  layer: Option[AnnotationLayer] = None
)

class AnnotationsController(
  val insecureContainer: InsecureAPIContainer,
  val secureContainerBuilder: SecureContainerBuilderType,
  asyncExecutor: ExecutionContext,
  auditLogger: Auditor
)(implicit
  val swagger: Swagger
) extends ScalatraServlet
    with AuthenticatedController {

  override val pennsieveSwaggerTag: String = "Annotations"
  override protected implicit def executor: ExecutionContext = asyncExecutor

  // for reads, access to annotations is implied through the node to which they refer
  // if a user has access to that node, facilitated through the secure graph manager,
  // then the user has access to the annotations associated with it

  val getAnnotationOperation = (apiOperation[Option[AnnotationResponse]](
    "getAnnotationOperation"
  )
    summary "get an annotation"
    parameter pathParam[String]("id").description("the id of the annotation"))

  get("/:id", operation(getAnnotationOperation)) {
    new AsyncResult {
      val result = for {
        secureContainer <- getSecureContainer()
        traceId <- getTraceId(request)
        annotationId <- paramT[Int]("id")
        annotation <- secureContainer.annotationManager
          .get(annotationId)
          .orNotFound()
        pkgId <- secureContainer.annotationManager
          .findPackageId(annotation)
          .orError()
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetById(pkgId)
          .orNotFound()
        (pkg, dataset) = packageAndDataset

        _ <- secureContainer
          .authorizePackageId(Set(DatasetPermission.ViewAnnotations))(pkgId)
          .coreErrorToActionResult()

        _ <- auditLogger
          .message()
          .append("annotation-id", annotationId)
          .append("dataset-id", dataset.id)
          .append("dataset-node-id", dataset.nodeId)
          .append("package-id", pkg.id)
          .append("package-node-id", pkg.nodeId)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult()

        users <- secureContainer.annotationManager
          .findUsersForAnnotation(annotation)
          .orError()
        userMap = users
          .map(
            u =>
              u.nodeId -> Builders
                .userDTO(
                  u,
                  organizationNodeId = None,
                  storage = None,
                  pennsieveTermsOfService = None,
                  customTermsOfService = Seq.empty
                )
          )
          .toMap
        creatorId = users
          .find(_.id == annotation.creatorId)
          .map(_.nodeId)
          .getOrElse("")
      } yield
        AnnotationResponse(AnnotationDTO(annotation, creatorId), Some(userMap))

      val is = result.value.map(OkResult)
    }
  }

  val updateAnnotationOperation = (apiOperation[AnnotationResponse](
    "updateAnnotation"
  )
    summary "updates an annotation"
    parameter bodyParam[UpdateAnnotationRequest]("body")
      .description("the comment to add")
    parameter pathParam[String]("id").description("the id of the annotation"))

  put("/:id", operation(updateAnnotationOperation)) {

    new AsyncResult {
      val result = for {
        req <- extractOrErrorT[UpdateAnnotationRequest](parsedBody)
        secureContainer <- getSecureContainer()
        traceId <- getTraceId(request)
        annotationId <- paramT[Int]("id")
        annotation <- secureContainer.annotationManager
          .get(annotationId)
          .orNotFound()

        pkgId <- secureContainer.annotationManager
          .findPackageId(annotation)
          .orError()

        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetById(pkgId)
          .orNotFound()
        (pkg, dataset) = packageAndDataset

        _ <- secureContainer.datasetManager
          .assertNotLocked(pkg.datasetId)
          .coreErrorToActionResult()

        _ <- auditLogger
          .message()
          .append("annotation-id", annotationId)
          .append("dataset-id", dataset.id)
          .append("dataset-node-id", dataset.nodeId)
          .append("package-id", pkg.id)
          .append("package-node-id", pkg.nodeId)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult()

        currentLayer <- secureContainer.annotationManager
          .getLayer(annotation.layerId)
          .orNotFound()

        updatedLayer <- req.layerId
          .traverse(id => secureContainer.annotationManager.getLayer(id))
          .orNotFound()

        destLayerId = updatedLayer.map(_.id).getOrElse(annotation.layerId)

        maybeBothPackageIds = updatedLayer
          .map(_.packageId)
          .toSet + currentLayer.packageId

        _ <- maybeBothPackageIds.toList
          .traverse(
            secureContainer
              .authorizePackageId(Set(DatasetPermission.ManageAnnotations))
          )
          .coreErrorToActionResult()

        updated <- secureContainer.annotationManager
          .update(
            annotation
              .copy(description = req.description, layerId = destLayerId)
          )
          .orError()
        annotationOwner <- secureContainer.userManager
          .get(annotation.creatorId)
          .orError()
      } yield
        AnnotationResponse(AnnotationDTO(updated, annotationOwner.nodeId), None)

      override val is = result.value.map(OkResult)
    }
  }

  val createAnnotationOperation = (apiOperation[AnnotationResponse](
    "createAnnotation"
  )
    summary "creates an annotation"
    parameter bodyParam[CreateAnnotationRequest]("createAnnotationRequest"))

  post("/", operation(createAnnotationOperation)) {
    new AsyncResult {
      val result = for {
        traceId <- getTraceId(request)
        req <- extractOrErrorT[CreateAnnotationRequest](parsedBody)
        props = ModelPropertyRO.fromRequestObject(req.properties)
        secureContainer <- getSecureContainer()
        user = secureContainer.user
        manager = secureContainer.annotationManager

        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(req.annotatedItem)
          .orNotFound()
        (pkg, dataset) = packageAndDataset

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ManageAnnotations))(dataset)
          .coreErrorToActionResult()
        _ <- secureContainer.datasetManager
          .assertNotLocked(dataset)
          .coreErrorToActionResult()
        existingLayers <- manager.findLayers(pkg).orError()
        layer <- req.layerId match {
          case Some(layerId) =>
            manager.getLayerByPackage(layerId, pkg.id).orNotFound()
          case None => {
            val randomColor =
              Colors.randomNewColor(existingLayers.map(_.color).toSet)
            manager
              .createLayer(pkg, "Default", randomColor.getOrElse(""))
              .orError()
          }
        }
        annotation <- manager
          .create(
            secureContainer.user,
            layer,
            req.description,
            req.path.getOrElse(Nil),
            props
          )
          .orError()
        userMap = Map(
          user.nodeId -> Builders
            .userDTO(
              user,
              organizationNodeId = None,
              storage = None,
              pennsieveTermsOfService = None,
              customTermsOfService = Seq.empty
            )
        )

        _ <- auditLogger
          .message()
          .append("annotation-id", req.annotatedItem)
          .append("dataset-id", dataset.id)
          .append("dataset-node-id", dataset.nodeId)
          .append("package-id", pkg.id)
          .append("package-node-id", pkg.nodeId)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult()

      } yield
        AnnotationResponse(
          AnnotationDTO(annotation, secureContainer.user.nodeId),
          Some(userMap),
          Some(layer)
        ) //return layer

      val is = result.value.map(CreatedResult)
    }
  }

  val deleteLayerOperation = (apiOperation[Unit]("deleteLayer")
    summary "delete an annotation layer"
    parameter pathParam[Int]("id").description("the id of the layer to delete"))

  delete("/layer/:id", operation(deleteLayerOperation)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Unit] = for {
        layerid <- paramT[Int]("id")
        secureContainer <- getSecureContainer()
        layer <- secureContainer.annotationManager.getLayer(layerid).orError()
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetById(layer.packageId)
          .orNotFound()
        (pkg, dataset) = packageAndDataset

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ManageAnnotationLayers))(
            dataset
          )
          .coreErrorToActionResult()
        _ <- secureContainer.datasetManager
          .assertNotLocked(dataset)
          .coreErrorToActionResult()
        id <- secureContainer.annotationManager.deleteLayer(layer).orError()
      } yield ()
      val is = result.value.map(OkResult)
    }
  }

  val updateLayerOperation = (apiOperation[AnnotationLayer]("updateLayer")
    summary "update an annotation layer"
    parameter bodyParam[UpdateLayerRequest]("updateLayerRequest")
    parameter pathParam[String]("id")
      .description("the ID of the Annotation Layer"))

  put("/layer/:id", operation(updateLayerOperation)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, AnnotationLayer] = for {
        req <- extractOrErrorT[UpdateLayerRequest](parsedBody)
        layerid <- paramT[Int]("id")
        secureContainer <- getSecureContainer()
        manager = secureContainer.annotationManager
        layer <- manager.getLayer(layerid).orNotFound()
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetById(layer.packageId)
          .orForbidden()
        (pkg, dataset) = packageAndDataset

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ManageAnnotationLayers))(
            dataset
          )
          .coreErrorToActionResult()
        _ <- secureContainer.datasetManager
          .assertNotLocked(dataset)
          .coreErrorToActionResult()
        newColor = req.color getOrElse layer.color
        updated <- manager
          .updateLayer(layer.copy(name = req.name, color = newColor))
          .orError()
      } yield updated
      val is = result.value.map(OkResult)
    }
  }

  val createLayerOperation = (apiOperation[AnnotationLayer]("createLayer")
    summary "creates an annotation layer"
    parameter bodyParam[CreateLayerRequest]("createLayerRequest"))

  post("/layer", operation(createLayerOperation)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, AnnotationLayer] = for {
        req <- extractOrErrorT[CreateLayerRequest](parsedBody)
        secureContainer <- getSecureContainer()
        traceId <- getTraceId(request)
        manager = secureContainer.annotationManager

        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(req.annotatedItem)
          .orNotFound()
        (pkg, dataset) = packageAndDataset

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ManageAnnotationLayers))(
            dataset
          )
          .coreErrorToActionResult()
        _ <- secureContainer.datasetManager
          .assertNotLocked(dataset)
          .coreErrorToActionResult()
        existingLayers <- manager.findLayers(pkg).orError()
        randomColor = req.color orElse Colors.randomNewColor(
          existingLayers.map(_.color).toSet
        )
        layer <- manager
          .createLayer(pkg, req.name, randomColor.getOrElse(""))
          .orError()

        _ <- auditLogger
          .message()
          .append("annotation-id", req.annotatedItem)
          .append("dataset-id", dataset.id)
          .append("dataset-node-id", dataset.nodeId)
          .append("package-id", pkg.id)
          .append("package-node-id", pkg.nodeId)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult()

      } yield layer

      val is = result.value.map(CreatedResult)
    }
  }

  val deleteAnnotationOperation = (apiOperation[Int]("deleteAnnotation")
    summary "delete an annotation"
    parameter pathParam[String]("id").description("the id of the annotation")
    )

  delete("/:id", operation(deleteAnnotationOperation)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Int] = for {
        traceId <- getTraceId(request)
        secureContainer <- getSecureContainer()
        annotationId <- paramT[Int]("id")
        annotation <- secureContainer.annotationManager
          .get(annotationId)
          .orNotFound()
        packageId <- secureContainer.annotationManager
          .findPackageId(annotation)
          .orError()
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetById(packageId)
          .orForbidden()
        (pkg, dataset) = packageAndDataset

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ManageAnnotations))(dataset)
          .coreErrorToActionResult()
        _ <- secureContainer.datasetManager
          .assertNotLocked(dataset)
          .coreErrorToActionResult()

        deleted <- secureContainer.annotationManager
          .delete(annotation)
          .leftMap { ge =>
            ge match {
              case ie: IntegrityError => BadRequest(ie.message)
              case other => InternalServerError(other.getMessage)
            }
          }

        _ <- auditLogger
          .message()
          .append("annotation-id", annotationId)
          .append("dataset-id", dataset.id)
          .append("dataset-node-id", dataset.nodeId)
          .append("package-id", pkg.id)
          .append("package-node-id", pkg.nodeId)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult()

      } yield deleted

      override val is = result.value.map(OkResult)
    }
  }

}
