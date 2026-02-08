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

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.implicits._
import com.pennsieve.audit.middleware.Auditor
import com.pennsieve.auth.middleware.DatasetPermission
import com.pennsieve.clients.UrlShortenerClient
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.core.utilities.checkOrErrorT
import com.pennsieve.db.FilesTable.{ OrderByColumn, OrderByDirection }
import com.pennsieve.domain.StorageAggregation.spackages
import com.pennsieve.domain.{ CoreError, PredicateError, ServiceError }
import com.pennsieve.dtos.Builders.packageDTO
import com.pennsieve.dtos.{ FileDTO, _ }
import com.pennsieve.helpers.APIContainers.{
  InsecureAPIContainer,
  SecureAPIContainer,
  SecureContainerBuilderType
}
import com.pennsieve.helpers.ResultHandlers._
import com.pennsieve.helpers._
import com.pennsieve.helpers.either.EitherTErrorHandler.implicits._
import com.pennsieve.managers.PackageManager
import com.pennsieve.models.PackageState.{ READY, UNAVAILABLE, UPLOADED }
import com.pennsieve.models._
import com.pennsieve.web.Settings
import io.circe.syntax._
import org.apache.commons.io.FilenameUtils
import org.joda.time.DateTime
import org.scalatra._
import org.scalatra.swagger.Swagger

import java.net.URL
import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

case class CreatePackageRequest(
  name: String,
  description: Option[String],
  externalLocation: Option[String],
  parent: Option[String],
  dataset: String,
  packageType: PackageType,
  state: Option[PackageState],
  owner: Option[String],
  properties: List[ModelPropertyRO]
)

case class UpdatePackageRequest(
  name: Option[String],
  description: Option[String],
  externalLocation: Option[String],
  packageType: Option[PackageType],
  state: Option[PackageState],
  uploader: Option[String],
  properties: List[ModelPropertyRO]
)

case class PackageObjectRequest(
  objectType: String,
  content: Any,
  properties: List[ModelPropertyRO]
)

case class CreateFileRequest(
  name: String,
  fileType: FileType,
  s3bucket: String,
  s3key: String,
  size: Option[Long]
)

case class DownloadItemResponse(url: String)

case class GetAnnotationsResponse(
  annotations: Map[Int, Seq[AnnotationDTO]],
  layers: List[AnnotationLayer],
  userMap: Map[String, UserDTO]
)

case class SetStorageRequest(size: Long)

case class SetStorageResponse(storageUse: Map[String, Long])

object PackagesController {
  //Default values for retrieving Package children (i.e. other packages)
  val PackageChildrenDefaultLimit: Int = 100
  val PackageChildrenMaxLimit: Int = 500
  val PackageChildrenDefaultOffset: Int = 0

  val FILES_LIMIT_DEFAULT: Int = 100
  val FILES_LIMIT_MAX: Int = 500
  val FILES_OFFSET_DEFAULT: Int = 0
}

class PackagesController(
  val insecureContainer: InsecureAPIContainer,
  val secureContainerBuilder: SecureContainerBuilderType,
  auditLogger: Auditor,
  objectStore: ObjectStore,
  urlShortenerClient: UrlShortenerClient,
  system: ActorSystem,
  asyncExecutor: ExecutionContext
)(implicit
  val swagger: Swagger
) extends ScalatraServlet
    with AuthenticatedController {

  import PackagesController._

  override protected implicit def executor: ExecutionContext = asyncExecutor

  override val pennsieveSwaggerTag = "Packages"

  /**
    * Extractors for paramT and optParamT support
    */
  implicit val orderByColumnParam =
    Param.enumParam(OrderByColumn)

  implicit val orderByDirectionParam =
    Param.enumParam(OrderByDirection)

  val createPackageOperation = (apiOperation[PackageDTO]("createPackage")
    summary "creates a new package"
    parameters (
      bodyParam[CreatePackageRequest]("body").description("package to create")
    ))

  post("/", operation(createPackageOperation)) {

    new AsyncResult {
      val result = for {
        secureContainer <- getSecureContainer()
        traceId <- getTraceId(request)
        user = secureContainer.user
        body <- extractOrErrorT[CreatePackageRequest](parsedBody)
        _ <- checkOrErrorT(body.name.nonEmpty)(
          BadRequest("package name must not be blank")
        )
        containingDataset <- secureContainer.datasetManager
          .getByNodeId(body.dataset)
          .orError()

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.EditFiles))(containingDataset)
          .coreErrorToActionResult()
        _ <- secureContainer.datasetManager
          .assertNotLocked(containingDataset)
          .coreErrorToActionResult()

        containingPackage <- body.parent.traverse(
          parent => secureContainer.packageManager.getByNodeId(parent).orError()
        )
        owner <- if (user.isSuperAdmin) {
          body.owner
            .map { ownerNodeId =>
              secureContainer.userManager.getByNodeId(ownerNodeId)
            }
            .getOrElse(EitherT.rightT[Future, CoreError](user))
            .coreErrorToActionResult()
        } else {
          EitherT.rightT[Future, ActionResult](user)
        }

        // Only super admins / service users can explicitly set state
        requestedState = if (user.isSuperAdmin)
          body.state
        else
          None

        state = requestedState.getOrElse(body.packageType match {
          case PackageType.Collection => READY
          case _ => UNAVAILABLE
        })

        properties = ModelPropertyRO.fromRequestObject(body.properties)
        newPackage <- secureContainer.packageManager
          .create(
            body.name,
            body.packageType,
            state,
            containingDataset,
            owner.id.some,
            containingPackage,
            attributes = properties,
            description = body.description,
            externalLocation = body.externalLocation
          )
          .coreErrorToActionResult()

        _ <- secureContainer.changelogManager
          .logEvent(
            containingDataset,
            ChangelogEventDetail.CreatePackage(newPackage, containingPackage)
          )
          .coreErrorToActionResult()

        _ <- secureContainer.datasetManager
          .touchUpdatedAtTimestamp(containingDataset)
          .coreErrorToActionResult()

        _ <- auditLogger
          .message()
          .append("dataset-id", containingDataset.id)
          .append("dataset-node-id", containingDataset.nodeId)
          .append("package-id", newPackage.id)
          .append("package-node-id", newPackage.nodeId)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult()

        dto <- packageDTO(newPackage, containingDataset)(
          asyncExecutor,
          secureContainer
        ).orError()
      } yield dto

      override val is = result.value.map(CreatedResult)
    }
  }

  val updateStorageParamKey: String = "updateStorage"
  val updatePackageOperation = (apiOperation[PackageDTO]("updatePackage")
    summary "updates a package"
    parameters (
      pathParam[String]("id")
        .description("package id (can be either a node id or an int id)"),
      bodyParam[UpdatePackageRequest]("body")
        .description("package node values to update"),
      queryParam[Boolean](updateStorageParamKey)
        .description("if set update this package's cached storage value")
  ))

  def getIdOrNodeId(s: String): Either[String, Int] =
    Try(s.toInt).toEither.leftMap(_ => s)

  def getPackageAndDatasetFromIdOrNodeId(
    packageManager: PackageManager,
    id: Either[String, Int]
  ): EitherT[Future, ActionResult, (Package, Dataset)] =
    id.bimap(
        nodeId => packageManager.getPackageAndDatasetByNodeId(nodeId),
        intId => packageManager.getPackageAndDatasetById(intId)
      )
      .valueOr(identity)
      .coreErrorToActionResult()

  put("/:id", operation(updatePackageOperation)) {
    new AsyncResult {
      val result = for {
        packageId <- paramT[String]("id")
        secureContainer <- getSecureContainer()
        maybeTraceId <- tryGetTraceId(request)
        user = secureContainer.user
        result <- getPackageAndDatasetFromIdOrNodeId(
          secureContainer.packageManager,
          getIdOrNodeId(packageId)
        )
        (oldPackage, dataset) = result

        sources <- secureContainer.fileManager
          .getSources(oldPackage)
          .coreErrorToActionResult()

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.EditFiles))(dataset)
          .coreErrorToActionResult()
        _ <- secureContainer.datasetManager
          .assertNotLocked(dataset)
          .coreErrorToActionResult()

        body <- extractOrErrorT[UpdatePackageRequest](parsedBody)

        _ <- checkOrErrorT(
          body.name.isEmpty || body.name.map(n => n.trim.nonEmpty) == Some(true)
        )(BadRequest("if defined, package name must not be blank"))

        properties = ModelProperty.merge(
          oldPackage.attributes,
          ModelPropertyRO.fromRequestObject(body.properties)
        )

        packageType = if (user.isSuperAdmin) {
          body.packageType.getOrElse(oldPackage.`type`)
        } else {
          oldPackage.`type`
        }

        state = if (user.isSuperAdmin) {
          body.state.getOrElse(oldPackage.state)
        } else {
          oldPackage.state
        }

        copiedPackage = oldPackage.copy(
          name = body.name.getOrElse(oldPackage.name),
          `type` = packageType,
          state = state,
          attributes = properties
        )

        updatedPackage <- secureContainer.packageManager
          .update(
            copiedPackage,
            description = body.description,
            externalLocation = body.externalLocation
          )
          .coreErrorToActionResult()

        _ <- if (oldPackage.name != updatedPackage.name) {
          if (sources.length == 1) {
            secureContainer.fileManager
              .renameFile(sources.head, updatedPackage.name)
          }

          for {
            parent <- secureContainer.packageManager
              .getParent(oldPackage)
              .coreErrorToActionResult()
            _ <- secureContainer.changelogManager
              .logEvent(
                dataset,
                ChangelogEventDetail.RenamePackage(
                  pkg = oldPackage,
                  oldName = oldPackage.name,
                  newName = updatedPackage.name,
                  parent = parent
                )
              )
              .coreErrorToActionResult()
          } yield ()
        } else EitherT.rightT[Future, ActionResult](())

        _ <- secureContainer.datasetManager
          .touchUpdatedAtTimestamp(dataset)
          .coreErrorToActionResult()

        shouldSetStorage = params.contains(updateStorageParamKey)
        storage <- if (shouldSetStorage) {
          secureContainer.storageManager
            .setPackageStorage(oldPackage)
            .orError()
            .map(_.some)
        } else {
          secureContainer.storageManager
            .getStorage(spackages, List(updatedPackage.id))
            .orError()
            .map(_.get(updatedPackage.id).flatten)
        }
        dto <- packageDTO(updatedPackage, dataset, storage = storage)(
          asyncExecutor,
          secureContainer
        ).coreErrorToActionResult()

        _ <- maybeTraceId match {
          case Some(traceId) =>
            auditLogger
              .message()
              .append("dataset-id", dataset.id)
              .append("dataset-node-id", dataset.nodeId)
              .append("package-id", updatedPackage.id)
              .append("package-node-id", updatedPackage.nodeId)
              .log(traceId)
              .toEitherT
              .coreErrorToActionResult()
          case _ => EitherT.rightT[Future, ActionResult](())
        }

      } yield dto

      override val is = result.value.map(OkResult)
    }
  }

  val getPackageOperation = (apiOperation[PackageDTO]("getPackage")
    summary "gets a package and optionally objects that are associated with it"
    parameters (
      pathParam[String]("id").description("package id"),
      queryParam[String]("include")
        .description("a csv of object types i.e. sources, files, view"),
      queryParam[Boolean]("includeAncestors")
        .description("whether or not to include ancestors"),
      queryParam[Boolean]("startAtEpoch").optional
        .defaultValue(false)
        .description(
          "if the package contains channels, reset the channels to start at 0"
        ),
      queryParam[Int]("limit").optional
        .description("max number of dataset children (i.e. packages) returned")
        .defaultValue(PackagesController.PackageChildrenDefaultLimit),
      queryParam[Int]("offset").optional
        .description("offset used for pagination of children")
        .defaultValue(PackagesController.PackageChildrenDefaultOffset)
  ))

  get("/:id", operation(getPackageOperation)) {

    def validateInclude(csv: String): Set[FileObjectType] = {
      val parts = csv.split(",")
      if (parts.forall(_.matches("[a-zA-Z0-9]*"))) {
        // Anything that produces None from FileObjectType.withNameOption
        // will be filtered out:
        parts.flatMap(FileObjectType.withNameOption).toSet
      } else {
        Set()
      }
    }

    new AsyncResult {
      val result: EitherT[Future, ActionResult, PackageDTO] = for {
        secureContainer <- getSecureContainer()
        packageId <- paramT[String]("id")
        limit <- paramT[Int](
          "limit",
          default = PackagesController.PackageChildrenDefaultLimit
        )
        offset <- paramT[Int](
          "offset",
          default = PackagesController.PackageChildrenDefaultOffset
        )
        traceId <- getTraceId(request)
        result <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(packageId)
          .coreErrorToActionResult()
        (pkg, dataset) = result

        updatedPackage = pkg

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ViewFiles))(dataset)
          .coreErrorToActionResult()

        includeParam <- optParamT[String]("include")
        _ <- checkOrErrorT(
          includeParam.forall(s => s.matches("[a-zA-Z0-9,]*"))
        )(BadRequest("include parameters must be alpha-numeric"))

        include = includeParam.map(validateInclude)

        includeAncestors <- paramT[Boolean]("includeAncestors", default = false)
        includeChildren = updatedPackage.`type` == PackageType.Collection

        startAtEpoch <- paramT[Boolean]("startAtEpoch", default = false)

        storageMap <- secureContainer.storageManager
          .getStorage(spackages, List(updatedPackage.id))
          .orError()
        storage = storageMap.get(updatedPackage.id).flatten

        dto <- packageDTO(
          updatedPackage,
          dataset,
          includeAncestors,
          includeChildren,
          include,
          storage = storage,
          limit = limit.min(PackagesController.PackageChildrenMaxLimit).some,
          offset = offset.some
        )(asyncExecutor, secureContainer).orError()

        _ <- auditLogger
          .message()
          .append("dataset-id", dataset.id)
          .append("dataset-node-id", dataset.nodeId)
          .append("package-id", updatedPackage.id)
          .append("package-node-id", updatedPackage.nodeId)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult()

      } yield {
        // reset the start time for any channels in this package if
        // the startAtEpoch flag was set
        dto.channels match {
          case Some(channels) if startAtEpoch =>
            val minimumChannelStartTime =
              TimeSeriesHelper.getPackageStartTime(channels)
            dto.copy(
              channels = Some(
                channels.map(
                  TimeSeriesHelper
                    .resetChannelDTOStartTime(minimumChannelStartTime)
                )
              )
            )
          case _ => dto
        }
      }

      override val is = result.value.map(OkResult)
    }
  }

  val downloadManifestOperation = (apiOperation[DownloadManifestDTO](
    "download-package-manifest"
  )
    summary "returns the tree structure, including signed s3 urls and the corresponding paths that will make up an archive to download"
    parameter bodyParam[DownloadRequest]("body")
      .description(
        "nodeIds: packages to include in the download, fileIds: optional, only return the provided files"
      ))

  post("/download-manifest", operation(downloadManifestOperation)) {

    new AsyncResult {
      val result =
        for {
          secureContainer <- getSecureContainer()
          body <- extractOrErrorT[DownloadRequest](parsedBody)

          packageHierarchy <- secureContainer.packageManager
            .getPackageHierarchy(body.nodeIds, body.fileIds)
            .coreErrorToActionResult()

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
                val newEntry: DownloadManifestEntry = DownloadManifestEntry(
                  nodeId = p.nodeId,
                  fileName = p.fileName,
                  packageName = p.packageName,
                  // append the package's own name to the path ONLY if it contains multiple files
                  path =
                    if (p.packageFileCount === 1)
                      p.packageNamePath.toList
                    else p.packageNamePath.toList :+ p.packageName,
                  url = objectStore
                    .getPresignedUrl(
                      p.s3Bucket,
                      p.s3Key,
                      DateTime.now.plusMinutes(180).toDate,
                      p.packageName
                    )
                    .toOption
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

          _ <- datasetIds.toList
            .traverse(
              datasetId =>
                secureContainer.authorizeDatasetId(
                  Set(DatasetPermission.ViewFiles)
                )(datasetId)
            )
            .coreErrorToActionResult()

          // these nodes did not exist in the result
          unfoundNodes = body.nodeIds.filter(
            nodeId => !rootNodeIds.contains(nodeId)
          )

          // but they could just be empty collections, in which case we don't want an error
          emptyCollectionNodeIds <- secureContainer.packageManager
            .getByNodeIds(unfoundNodes)
            .map(packages => packages.map(_.nodeId))
            .coreErrorToActionResult()

          _ <- unfoundNodes
            .traverse(
              nodeId =>
                checkOrErrorT[CoreError](
                  emptyCollectionNodeIds.contains(nodeId)
                )(PredicateError(s"$nodeId not found"))
            )
            .coreErrorToActionResult()

          validNodeIds = (body.nodeIds.toSet
            .diff(emptyCollectionNodeIds.toSet))
            .toList

          _ <- validNodeIds
            .traverse(
              nodeId =>
                checkOrErrorT[CoreError](rootNodeIds.contains(nodeId))(
                  PredicateError(s"$nodeId not found")
                )
            )
            .coreErrorToActionResult()

        } yield downloadResponse

      override val is = result.value.map(OkResult)
    }

  }

  def getMD5(f: File): Either[ActionResult, String] = {
    objectStore.getMD5(f.s3Bucket, f.s3Key)
  }

  def getPagedSources(
    pkg: Package,
    limit: Int,
    offset: Int,
    orderBy: Option[(OrderByColumn, OrderByDirection)],
    secureContainer: SecureAPIContainer
  ): EitherT[Future, ActionResult, PagedResponse[FileDTO]] =
    for {
      totalCount <- secureContainer.fileManager
        .getTotalSourceCount(pkg)
        .coreErrorToActionResult()

      _ <- secureContainer
        .authorizePackage(Set(DatasetPermission.ViewFiles))(pkg)
        .coreErrorToActionResult()

      sources <- secureContainer.fileManager
        .getSources(pkg, Some(limit), Some(offset), orderBy)
        .orNotFound()
      files = sources.map { f =>
        val md5 = getMD5(f).toOption
        FileDTO(f, pkg, md5)
      }.toList

    } yield
      PagedResponse(
        limit = limit.toLong,
        offset = offset.toLong,
        results = files,
        totalCount = Some(totalCount)
      )

  val allowableOrderByValues: immutable.IndexedSeq[String] =
    OrderByColumn.values.map(_.entryName)

  val allowableOrderByDirectionValues =
    OrderByDirection.values.map(_.entryName)

  val getPackageSourcesPaged = (apiOperation[PagedResponse[FileDTO]](
    "getPackageSourcesPaged"
  )
    summary "gets all sources of a package of the given id in a paged response"
    parameters (
      pathParam[String]("id").description("package id"),
      queryParam[Int]("limit")
        .description("max number of view files returned")
        .defaultValue(FILES_LIMIT_DEFAULT),
      queryParam[Int]("offset")
        .description("offset used for pagination of results")
        .defaultValue(FILES_OFFSET_DEFAULT),
      queryParam[String]("order-by")
        .description(s"which data field to sort results by")
        .allowableValues(allowableOrderByValues)
        .defaultValue(OrderByColumn.Name.entryName),
      queryParam[String]("order-by-direction")
        .description(s"which data field to order the results by")
        .allowableValues(allowableOrderByDirectionValues)
        .defaultValue(OrderByDirection.Asc.entryName)
  ))

  get("/:id/sources-paged", operation(getPackageSourcesPaged)) {

    new AsyncResult {
      val result: EitherT[Future, ActionResult, PagedResponse[FileDTO]] = for {
        traceId <- getTraceId(request)
        packageId <- paramT[String]("id")
        limit <- paramT[Int]("limit", default = FILES_LIMIT_DEFAULT)
        offset <- paramT[Int]("offset", default = FILES_OFFSET_DEFAULT)
        orderByDirection <- paramT[OrderByDirection](
          "order-by-direction",
          default = OrderByDirection.Asc
        )
        orderBy <- paramT[OrderByColumn](
          "order-by",
          default = OrderByColumn.Name
        )

        secureContainer <- getSecureContainer()
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(packageId)
          .orNotFound()
        (pkg, dataset) = packageAndDataset
        sources <- getPagedSources(
          pkg,
          limit.min(FILES_LIMIT_MAX),
          offset,
          Some((orderBy, orderByDirection)),
          secureContainer
        )

        organization = secureContainer.organization

        _ <- auditLogger
          .message()
          .append("dataset-id", dataset.id)
          .append("dataset-node-id", dataset.nodeId)
          .append("package-id", pkg.id)
          .append("package-node-id", pkg.nodeId)
          .append("description", s"Package (${packageId} sources")
          .append("organization", organization.id)
          .append("files", sources.results.map(_.content.id).toList: _*)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult()

      } yield sources

      override val is = result.value.map(OkResult)
    }
  }

  val getPackageSources = (apiOperation[List[FileDTO]]("getPackageSources")
    summary "gets all sources of a package of the given id"
    parameters (
      pathParam[String]("id").description("package id"),
      queryParam[Int]("limit")
        .description("max number of view files returned")
        .defaultValue(FILES_LIMIT_DEFAULT),
      queryParam[Int]("offset")
        .description("offset used for pagination of results")
        .defaultValue(FILES_OFFSET_DEFAULT)
  ) deprecate)

  get("/:id/sources", operation(getPackageSources)) {

    response.setHeader(
      "Warning",
      "299 - GET /packages/:id/sources is deprecated and will be removed by December 31, 2025"
    )

    new AsyncResult {
      val result: EitherT[Future, ActionResult, Seq[FileDTO]] = for {
        packageId <- paramT[String]("id")
        traceId <- getTraceId(request)
        limit <- paramT[Int]("limit", default = FILES_LIMIT_DEFAULT)
        offset <- paramT[Int]("offset", default = FILES_OFFSET_DEFAULT)
        secureContainer <- getSecureContainer()
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(packageId)
          .orNotFound()
        (pkg, dataset) = packageAndDataset
        sources <- getPagedSources(
          pkg,
          limit.min(FILES_LIMIT_MAX),
          offset,
          None,
          secureContainer
        )
        _ <- auditLogger
          .message()
          .append("dataset-id", dataset.id)
          .append("dataset-node-id", dataset.nodeId)
          .append("package-id", pkg.id)
          .append("package-node-id", pkg.nodeId)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult()
      } yield sources.results

      override val is = result.value.map(OkResult)
    }
  }

  type FileListDTO = List[FileDTO]

  val getPackageFiles = (apiOperation[FileListDTO]("getPackageFiles")
    summary "Gets all files of a package of the given id, if no files exist, returns sources"
    parameters (
      pathParam[String]("id").description("package id"),
      queryParam[Int]("limit")
        .description("max number of files returned")
        .defaultValue(FILES_LIMIT_DEFAULT),
      queryParam[Int]("offset")
        .description("offset used for pagination of results")
        .defaultValue(FILES_OFFSET_DEFAULT)
  ))

  get("/:id/files", operation(getPackageFiles)) {

    new AsyncResult {
      val result: EitherT[Future, ActionResult, List[FileDTO]] = for {
        packageId <- paramT[String]("id")
        traceId <- getTraceId(request)
        limit <- paramT[Int]("limit", default = FILES_LIMIT_DEFAULT)
        offset <- paramT[Int]("offset", default = FILES_OFFSET_DEFAULT)
        secureContainer <- getSecureContainer()
        organization = secureContainer.organization
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(packageId)
          .orNotFound()
        (pkg, dataset) = packageAndDataset

        _ <- secureContainer
          .authorizePackage(Set(DatasetPermission.ViewFiles))(pkg)
          .coreErrorToActionResult()

        files <- secureContainer.fileManager
          .getFiles(pkg, limit.min(FILES_LIMIT_MAX).some, offset.some)
          .orNotFound()

        _ <- auditLogger
          .message()
          .append("description", s"Package (${packageId} files")
          .append("organization", organization.id)
          .append("dataset-id", dataset.id)
          .append("dataset-node-id", dataset.nodeId)
          .append("package-id", pkg.id)
          .append("package-node-id", pkg.nodeId)
          .append("files", files.map(_.id): _*)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult()
      } yield {
        files.map(FileDTO(_, pkg)).toList
      }

      override val is = result.value.map(OkResult)
    }
  }

  val getPackageView = (apiOperation[List[FileDTO]]("getPackageView")
    summary "Gets view objects for a package of the given id, if no view objects exist, returns files, if no files exist, return sources"
    parameters (
      pathParam[String]("id").description("package id"),
      queryParam[Int]("limit")
        .description("max number of view files returned")
        .defaultValue(FILES_LIMIT_DEFAULT),
      queryParam[Int]("offset")
        .description("offset used for pagination of results")
        .defaultValue(FILES_OFFSET_DEFAULT)
  ))

  get("/:id/view", operation(getPackageView)) {

    new AsyncResult {
      val result = for {
        traceId <- getTraceId(request)
        packageId <- paramT[String]("id")
        limit <- paramT[Int]("limit", default = FILES_LIMIT_DEFAULT)
        offset <- paramT[Int]("offset", default = FILES_OFFSET_DEFAULT)
        secureContainer <- getSecureContainer()
        organization = secureContainer.organization
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(packageId)
          .orNotFound()
        (pkg, dataset) = packageAndDataset

        _ <- secureContainer
          .authorizePackage(Set(DatasetPermission.ViewFiles))(pkg)
          .coreErrorToActionResult()

        views <- secureContainer.fileManager
          .getViews(pkg, limit.min(FILES_LIMIT_MAX).some, offset.some)
          .orNotFound()

        _ <- auditLogger
          .message()
          .append("description", s"Package (${packageId} views")
          .append("organization", organization.id)
          .append("dataset-id", dataset.id)
          .append("dataset-node-id", dataset.nodeId)
          .append("package-id", pkg.id)
          .append("package-node-id", pkg.nodeId)
          .append("views", views.map(_.id): _*)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult()
      } yield {
        views.map(FileDTO(_, pkg))
      }

      override val is = result.value.map(OkResult)
    }
  }

  val getFileOperation = (apiOperation[DownloadItemResponse]("getFile")
    summary "returns a presigned s3 url for downloading a file"
    parameters (
      pathParam[String]("packageId").description("the id of the package"),
      pathParam[String]("id").description("the id of the file"),
      queryParam[Boolean]("short")
        .description("If true, shorten the URL")
        .defaultValue(false)
  ))

  get("/:packageId/files/:id", operation(getFileOperation)) {

    new AsyncResult {
      val s3url: EitherT[Future, ActionResult, URL] = for {
        packageId <- paramT[String]("packageId")
        traceId <- getTraceId(request)
        fileId <- paramT[Int]("id")
        short <- paramT[Boolean]("short", default = false)

        secureContainer <- getSecureContainer()
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(packageId)
          .orNotFound()
        (pkg, dataset) = packageAndDataset

        _ <- secureContainer
          .authorizePackage(Set(DatasetPermission.ViewFiles))(pkg)
          .coreErrorToActionResult()

        organization = secureContainer.organization
        file <- secureContainer.fileManager.get(fileId, pkg).orNotFound()

        url <- if (!short)
          objectStore
            .getPresignedUrl(
              file.s3Bucket,
              file.s3Key,
              DateTime.now.plusMinutes(Settings.url_time_limit).toDate,
              pkg.name
            )
            .toEitherT[Future]
        else
          // The Microsoft office viewer used by the frontend caps the length of
          // URLs that it accepts. AWS presigned URLs are far beyond that limit.
          // If a `short` URL is requested, we use an external URL shortener to
          // generate an appropriately sized URL. The TTL on shortened URLS is
          // also reduced since these files are small and will be opened
          // immediately.
          for {
            longUrl <- objectStore
              .getPresignedUrl(
                file.s3Bucket,
                file.s3Key,
                DateTime.now.plusMinutes(Settings.bitly_url_time_limit).toDate,
                pkg.name
              )
              .toEitherT[Future]
            shortUrl <- EitherT.right[ActionResult](
              urlShortenerClient.shortenUrl(longUrl)
            )
          } yield shortUrl

        _ <- auditLogger
          .message()
          .append("organization", organization.id)
          .append("dataset-id", dataset.id)
          .append("dataset-node-id", dataset.nodeId)
          .append("package-id", pkg.id)
          .append("package-node-id", pkg.nodeId)
          .append("file", fileId)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult()

      } yield url

      override val is = s3url.value.map(HandleResult(_) { url =>
        Ok(DownloadItemResponse(url.toString))
      })
    }
  }

  val downloadFileOperation = (apiOperation[DownloadItemResponse](
    "downloadFile"
  )
    summary "returns a presigned s3 url for downloading a file")

  get("""^\/(.*)/files/(.*)/presign/(.*)""".r, operation(downloadFileOperation)) {
    val captures: Seq[String] = multiParams("captures")

    //if a path is sent, prefix with a forward slash, otherwise leave it blank
    val filePath: Option[String] = Option(captures(2)) map (
      fn =>
        if (fn.isEmpty) {
          fn
        } else {
          s"/$fn"
        }
      )

    new AsyncResult {
      val s3url = for {
        secureContainer <- getSecureContainer()
        traceId <- getTraceId(request)
        packageId <- captures.headOption
          .toRight(BadRequest(Error("Missing package id")))
          .toEitherT[Future]
        fileId <- Option(captures(1))
          .toRight(BadRequest(Error("Missing pointer to file")))
          .toEitherT[Future]
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(packageId)
          .orNotFound()
        (pkg, dataset) = packageAndDataset

        _ <- secureContainer
          .authorizePackage(Set(DatasetPermission.ViewFiles))(pkg)
          .coreErrorToActionResult()

        organization = secureContainer.organization
        // Needed since `packageId` in `captures `is still expected to be a string UID, but file no longer is
        fileIdAsInt <- Try(fileId.toInt).toOption
          .toRight(BadRequest(Error("Not a file pointer")))
          .toEitherT[Future]
        file <- secureContainer.fileManager.get(fileIdAsInt, pkg).orNotFound()
        // TODO This is necessary since the File model no longer relies on nodeId
        // only it's ID (which is just an autoincremented int)
        _ <- auditLogger
          .message()
          .append("organization", organization.id)
          .append("dataset-id", dataset.id)
          .append("dataset-node-id", dataset.nodeId)
          .append("package-id", pkg.id)
          .append("package-node-id", pkg.nodeId)
          .append("file", file.id)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult()
        fileName = filePath.getOrElse("") //filePath might be empty if the whole key
        url <- objectStore
          .getPresignedUrl(
            file.s3Bucket,
            s"${file.s3Key}$fileName",
            DateTime.now.plusHours(10).toDate,
            pkg.name
          )
          .toEitherT[Future]
      } yield url

      override val is = s3url.value.map(
        u =>
          HandleResult(u) { url =>
            redirect(url.toString)
          }
      )
    }
  }

  val getAnnotationsOperation = (apiOperation[GetAnnotationsResponse](
    "getAnnotations"
  )
    summary "get annotations for package"
    parameter pathParam[String]("id")
      .description("the id of the annotated package"))

  get("/:id/annotations", operation(getAnnotationsOperation)) {

    // access to annotations is implied through the node to which they refer
    // if a user has access to that node, facilitated through the secure graph manager,
    // then the user has access to the annotations associated with it

    new AsyncResult {
      val result: EitherT[Future, ActionResult, GetAnnotationsResponse] = for {
        packageId <- paramT[String]("id")
        secureContainer <- getSecureContainer()
        pkg <- secureContainer.packageManager
          .getByNodeId(packageId)
          .orNotFound()

        _ <- secureContainer
          .authorizePackage(Set(DatasetPermission.ViewAnnotations))(pkg)
          .coreErrorToActionResult()

        annotations <- secureContainer.annotationManager.find(pkg).orError()
        users <- secureContainer.annotationManager
          .findAnnotationUsersForPackage(pkg)
          .orError()

        userIdMap = users.map(u => u.id -> u.nodeId).toMap
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

        annotationMap = annotations.map {
          case (layer, annotations) =>
            (layer.id -> annotations.map(
              a => AnnotationDTO(a, userIdMap.getOrElse(a.creatorId, ""))
            ))
        }

        layers = annotations.keys.toList
      } yield GetAnnotationsResponse(annotationMap, layers, userMap)

      override val is = result.value.map(OkResult)
    }
  }

  val putStorageOperation = (apiOperation[SetStorageResponse]("putStorage")
    summary "set storage for package. NOTE: this endpoint is deprecated and will go away in a future release (2.7.3)"
    parameter pathParam[Int]("id")
      .description("the integer id of the package to update")
    parameter bodyParam[SetStorageRequest]("body")
      .description("request body containing the new size of the package") deprecate)

  // this endpoint is deprecated and will go away in a future release v2.7.3
  put("/:id/storage", operation(putStorageOperation)) {
    response.setHeader(
      "Warning",
      "299 - PUT /packages/:id/storage is deprecated and will be removed by December 31, 2025"
    )

    new AsyncResult {
      val result: EitherT[Future, ActionResult, SetStorageResponse] = for {
        pkgId <- paramT[Int]("id")
        secureContainer <- getSecureContainer()
        _ <- checkOrErrorT(isServiceClaim(request))(Forbidden())

        _ <- secureContainer
          .authorizePackageId(Set(DatasetPermission.EditFiles))(pkgId)
          .coreErrorToActionResult()

        // set new storage value
        requestBody <- extractOrErrorT[SetStorageRequest](parsedBody)
        _ <- secureContainer.storageManager
          .incrementStorage(spackages, requestBody.size, pkgId)
          .orError()

        storageMap <- secureContainer.storageManager
          .getStorage(spackages, List(pkgId))
          .map(_.map { case (k, v) => (k.toString, v.getOrElse(0L)) })
          .coreErrorToActionResult()

      } yield SetStorageResponse(storageMap)

      override val is = result.value.map(OkResult)
    }
  }

}
