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
import akka.http.scaladsl.model.HttpResponse
import cats.implicits._
import cats.data.EitherT
import com.pennsieve.core.utilities.{ checkOrError, checkOrErrorT }
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits.FutureEitherT
import com.pennsieve.discover.client.definitions.FileTreeWithOrgPage
import com.pennsieve.discover.client.file.{
  GetFileFromSourcePackageIdResponse,
  FileClient => DiscoverClient
}
import com.pennsieve.domain.{
  CoreError,
  NotFound,
  PredicateError,
  ServiceError,
  UnauthorizedError
}
import com.pennsieve.dtos.Builders.{
  dataCanvasDTO,
  dataCanvasFolderDTO,
  datacanvasDTOs,
  packageDTO
}
import com.pennsieve.dtos.{
  DataCanvasDTO,
  DataCanvasFolderDTO,
  DownloadManifestDTO,
  DownloadManifestEntry,
  DownloadManifestHeader,
  DownloadRequest,
  PackageDTO
}
import com.pennsieve.helpers.APIContainers.{
  InsecureAPIContainer,
  SecureContainerBuilderType
}
import com.pennsieve.helpers.ObjectStore
import com.pennsieve.helpers.ResultHandlers.{
  CreatedResult,
  NoContentResult,
  OkResult
}
import com.pennsieve.helpers.either.EitherTErrorHandler.implicits._
import com.pennsieve.models.{
  DataCanvasFolderPath,
  DataCanvasPackage,
  Package,
  Role,
  Utilities
}
import org.joda.time.DateTime
import org.json4s.{ JNothing, JValue }
import org.scalatra.{ ActionResult, AsyncResult, BadRequest, ScalatraServlet }
import org.scalatra.swagger.Swagger

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._

case class CreateDataCanvasRequest(
  name: String,
  description: String,
  isPublic: Option[Boolean] = None,
  status: Option[String] = None
)

case class UpdateDataCanvasRequest(
  name: Option[String] = None,
  description: Option[String] = None,
  isPublic: Option[Boolean] = None,
  status: Option[String] = None
)

case class AttachPackageRequest(packageId: String, isPublic: Boolean = true)

case class AttachPackageResponse(packageId: String, isPublic: Boolean = true)

case class CreateDataCanvasFolder(name: String, parent: Option[Int])
case class RenameDataCanvasFolder(oldName: String, newName: String)
case class MoveDataCanvasFolder(oldParent: Int, newParent: Int)

class DataCanvasController(
  val insecureContainer: InsecureAPIContainer,
  val secureContainerBuilder: SecureContainerBuilderType,
  objectStore: ObjectStore,
  discoverFileClient: DiscoverClient,
  implicit
  val system: ActorSystem,
  asyncExecutor: ExecutionContext
)(implicit
  val swagger: Swagger
) extends ScalatraServlet
    with AuthenticatedController {

  override protected implicit def executor: ExecutionContext = asyncExecutor

  override val pennsieveSwaggerTag = "DataCanvas"

  implicit class JValueExtended(value: JValue) {
    def hasField(childString: String): Boolean =
      (value \ childString) != JNothing
  }

  /**
    * Handle errors from the Guardrail client.
    *
    * These are either HTTP responses that are not documented in the Swagger
    * file, or the error thrown by a failed Future during the request.
    */
  private def handleGuardrailError(
    implicit
    /* ec: ExecutionContext, */
    system: ActorSystem
  ): Either[Throwable, HttpResponse] => Future[CoreError] =
    _.fold(
      error => Future.successful(ServiceError(error.toString)),
      resp =>
        resp.entity.toStrict(1.second).map { entity =>
          ServiceError(s"HTTP ${resp.status}: ${entity.data.utf8String}")
        }
    )

  //
  // DataCanvas operations
  //

  /**
    * GET the DataCanvases owned by the requesting user
    */
  get(
    "/",
    operation(
      apiOperation[List[DataCanvasDTO]]("getOwnedDataCanvases")
        summary "gets the data-canvases owner by the user"
    )
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Seq[DataCanvasDTO]] = for {
        secureContainer <- getSecureContainer()
        userId = secureContainer.user.id

        canvases <- secureContainer.dataCanvasManager
          .getForUser(userId = userId, withRole = Role.Owner)
          .coreErrorToActionResult()

        dtos <- datacanvasDTOs(canvases)(
          asyncExecutor,
          secureContainer,
          system,
          jwtConfig
        ).coreErrorToActionResult()

      } yield dtos
      override val is = result.value.map(OkResult(_))
    }
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
        secureContainer <- getSecureContainer()

        datacanvas <- secureContainer.dataCanvasManager
          .getById(datacanvasId)
          .coreErrorToActionResult()

        dto <- dataCanvasDTO(datacanvas)(
          asyncExecutor,
          secureContainer,
          system,
          jwtConfig
        ).coreErrorToActionResult()

      } yield dto

      override val is = result.value.map(OkResult(_))
    }
  }

  //
  // Public DataCanvas API endpoints
  //

  /**
    * Get a public DataCanvas by node id
    */
  get(
    "/get/:nodeId",
    operation(
      apiOperation[DataCanvasDTO]("getPublicDataCanvas")
        summary "gets a public data-canvas regardless of organization"
        parameters (
          pathParam[String]("nodeId").description("the data-canvas node id")
        )
    )
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, DataCanvasDTO] = for {
        nodeId <- paramT[String]("nodeId")
        secureContainer <- getSecureContainer()

        response <- secureContainer.allDataCanvasesViewManager
          .get(nodeId)
          .coreErrorToActionResult()

        organizationId = response._1
        datacanvas = response._2

        _ <- checkOrErrorT[CoreError](datacanvas.isPublic)(
          UnauthorizedError("data-canvas is not publicly available")
        ).coreErrorToActionResult()

        dto <- dataCanvasDTO(datacanvas)(
          asyncExecutor,
          secureContainer,
          system,
          jwtConfig
        ).coreErrorToActionResult()

      } yield dto

      override val is = result.value.map(OkResult(_))
    }
  }

  /**
    * Get all public DataCanvases for an organization
    */
  get(
    "/get/organization/:orgNodeId",
    operation(
      apiOperation[List[DataCanvasDTO]](
        "getPublicDataCanvasesForAnOrganization"
      )
        summary "gets all public data-canvas for an organization"
        parameters (
          pathParam[String]("orgNodeId").description("the organization node id")
        )
    )
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, List[DataCanvasDTO]] = for {
        orgNodeId <- paramT[String]("orgNodeId")
        secureContainer <- getSecureContainer

        organization <- secureContainer.organizationManager
          .getByNodeId(orgNodeId)
          .coreErrorToActionResult

        response <- secureContainer.allDataCanvasesViewManager
          .getForOrganization(organization.id, isPublic = true)
          .coreErrorToActionResult

        datacanvases = response.map(x => x._2)

        dtos <- datacanvasDTOs(datacanvases)(
          asyncExecutor,
          secureContainer,
          system,
          jwtConfig
        ).coreErrorToActionResult

      } yield dtos

      override val is = result.value.map(OkResult(_))
    }
  }

  //
  // DataCanvas: create, update, delete
  //

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
        secureContainer <- getSecureContainer()
        body <- extractOrErrorT[CreateDataCanvasRequest](parsedBody)

        status <- body.status match {
          case Some(name) =>
            secureContainer.db
              .run(secureContainer.datasetStatusManager.getByName(name))
              .toEitherT
              .coreErrorToActionResult()
          case None => // Use the default status
            secureContainer.db
              .run(secureContainer.datasetStatusManager.getDefaultStatus)
              .toEitherT
              .coreErrorToActionResult()
        }

        newDataCanvas <- secureContainer.dataCanvasManager
          .create(
            body.name,
            body.description,
            isPublic = body.isPublic,
            statusId = Some(status.id)
          )
          .coreErrorToActionResult()

        // TODO: changelog event: Created DataCanvas

        dto <- dataCanvasDTO(newDataCanvas)(
          asyncExecutor,
          secureContainer,
          system,
          jwtConfig
        ).orError()
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
        secureContainer <- getSecureContainer()
        body <- extractOrErrorT[UpdateDataCanvasRequest](parsedBody)

        dataCanvas <- secureContainer.dataCanvasManager
          .getById(id)
          .orNotFound()

        oldStatus <- secureContainer.datasetStatusManager
          .get(dataCanvas.statusId)
          .coreErrorToActionResult()

        newStatus <- body.status match {
          case Some(name) => {
            secureContainer.db
              .run(secureContainer.datasetStatusManager.getByName(name))
              .toEitherT
              .coreErrorToActionResult()
          }
          case None => // Keep existing status
            EitherT.rightT[Future, ActionResult](oldStatus)
        }

        updatedDataCanvas <- secureContainer.dataCanvasManager
          .update(
            dataCanvas.copy(
              name = body.name.getOrElse(dataCanvas.name),
              description = body.description.getOrElse(dataCanvas.description),
              statusId = newStatus.id,
              isPublic = body.isPublic.getOrElse(false)
            )
          )
          .orBadRequest()

        // TODO: changelog event: Updated DataCanvas

        dto <- dataCanvasDTO(updatedDataCanvas)(
          asyncExecutor,
          secureContainer,
          system,
          jwtConfig
        ).orError()
      } yield dto

      override val is = result.value.map(CreatedResult(_))
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
        secureContainer <- getSecureContainer()

        dataCanvas <- secureContainer.dataCanvasManager
          .getById(id)
          .orNotFound()

        _ <- secureContainer.dataCanvasManager
          .delete(dataCanvas)
          .orForbidden()

      } yield Done
      override val is = result.value.map(NoContentResult)
    }
  }

  //
  // Folder operations
  //

  /**
    * Get a Folder
    */
  get(
    "/:canvasId/folder/:folderId",
    operation(
      apiOperation[DataCanvasFolderDTO]("getDataCanvasFolder")
        summary "get a data-canvas folder"
        parameters (
          pathParam[Int]("canvasId").description("data-canvas id"),
          pathParam[Int]("folderId").description("folder id")
      )
    )
  ) {
    new AsyncResult() {
      val result: EitherT[Future, ActionResult, DataCanvasFolderDTO] = for {
        canvasId <- paramT[Int]("canvasId")
        folderId <- paramT[Int]("folderId")
        secureContainer <- getSecureContainer()

        canvas <- secureContainer.dataCanvasManager
          .getById(canvasId)
          .orNotFound()

        folder <- secureContainer.dataCanvasManager
          .getFolder(canvas.id, folderId)
          .orNotFound()

        dto <- dataCanvasFolderDTO(folder)(
          asyncExecutor,
          secureContainer,
          system,
          jwtConfig
        ).coreErrorToActionResult()

      } yield dto

      override val is = result.value.map(OkResult(_))
    }
  }

  /**
    * Create a Folder
    */
  post(
    "/:canvasId/folder",
    operation(
      apiOperation[DataCanvasFolderDTO]("createDataCanvasFolder")
        summary "create a data-canvas folder"
        parameters (
          pathParam[Int]("canvasId").description("data-canvas id"),
          bodyParam[CreateDataCanvasFolder]("body")
            .description("name of folder and parent folder id (optional)")
      )
    )
  ) {
    new AsyncResult() {
      val result: EitherT[Future, ActionResult, DataCanvasFolderDTO] = for {
        canvasId <- paramT[Int]("canvasId")
        body <- extractOrErrorT[CreateDataCanvasFolder](parsedBody)
        secureContainer <- getSecureContainer()

        canvas <- secureContainer.dataCanvasManager
          .getById(canvasId)
          .orNotFound()

        parentFolder <- body.parent match {
          case Some(parentId) =>
            secureContainer.dataCanvasManager
              .getFolder(canvas.id, parentId)
              .orNotFound()
          case None =>
            secureContainer.dataCanvasManager
              .getRootFolder(canvas.id)
              .orNotFound()
        }

        folder <- secureContainer.dataCanvasManager
          .createFolder(canvas.id, body.name, Some(parentFolder.id))
          .orBadRequest()

        dto <- dataCanvasFolderDTO(folder)(
          asyncExecutor,
          secureContainer,
          system,
          jwtConfig
        ).coreErrorToActionResult()

      } yield dto

      override val is = result.value.map(CreatedResult(_))
    }
  }

  /**
    * Rename a Folder
    */
  put(
    "/:canvasId/folder/:folderId/rename",
    operation(
      apiOperation[DataCanvasFolderDTO]("renameDataCanvasFolder")
        summary "rename a data-canvas folder"
        parameters (
          pathParam[Int]("canvasId").description("data-canvas id"),
          pathParam[Int]("folderId").description("folder id"),
          bodyParam[RenameDataCanvasFolder]("body")
            .description("the folder's current name and new name")
      )
    )
  ) {
    new AsyncResult() {
      val result: EitherT[Future, ActionResult, DataCanvasFolderDTO] = for {
        canvasId <- paramT[Int]("canvasId")
        folderId <- paramT[Int]("folderId")
        body <- extractOrErrorT[RenameDataCanvasFolder](parsedBody)
        secureContainer <- getSecureContainer()

        _ <- secureContainer.dataCanvasManager
          .getById(canvasId)
          .orNotFound()

        folder <- secureContainer.dataCanvasManager
          .getFolder(canvasId, folderId)
          .orNotFound()

        updatedFolder <- secureContainer.dataCanvasManager
          .updateFolder(folder.copy(name = body.newName))
          .orBadRequest()

        dto <- dataCanvasFolderDTO(updatedFolder)(
          asyncExecutor,
          secureContainer,
          system,
          jwtConfig
        ).coreErrorToActionResult()

      } yield dto

      override val is = result.value.map(CreatedResult(_))
    }
  }

  /**
    * Move a Folder
    */
  put(
    "/:canvasId/folder/:folderId/move",
    operation(
      apiOperation[DataCanvasFolderDTO]("moveDataCanvasFolder")
        summary "move a data-canvas folder"
        parameters (
          pathParam[Int]("canvasId").description("data-canvas id"),
          pathParam[Int]("folderId").description("folder id"),
          bodyParam[MoveDataCanvasFolder]("body")
            .description("the folder's current parent and new parent")
      )
    )
  ) {
    new AsyncResult() {
      val result: EitherT[Future, ActionResult, DataCanvasFolderDTO] = for {
        canvasId <- paramT[Int]("canvasId")
        folderId <- paramT[Int]("folderId")
        body <- extractOrErrorT[MoveDataCanvasFolder](parsedBody)
        secureContainer <- getSecureContainer()

        _ <- secureContainer.dataCanvasManager
          .getById(canvasId)
          .orNotFound()

        folder <- secureContainer.dataCanvasManager
          .getFolder(canvasId, folderId)
          .orNotFound()

        _ <- secureContainer.dataCanvasManager
          .getFolder(canvasId, body.oldParent)
          .orNotFound()

        _ <- secureContainer.dataCanvasManager
          .getFolder(canvasId, body.newParent)
          .orNotFound()

        updatedFolder <- secureContainer.dataCanvasManager
          .updateFolder(folder.copy(parentId = Some(body.newParent)))
          .orBadRequest()

        dto <- dataCanvasFolderDTO(updatedFolder)(
          asyncExecutor,
          secureContainer,
          system,
          jwtConfig
        ).coreErrorToActionResult()

      } yield dto

      override val is = result.value.map(CreatedResult(_))
    }
  }

  /**
    * Delete a Folder
    */
  delete(
    "/:canvasId/folder/:folderId",
    operation(
      apiOperation[Done]("deleteDataCanvasFolder")
        summary "delete a data-canvas folder and its sub-folders"
        parameters (
          pathParam[Int]("canvasId").description("data-canvas id"),
          pathParam[Int]("folderId").description("folder id")
      )
    )
  ) {
    new AsyncResult() {
      val result: EitherT[Future, ActionResult, Done] = for {
        canvasId <- paramT[Int]("canvasId")
        folderId <- paramT[Int]("folderId")

        secureContainer <- getSecureContainer()

        _ <- secureContainer.dataCanvasManager
          .getById(canvasId)
          .orNotFound()

        folder <- secureContainer.dataCanvasManager
          .getFolder(canvasId, folderId)
          .orNotFound()

        _ <- secureContainer.dataCanvasManager
          .deleteFolder(folder)
          .orBadRequest()

      } yield Done

      override val is = result.value.map(NoContentResult(_))
    }
  }

  /**
    * Get folder paths
    */
  get(
    "/:canvasId/folder/paths",
    operation(
      apiOperation[List[DataCanvasFolderPath]]("getDataCanvasFolderPaths")
        summary "get a data-canvas folder paths"
        parameters (
          pathParam[Int]("canvasId").description("data-canvas id")
        )
    )
  ) {
    new AsyncResult() {
      val result: EitherT[Future, ActionResult, List[DataCanvasFolderPath]] =
        for {
          canvasId <- paramT[Int]("canvasId")
          secureContainer <- getSecureContainer

          canvas <- secureContainer.dataCanvasManager
            .getById(canvasId)
            .orNotFound()

          paths <- secureContainer.dataCanvasManager
            .getFolderPaths(canvas.id)
            .orNotFound()

        } yield paths.toList
      override val is = result.value.map(OkResult(_))
    }
  }

  //
  // Package operations
  //

  case class PackageLookupResult(
    datasetId: Option[Int],
    packageId: Option[Int],
    packageNodeId: Option[String]
  )

  /**
    * attach a Package to a DataCanvas
    */
  // TODO: should this return an ExtendedPackageDTO ??
  post(
    "/:canvasId/folder/:folderId/package",
    operation(
      apiOperation[AttachPackageResponse]("addPackageToDataCanvas")
        summary "add a package to a data-canvas"
        parameters (
          pathParam[String]("canvasId").description("data-canvas node id"),
          pathParam[String]("folderId").description("folder node id"),
          bodyParam[AttachPackageRequest]("body")
            .description("package to attach to data-canvas")
      )
    )
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, AttachPackageResponse] = for {
        canvasId <- paramT[String]("canvasId")
        folderId <- paramT[String]("folderId")
        body <- extractOrErrorT[AttachPackageRequest](parsedBody)
        secureContainer <- getSecureContainer()
        organization = secureContainer.organization

        canvas <- secureContainer.dataCanvasManager
          .getByNodeId(canvasId)
          .orNotFound()

        folder <- secureContainer.dataCanvasManager
          .getFolderByNodeId(folderId)
          .orNotFound()

        packageLookupResult <- body.isPublic match {
          case true =>
            discoverFileClient
              .getFileFromSourcePackageId(body.packageId)
              .leftSemiflatMap(handleGuardrailError)
              .flatMap {
                _.fold[EitherT[Future, CoreError, FileTreeWithOrgPage]](
                  handleOK = response => EitherT.pure(response),
                  handleNotFound = msg => EitherT.leftT(NotFound(msg))
                )
              }
              .coreErrorToActionResult()
              .map { _ =>
                PackageLookupResult(None, None, Some(body.packageId))
              }
          case false =>
            secureContainer.packageManager
              .getByNodeId(body.packageId)
              .orNotFound()
              .map(
                pkg =>
                  PackageLookupResult(Some(pkg.datasetId), Some(pkg.id), None)
              )
        }

        response <- body.isPublic match {
          case true =>
            for {
              _ <- secureContainer.dataCanvasManager
                .attachPublicPackage(canvas.id, folder.id, body.packageId)
                .orBadRequest()
            } yield
              AttachPackageResponse(
                packageId = body.packageId,
                isPublic = body.isPublic
              )
          case false =>
            for {
              _ <- secureContainer.dataCanvasManager
                .attachPackage(
                  canvas.id,
                  folder.id,
                  packageLookupResult.datasetId.get,
                  packageLookupResult.packageId.get,
                  organization.id
                )
                .orBadRequest()
            } yield
              AttachPackageResponse(
                packageId = body.packageId,
                isPublic = body.isPublic
              )
        }

      } yield response

      override val is = result.value.map(CreatedResult(_))
    }
  }

  /**
    * detach a Package from a DataCanvas
    */
  // TODO: refactor - change :canvasId, :folderId, :packageId to type String (node ids)
  // TODO: refactor - canvas and folder lookup by node id
  // TODO: figure out how to handle Private vs. Public packages
  // TODO: maybe we need to try removing from both tables?
  delete(
    "/:canvasId/folder/:folderId/package/:packageId",
    operation(
      apiOperation[Done]("removePackageFromDataCanvas")
        summary "remove a package from a data-canvas"
        parameters (
          pathParam[Int]("canvasId").description("data-canvas id"),
          pathParam[Int]("folderId").description("data-canvas folder id"),
          pathParam[Int]("packageId").description("package id")
      )
    )
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Done] = for {
        canvasId <- paramT[Int]("canvasId")
        folderId <- paramT[Int]("folderId")
        packageId <- paramT[Int]("packageId")
        secureContainer <- getSecureContainer()

        _ = println(
          s">>> API >>> delete() canvasId: ${canvasId} folderId: ${folderId} packageId: ${packageId}"
        )

        canvas <- secureContainer.dataCanvasManager
          .getById(canvasId)
          .orNotFound()

        _ = println(s">>> API >>> canvas: ${canvas}")

        folder <- secureContainer.dataCanvasManager
          .getFolder(canvas.id, folderId)
          .orNotFound()

        _ = println(s">>> API >>> folder: ${folder}")

        pkg <- secureContainer.packageManager
          .get(packageId)
          .orNotFound()

        _ = println(s">>> API >>> pkg: ${pkg}")

        dataCanvasPackage <- secureContainer.dataCanvasManager
          .getPackage(folder.id, pkg.datasetId, pkg.id)
          .coreErrorToActionResult()

        _ = println(s">>> API >>> dataCanvasPackage: ${dataCanvasPackage}")

        _ <- secureContainer.dataCanvasManager
          .detachPackage(dataCanvasPackage)
          .orForbidden()

      } yield Done

      override val is = result.value.map(NoContentResult)
    }
  }

  //
  // generate download manifest
  //
  val downloadManifestOperation = (apiOperation[DownloadManifestDTO](
    "download-manifest"
  )
    summary "returns the tree structure, including signed s3 urls and the corresponding paths that will make up an archive to download"
    parameter bodyParam[DownloadRequest]("body")
      .description(
        "nodeIds: packages to include in the download, fileIds: optional, only return the provided files"
      ))

  post("/download-manifest", operation(downloadManifestOperation)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, DownloadManifestDTO] =
        for {
          secureContainer <- getSecureContainer()
          body <- extractOrErrorT[DownloadRequest](parsedBody)
          nodeId = body.nodeIds.head

          canvas <- secureContainer.dataCanvasManager
            .getByNodeId(nodeId)
            .coreErrorToActionResult()

          folderPaths <- secureContainer.dataCanvasManager
            .getFolderPaths(canvas.id)
            .coreErrorToActionResult

          folderPathMap = folderPaths.map(path => path.id -> path).toMap

          canvasPackages <- secureContainer.dataCanvasManager
            .getPackages(canvas.id)
            .coreErrorToActionResult

          orgPackageFolderMap = canvasPackages.groupBy { i =>
            (i.organizationId, i.packageId)
          }

          orgPackageList = canvasPackages.groupBy { i =>
            i.organizationId
          }

          orgId = secureContainer.organization.id

          packageIds = orgPackageList
            .getOrElse(orgId, List())
            .map(_.packageId)
            .toList

          packageHierarchy <- secureContainer.packageManager
            .getPackageHierarchyForOrg(orgId, packageIds)
            .coreErrorToActionResult

          (datasetIds, rootNodeIds, downloadResponse) = packageHierarchy
            .foldLeft(
              (
                Set.empty[Int],
                Set.empty[String],
                DownloadManifestDTO(
                  DownloadManifestHeader(0, 0L),
                  List.empty[DownloadManifestEntry]
                )
              )
            ) {
              case ((datasetIds, rootNodeIds, downloadResponse), p) => {
                val canvasFolderPathNames = {
                  orgPackageFolderMap.get((orgId, p.packageId)) match {
                    case Some(dcp) =>
                      folderPathMap.get(dcp.head.dataCanvasFolderId) match {
                        case Some(dcfp) => dcfp.pathNames.toList
                        case None => List[String]()
                      }
                    case None => List[String]()
                  }
                }
                val packagePathNames =
                  if (p.packageFileCount === 1)
                    p.packageNamePath.toList
                  else p.packageNamePath.toList :+ p.packageName
                val newEntry: DownloadManifestEntry = DownloadManifestEntry(
                  nodeId = p.nodeId,
                  fileName = p.fileName,
                  packageName = p.packageName,
                  path = canvasFolderPathNames ++ packagePathNames,
                  url = objectStore
                    .getPresignedUrl(
                      p.s3Bucket,
                      p.s3Key,
                      DateTime.now.plusMinutes(180).toDate
                    )
                    .right
                    .get,
                  size = p.size,
                  fileExtension = Utilities.getFullExtension(p.s3Key)
                )
                (
                  datasetIds + p.datasetId,
                  rootNodeIds + p.nodeIdPath.headOption
                    .getOrElse(p.nodeId),
                  DownloadManifestDTO(
                    DownloadManifestHeader(
                      downloadResponse.header.count + 1,
                      downloadResponse.header.size + p.size
                    ),
                    downloadResponse.data :+ newEntry
                  )
                )
              }
            }

        } yield downloadResponse

      override val is = result.value.map(CreatedResult)
    }
  }
}
