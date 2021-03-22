// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.api

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.blackfynn.auth.middleware.DatasetPermission
import com.blackfynn.dtos.{
  Builders,
  ChannelDTO,
  ModelPropertyRO,
  PackageDTO,
  PagedResponse,
  UserDTO
}
import com.blackfynn.helpers.TimeSeriesHelper
import com.blackfynn.helpers.APIContainers.{
  InsecureAPIContainer,
  SecureAPIContainer,
  SecureContainerBuilderType
}
import com.blackfynn.helpers.ResultHandlers.{
  CreatedResult,
  OkResult,
  StreamResult
}
import com.blackfynn.helpers.Validators.oneOfTransform
import com.blackfynn.helpers.either.EitherTErrorHandler.implicits._
import com.blackfynn.helpers.either.EitherErrorHandler.implicits._
import com.blackfynn.core.utilities.FutureEitherHelpers.implicits._
import com.blackfynn.models.{ Dataset, ModelProperty, Package, PackageType }
import com.blackfynn.core.utilities.checkOrErrorT
import com.github.tminglei.slickpg.Range
import cats.implicits._
import cats.data.EitherT
import com.blackfynn.db.{ TimeSeriesAnnotation, TimeSeriesLayer }
import com.blackfynn.domain.{ CoreError, NotFound }
import com.blackfynn.dtos.Builders.packageDTO
import com.blackfynn.timeseries._
import com.blackfynn.helpers.Colors
import com.blackfynn.models.FileObjectType.View
import org.scalatra._
import org.scalatra.swagger.Swagger
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder
import io.circe.generic.auto._

import scala.collection.SortedSet
import scala.concurrent.{ ExecutionContext, Future }
import scalikejdbc.{ AutoSession, DBSession }

case class TimeSeriesAnnotationWriteRequest(
  name: String,
  channelIds: List[String],
  label: String,
  description: Option[String],
  start: Long,
  end: Long,
  data: Option[AnnotationData],
  layer_id: Option[Int] = None,
  linkedPackage: Option[String] = None
)

case class TimeSeriesChannelWriteRequest(
  name: String,
  start: Long,
  end: Long,
  unit: String,
  rate: Double,
  channelType: String,
  lastAnnotation: Long,
  group: Option[String],
  spikeDuration: Option[Long],
  properties: List[ModelPropertyRO],
  id: Option[String] = None
)

case class AnnotationResults(
  users: Map[String, UserDTO],
  annotations: PagedResponse[TimeSeriesAnnotation],
  linkedPackages: Map[String, PackageDTO]
)
case class AnnotationResult(
  users: Map[String, UserDTO],
  annotation: TimeSeriesAnnotation,
  linkedPackage: Option[PackageDTO]
)

case class LayerRequest(
  name: String,
  description: Option[String],
  color: Option[String]
)

class TimeSeriesController(
  val insecureContainer: InsecureAPIContainer,
  val secureContainerBuilder: SecureContainerBuilderType,
  implicit
  protected val executor: ExecutionContext,
  implicit
  val materializer: Materializer
)(implicit
  val swagger: Swagger
) extends ScalatraServlet
    with AuthenticatedController {

  override val swaggerTag = "TimeSeries"

  // var so we can override in tests
  implicit var autoSession: DBSession = AutoSession

  def getUserMap(
    annotations: Seq[TimeSeriesAnnotation]
  ): EitherT[Future, CoreError, Map[String, UserDTO]] = {
    val userIds = annotations.map(_.userId).toSet.flatten
    for {
      users <- insecureContainer.userManager.getByNodeIds(userIds)
      dtos <- users.traverse(
        Builders.userDTO(
          _,
          storage = None,
          pennsieveTermsOfService = None,
          customTermsOfService = Seq.empty
        )(insecureContainer.organizationManager, executor)
      )
    } yield dtos.map(u => (u.id -> u)).toMap
  }

  def makeAnnotationResultsDTO(
    r: PagedResponse[TimeSeriesAnnotation],
    secureContainer: SecureAPIContainer,
    dataset: Dataset,
    includeLinkedPackages: Boolean
  ): EitherT[Future, CoreError, AnnotationResults] = {
    for {
      usermap <- getUserMap(r.results)
      linkedPackageIds = r.results.flatMap(r => r.linkedPackage).toList
      packageMap <- if (includeLinkedPackages) {
        for {
          linkedPackages <- secureContainer.packageManager.getByNodeIds(
            linkedPackageIds
          )
          linkedPackageDTOs <- linkedPackages.traverse(
            linkedPkg =>
              packageDTO(
                linkedPkg,
                dataset,
                includeAncestors = false,
                includeChildren = true,
                include = Some(Set(View)),
                storage = None
              )(executor, secureContainer)
          )
        } yield linkedPackageDTOs.map(p => (p.content.id -> p)).toMap
      } else {
        Map[String, PackageDTO]().asRight[CoreError].toEitherT[Future]
      }
    } yield AnnotationResults(usermap, r, packageMap)
  }

  val getChannelsOperation: OperationBuilder = (apiOperation[List[ChannelDTO]](
    "getChannels"
  )
    summary "gets the channels for a time series package"
    parameter pathParam[String]("id")
    parameter queryParam[Boolean]("startAtEpoch").optional.defaultValue(false))

  get("/:id/channels", operation(getChannelsOperation)) {
    new AsyncResult {
      val result = for {
        secureContainer <- getSecureContainer
        packageId <- paramT[String]("id")
        startAtEpoch <- paramT[Boolean]("startAtEpoch", default = false)

        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(packageId)
          .orNotFound
        (pkg, dataset) = packageAndDataset
        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ViewFiles))(dataset)
          .coreErrorToActionResult

        channels <- secureContainer.timeSeriesManager
          .getChannels(pkg)
          .orError

      } yield {
        if (startAtEpoch) {
          val minimumChannelStartTime =
            TimeSeriesHelper.getPackageStartTime(channels)
          ChannelDTO(
            channels.map(
              TimeSeriesHelper
                .resetChannelStartTime(minimumChannelStartTime)
            ),
            pkg
          )
        } else ChannelDTO(channels, pkg)
      }

      override val is = result.value.map(OkResult)
    }

  }

  val getChannelOperation: OperationBuilder = (apiOperation[ChannelDTO](
    "getChannel"
  )
    summary "get a single channel that belongs to the time series package"
    parameters (pathParam[String]("id"),
    pathParam[String]("channelId"),
    queryParam[Boolean]("startAtEpoch").optional.defaultValue(false)))

  get("/:id/channels/:channelId", operation(getChannelOperation)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, ChannelDTO] = for {
        secureContainer <- getSecureContainer
        packageId <- paramT[String]("id")
        channelId <- paramT[String]("channelId")
        startAtEpoch <- paramT[Boolean]("startAtEpoch", default = false)

        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(packageId)
          .orNotFound
        (pkg, dataset) = packageAndDataset
        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ViewFiles))(dataset)
          .coreErrorToActionResult

        channel <- secureContainer.timeSeriesManager
          .getChannelByNodeId(channelId, pkg)
          .orNotFound

        packageStartTime <- if (startAtEpoch) {
          TimeSeriesHelper.getPackageStartTime(pkg, secureContainer).orError
        } else EitherT.pure[Future, ActionResult](0L)

        channelStartingAtEpoch = if (startAtEpoch) {
          TimeSeriesHelper.resetChannelStartTime(packageStartTime)(channel)
        } else channel
      } yield ChannelDTO(channelStartingAtEpoch, pkg)

      override val is = result.value.map(OkResult)
    }
  }

  val createChannelsOperation: OperationBuilder = (apiOperation[ChannelDTO](
    "createChannel"
  )
    summary "saves channels to the time series package"
    parameters (
      pathParam[String]("id"),
      bodyParam[Seq[TimeSeriesChannelWriteRequest]]("channel")
  ))

  post("/:id/channels", operation(createChannelsOperation)) {
    val channelParams = parsedBody
      .extractOpt[Seq[TimeSeriesChannelWriteRequest]]
      .getOrElse(Seq(parsedBody.extract[TimeSeriesChannelWriteRequest]))

    new AsyncResult {
      val result: EitherT[Future, ActionResult, Any] = for {
        secureContainer <- getSecureContainer
        packageId <- paramT[String]("id")

        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(packageId)
          .orNotFound
        (pkg, dataset) = packageAndDataset
        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.EditFiles))(dataset)
          .coreErrorToActionResult

        _ <- checkOrErrorT(pkg.`type` == PackageType.TimeSeries)(
          BadRequest("channels can only be added to time series packages")
        )

        channels <- channelParams.toList.traverse { c =>
          for {
            _ <- checkOrErrorT(c.name.trim.nonEmpty)(
              BadRequest("channel name must not be blank")
            )
            properties = ModelPropertyRO.fromRequestObject(c.properties)
            channel <- secureContainer.timeSeriesManager
              .createChannel(
                pkg,
                c.name,
                c.start,
                c.end,
                c.unit,
                c.rate,
                c.channelType,
                c.group,
                c.lastAnnotation,
                c.spikeDuration,
                properties
              )
              .orError
          } yield channel
        }
      } yield
        if (channels.size == 1) ChannelDTO(channels.head, pkg)
        else ChannelDTO(channels, pkg)

      override val is = result.value.map(CreatedResult)
    }
  }

  val updateChannel: OperationBuilder = (apiOperation[ChannelDTO](
    "updateChannel"
  )
    summary "update an existing channel object in the graph"
    parameters (
      pathParam[String]("id"),
      pathParam[String]("channelId"),
      bodyParam[TimeSeriesChannelWriteRequest]("channel")
  ))

  put("/:id/channels/:channelId", operation(updateChannel)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, ChannelDTO] = for {
        secureContainer <- getSecureContainer
        packageId <- paramT[String]("id")
        channelId <- paramT[String]("channelId")

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
        _ <- checkOrErrorT(pkg.`type` == PackageType.TimeSeries)(
          BadRequest("channels only exist on time series packages")
        )

        channelParam <- extractOrErrorT[TimeSeriesChannelWriteRequest](
          parsedBody
        )
        _ <- checkOrErrorT(channelParam.name.trim.nonEmpty)(
          BadRequest("channel name must not be blank")
        )

        channel <- secureContainer.timeSeriesManager
          .getChannelByNodeId(channelId, pkg)
          .orNotFound
        properties = ModelPropertyRO.fromRequestObject(channelParam.properties)
        updatedChannel = channel.copy(
          name = channelParam.name,
          start = channelParam.start,
          end = channelParam.end,
          unit = channelParam.unit,
          rate = channelParam.rate,
          `type` = channelParam.channelType,
          lastAnnotation = channelParam.lastAnnotation,
          group = channelParam.group,
          spikeDuration = channelParam.spikeDuration,
          properties = properties
        )

        updated <- secureContainer.timeSeriesManager
          .updateChannel(updatedChannel, pkg)
          .orError
      } yield ChannelDTO(updated, pkg)

      override val is = result.value.map(OkResult)
    }
  }

  val updateChannelProperties: OperationBuilder = (apiOperation[ChannelDTO](
    "updateChannelProperties"
  )
    summary "update an existing channel object's properties in the graph"
    parameters (
      pathParam[String]("id"),
      pathParam[String]("channelId"),
      bodyParam[List[ModelPropertyRO]]("properties")
  ))

  put("/:id/channels/:channelId/properties", operation(updateChannel)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, ChannelDTO] = for {
        secureContainer <- getSecureContainer
        packageId <- paramT[String]("id")
        channelId <- paramT[String]("channelId")

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

        channel <- secureContainer.timeSeriesManager
          .getChannelByNodeId(channelId, pkg)
          .orNotFound
        propertyRequestObjects <- extractOrErrorT[List[ModelPropertyRO]](
          parsedBody
        )

        newProperties = ModelPropertyRO.fromRequestObject(
          propertyRequestObjects
        )
        updatedProperties = ModelProperty.merge(
          channel.properties,
          newProperties
        )
        updatedChannel = channel.copy(properties = updatedProperties)

        updated <- secureContainer.timeSeriesManager
          .updateChannel(updatedChannel, pkg)
          .orError
      } yield ChannelDTO(updated, pkg)

      override val is = result.value.map(OkResult)
    }
  }

  val updateChannels: OperationBuilder = (apiOperation[ChannelDTO](
    "updateChannels"
  )
    summary "update existing channel objects in the graph"
    parameters (
      pathParam[String]("id"),
      bodyParam[List[TimeSeriesChannelWriteRequest]]("channels")
  ))

  put("/:id/channels", operation(updateChannels)) {

    new AsyncResult {
      val result: EitherT[Future, ActionResult, List[ChannelDTO]] = for {
        secureContainer <- getSecureContainer
        packageId <- paramT[String]("id")

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

        _ <- checkOrErrorT(pkg.`type` == PackageType.TimeSeries)(
          BadRequest("channels only exist on time series packages")
        )

        channelsParam <- extractOrErrorT[List[TimeSeriesChannelWriteRequest]](
          parsedBody
        )
        _ <- checkOrErrorT(channelsParam.forall(_.name.trim.nonEmpty))(
          BadRequest("channel names must not be blank")
        )

        // create a list of tuples (channelId -> channel) in order to
        // ensure all channelIds are defined.
        channelsTuples <- channelsParam
          .traverse(
            param =>
              param.id
                .orBadRequest("channel ids must be defined")
                .map(_ -> param)
          )
          .toEitherT[Future]

        channels <- channelsTuples
          .map {
            case (id, _) =>
              secureContainer.timeSeriesManager
                .getChannelByNodeId(id, pkg)
          }
          .sequence
          .orNotFound

        channelsMap = channelsTuples.toMap
        updatedChannels = channels.map { channel =>
          val channelParam = channelsMap(channel.nodeId)
          val properties =
            ModelPropertyRO.fromRequestObject(channelParam.properties)
          channel.copy(
            name = channelParam.name,
            start = channelParam.start,
            end = channelParam.end,
            unit = channelParam.unit,
            rate = channelParam.rate,
            `type` = channelParam.channelType,
            lastAnnotation = channelParam.lastAnnotation,
            group = channelParam.group,
            spikeDuration = channelParam.spikeDuration,
            properties = properties
          )
        }

        updated <- secureContainer.timeSeriesManager
          .updateChannels(updatedChannels, pkg)
          .orError
      } yield updated.map(ChannelDTO(_, pkg))

      override val is = result.value.map(OkResult)
    }
  }

  val deleteChannel: OperationBuilder = (apiOperation[Unit]("deleteChannel")
    summary "delete an existing channel object in the graph"
    parameters (
      pathParam[String]("id"),
      pathParam[String]("channelId")
  ))

  delete("/:id/channels/:channelId", operation(deleteChannel)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Boolean] = for {
        secureContainer <- getSecureContainer
        packageId <- paramT[String]("id")
        channelId <- paramT[String]("channelId")

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

        channel <- secureContainer.timeSeriesManager
          .getChannelByNodeId(channelId, pkg)
          .orNotFound
        _ <- secureContainer.timeSeriesManager
          .deleteChannel(channel, pkg)
          .orError
      } yield true

      override val is = result.value.map(OkResult)
    }
  }

  def getChannelIds(
    requestedChannels: Seq[String],
    `package`: Package
  )(implicit
    secureContainer: SecureAPIContainer
  ): EitherT[Future, CoreError, SortedSet[String]] = {
    val channels =
      if (requestedChannels.isEmpty)
        secureContainer.timeSeriesManager
          .getChannels(`package`)
      else
        secureContainer.timeSeriesManager
          .getChannelsByNodeIds(`package`, requestedChannels.to[SortedSet])

    channels.map(_.map(_.nodeId).to[SortedSet])
  }

  val createAnnotation: OperationBuilder = (apiOperation[TimeSeriesAnnotation](
    "createTimeSeriesAnnotation"
  )
    summary "Create an annotation for the given time series id"
    parameters (
      pathParam[String]("id")
        .description("Id of the time series the annotation will belong to"),
      pathParam[String]("layerId").description(
        "Id of the time series layer the annotation will belong to"
      ),
      queryParam[Boolean]("startAtEpoch")
        .description(
          "Timestamps in request will be relative to the minimum package time"
        )
        .optional
        .defaultValue(false),
      bodyParam[TimeSeriesAnnotationWriteRequest]("body")
        .description("annotations to write")
  ))
  post("/:id/layers/:layerId/annotations", operation(createAnnotation)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, TimeSeriesAnnotation] = for {
        secureContainer <- getSecureContainer
        user = secureContainer.user
        packageId <- paramT[String]("id")
        startAtEpoch <- paramT[Boolean]("startAtEpoch", default = false)

        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(packageId)
          .orNotFound
        (pkg, dataset) = packageAndDataset

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ManageAnnotations))(dataset)
          .coreErrorToActionResult
        _ <- secureContainer.datasetManager
          .assertNotLocked(dataset)
          .coreErrorToActionResult

        packageStartTime <- if (startAtEpoch)
          TimeSeriesHelper.getPackageStartTime(pkg, secureContainer).orError
        else EitherT.pure[Future, ActionResult](0L)

        _ <- checkOrErrorT(pkg.`type` == PackageType.TimeSeries)(
          BadRequest(Error("Id does not point to a valid TimeSeries package"))
        )
        params <- extractOrErrorT[TimeSeriesAnnotationWriteRequest](parsedBody)

        channelIds <- getChannelIds(params.channelIds, pkg)(secureContainer).coreErrorToActionResult

        dataDB = insecureContainer.dataDB
        layerId <- paramT[Int]("layerId")
        layer <- insecureContainer.layerManager
          .getBy(layerId)
          .whenNone[CoreError](NotFound(s"Layer:$layerId"))
          .coreErrorToActionResult

        createdTimeSeriesAnnotation <- insecureContainer.timeSeriesAnnotationManager
          .create(
            `package` = pkg,
            layerId = layer.id,
            name = params.name,
            label = params.label,
            description = params.description,
            userNodeId = user.nodeId,
            range = Range[Long](
              params.start + packageStartTime,
              params.end + packageStartTime
            ),
            channelIds = params.channelIds.to[SortedSet],
            data = params.data,
            linkedPackage = params.linkedPackage
          )(secureContainer.timeSeriesManager)
          .coreErrorToActionResult
      } yield
        TimeSeriesHelper.resetAnnotationStartTime(packageStartTime)(
          createdTimeSeriesAnnotation
        )

      override val is = result.value.map(CreatedResult)
    }
  }

  val getAnnotation: OperationBuilder = (apiOperation[AnnotationResult](
    "getAnnotation"
  )
    summary "Get an annotation"
    parameters (
      pathParam[String]("id")
        .description("Id of the time series the annotation belongs to"),
      pathParam[Int]("layerId")
        .description("Id of the layer the annotations belongs to"),
      pathParam[Boolean]("includeLinks").optional
        .description("whether or not to include attached packages")
        .defaultValue(true),
      queryParam[Int]("annotationId")
        .description("Id of the annotation to retrieve"),
      queryParam[Boolean]("startAtEpoch")
        .description(
          "Returned timestamps will be relative to the minimum package time"
        )
        .optional
        .defaultValue(false)
  ))

  get(
    "/:id/layers/:layerId/annotations/:annotationId",
    operation(getAnnotation)
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, AnnotationResult] = for {
        annotationId <- paramT[Int]("annotationId")
        secureContainer <- getSecureContainer
        packageId <- paramT[String]("id")
        includeLinkedPackages <- paramT[Boolean]("includeLinks", default = true)
        startAtEpoch <- paramT[Boolean]("startAtEpoch", default = false)

        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(packageId)
          .orNotFound
        (pkg, dataset) = packageAndDataset

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ViewAnnotations))(dataset)
          .coreErrorToActionResult

        _ <- checkOrErrorT(pkg.`type` == PackageType.TimeSeries)(
          BadRequest(Error("Id does not point to a valid TimeSeries package"))
        )
        layerId <- paramT[Int]("layerId")
        layer <- insecureContainer.layerManager
          .getBy(layerId)
          .whenNone[CoreError](
            NotFound(s"Could not find layer with id: $layerId")
          )
          .coreErrorToActionResult
        annotation <- insecureContainer.timeSeriesAnnotationManager
          .getBy(annotationId)
          .whenNone[CoreError](NotFound("Annotation not found"))
          .coreErrorToActionResult

        packageStartTime <- if (startAtEpoch)
          TimeSeriesHelper.getPackageStartTime(pkg, secureContainer).orError
        else EitherT.pure[Future, ActionResult](0L)

        userdto <- getUserMap(Seq(annotation)).orError

        linkedPackageDTO <- if (includeLinkedPackages) {
          for {
            linkedPackage <- annotation.linkedPackage.traverse(
              pkgId =>
                secureContainer.packageManager.getByNodeId(pkgId).orNotFound
            )
            linkedDTO <- linkedPackage.traverse(
              linkedPkg =>
                packageDTO(
                  linkedPkg,
                  dataset,
                  includeAncestors = false,
                  includeChildren = true,
                  include = Some(Set(View)),
                  storage = None
                )(executor, secureContainer).orError
            )
          } yield linkedDTO
        } else {
          None.asRight[ActionResult].toEitherT[Future]
        }

      } yield
        AnnotationResult(
          userdto,
          TimeSeriesHelper.resetAnnotationStartTime(packageStartTime)(
            annotation
          ),
          linkedPackageDTO
        )

      override val is = result.value.map(OkResult)
    }

  }

  val getAnnotations: OperationBuilder = (apiOperation[AnnotationResults](
    "getTimeSeriesAnnotations"
  )
    summary "Get annotations based on query params"
    parameters (
      pathParam[String]("id")
        .description("Id of time series object to search in"),
      pathParam[Int]("layerId")
        .description("Id of the layer the annotations belongs to"),
      queryParam[Long]("start")
        .description("Start time of search query in microseconds"),
      queryParam[Long]("end")
        .description("End time of search query in microseconds"),
      pathParam[Boolean]("includeLinks").optional
        .description("whether or not to include attached packages")
        .defaultValue(true),
      queryParam[SortedSet[String]]("channelIds")
        .description("list of channel ids (if absent, use all channels)")
        .optional,
      queryParam[Long]("limit").optional,
      queryParam[Long]("offset").optional,
      queryParam[String]("layerName")
        .description(
          s"""Annotation layer name (if absent, uses "${TimeSeriesLayer.defaultLayerName}" layer)"""
        )
        .optional,
      queryParam[Boolean]("startAtEpoch")
        .description(
          "Returned timestamps will be relative to the minimum package time"
        )
        .optional
        .defaultValue(false)
  ))

  get("/:id/layers/:layerId/annotations", operation(getAnnotations)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, AnnotationResults] = for {
        start <- paramT[Long]("start")
        end <- paramT[Long]("end")
        limit <- paramT[Long]("limit", default = 100L)
        offset <- paramT[Long]("offset", default = 0L)
        packageId <- paramT[String]("id")
        includeLinkedPackages <- paramT[Boolean]("includeLinks", default = true)
        requestedChannelIds = multiParams("channelIds")
        startAtEpoch <- paramT[Boolean]("startAtEpoch", default = false)

        secureContainer <- getSecureContainer
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(packageId)
          .orNotFound
        (pkg, dataset) = packageAndDataset

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ViewAnnotations))(dataset)
          .coreErrorToActionResult

        _ <- checkOrErrorT(pkg.`type` == PackageType.TimeSeries)(
          BadRequest(Error("Id does not point to a valid TimeSeries package"))
        )

        packageStartTime <- if (startAtEpoch)
          TimeSeriesHelper.getPackageStartTime(pkg, secureContainer).orError
        else EitherT.pure[Future, ActionResult](0L)

        layerId <- paramT[Int]("layerId")
        layer <- insecureContainer.layerManager
          .getBy(layerId)
          .whenNone[CoreError](
            NotFound(s"Could not find layer with id: $layerId")
          )
          .coreErrorToActionResult
        channelIds <- getChannelIds(requestedChannelIds, pkg)(secureContainer).coreErrorToActionResult

        annotations <- insecureContainer.timeSeriesAnnotationManager
          .findBy(
            channelIds = channelIds,
            qStart = start + packageStartTime,
            qEnd = end + packageStartTime,
            layerId = layer.id,
            limit = limit,
            offset = offset
          )
          .toEitherT
          .coreErrorToActionResult

        resetAnnotations = annotations.map(
          TimeSeriesHelper.resetAnnotationStartTime(packageStartTime)
        )

        paged = PagedResponse[TimeSeriesAnnotation](
          limit = limit,
          offset = offset,
          results = resetAnnotations
        )

        dto <- makeAnnotationResultsDTO(
          paged,
          secureContainer,
          dataset,
          includeLinkedPackages
        ).orError
      } yield dto

      override val is = result.value.map(OkResult)
    }
  }

  val validAggregations = List("count", "average", "avg")

  val getAnnotationsWindow: OperationBuilder = (apiOperation[List[
    AnnotationAggregateWindowResult[Option[Long]]
  ]]("getAnnotationsWindow")
    summary "get annotations based on a sliding window"
    parameters (
      pathParam[String]("id").description(
        "Id of the timeseries package to aggregate annotations for"
      ),
      pathParam[Int]("layerId")
        .description("Id of the layer the annotations belongs to"),
      queryParam[String]("aggregation")
        .description(
          "Aggregation function to run over the values of the annotations"
        )
        .allowableValues(validAggregations),
      queryParam[Long]("start")
        .description(
          "The starting time of the range to query over in microseconds (inclusive)"
        ),
      queryParam[Long]("end")
        .description(
          "The ending time of the the range to query over in microseconds (exclusive)"
        ),
      queryParam[Long]("period")
        .description(
          "The length of time to group the counts of annotations by in microseconds"
        ),
      queryParam[SortedSet[String]]("channelIds")
        .description("list of channel ids (if absent, use all channels)")
        .optional,
      queryParam[Boolean]("mergePeriods")
        .description(
          "merge consecutive result periods together to reduce the size of the resulting payload"
        )
        .optional
        .defaultValue(false),
      queryParam[Boolean]("startAtEpoch")
        .description(
          "Returned timestamps will be relative to the minimum package time"
        )
        .optional
        .defaultValue(false)
  ))

  def getAggregationFunctionName: EitherT[Future, ActionResult, String] =
    paramT[String](
      "aggregation",
      oneOfTransform((s: String) => s.toLowerCase)(validAggregations: _*)
    ).map(_.toLowerCase)

  def getAggregationFunction(
    funcName: String
  ): Either[ActionResult, WindowAggregator[_, Double]] =
    funcName match {
      case "count" =>
        Right {
          WindowAggregator.CountAggregator
        }
      case "avg" | "average" =>
        Right {
          WindowAggregator.IntegerAverageAggregator
        }
      case _ =>
        Left {
          BadRequest(
            Error(
              s"Invalid aggregation specified. Only ${validAggregations.mkString(",")} allowed"
            )
          )
        }
    }

  get(
    "/:id/layers/:layerId/annotations/window",
    operation(getAnnotationsWindow)
  ) {
    new AsyncResult {
      val result
        : EitherT[Future, ActionResult, Source[AnnotationAggregateWindowResult[
          Double
        ], NotUsed]] = for {
        aggregationFuncName <- getAggregationFunctionName
        aggregationFunc <- getAggregationFunction(aggregationFuncName)
          .toEitherT[Future]
        start <- paramT[Long]("start")
        end <- paramT[Long]("end")
        period <- paramT[Long]("period")
        mergePeriods <- paramT[Boolean]("mergePeriods", default = false)
        startAtEpoch <- paramT[Boolean]("startAtEpoch", default = false)

        secureContainer <- getSecureContainer
        packageId <- paramT[String]("id")

        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(packageId)
          .orNotFound
        (pkg, dataset) = packageAndDataset

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ViewAnnotations))(dataset)
          .coreErrorToActionResult

        _ <- checkOrErrorT(pkg.`type` == PackageType.TimeSeries)(
          BadRequest(Error("Id does not point to a valid TimeSeries package"))
        )

        packageStartTime <- if (startAtEpoch)
          TimeSeriesHelper.getPackageStartTime(pkg, secureContainer).orError
        else EitherT.pure[Future, ActionResult](0L)

        requestedChannelIds = multiParams("channelIds")
        channelIds <- getChannelIds(requestedChannelIds, pkg)(secureContainer).coreErrorToActionResult

        layerId <- paramT[Int]("layerId")
        layer <- insecureContainer.layerManager
          .getBy(layerId)
          .whenNone[CoreError](
            NotFound(s"Could not find layer with id: $layerId")
          )
          .coreErrorToActionResult
        windows = if (mergePeriods) {
          insecureContainer.timeSeriesAnnotationManager.chunkWindowAggregate(
            frameStart = start + packageStartTime,
            frameEnd = end + packageStartTime,
            periodLength = period,
            channelIds = channelIds,
            layerId = layer.id,
            windowAggregator = aggregationFunc
          )
        } else {
          insecureContainer.timeSeriesAnnotationManager.windowAggregate(
            frameStart = start + packageStartTime,
            frameEnd = end + packageStartTime,
            periodLength = period,
            channelIds = channelIds,
            layerId = layer.id,
            windowAggregator = aggregationFunc
          )
        }
      } yield
        windows.map(
          TimeSeriesHelper.resetAnnotationWindowStartTime(packageStartTime)
        )

      //override val timeout: Duration = Duration.Inf

      override val is = result.value
        .flatMap(r => StreamResult(r))
    }
  }

  val hasAnnotations = (apiOperation[Boolean]("hasAnnotations")
    summary "returns true if any annotations exist for the time series package"
    parameters (
      pathParam[String]("id").description(
        "Id of the timeseries package to aggregate annotations for"
      )
    ))

  get("/:id/hasAnnotations", operation(hasAnnotations)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Boolean] = for {
        secureContainer <- getSecureContainer
        packageId <- paramT[String]("id")
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(packageId)
          .orNotFound
        (pkg, dataset) = packageAndDataset

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ViewAnnotations))(dataset)
          .coreErrorToActionResult

        hasAnnotations <- insecureContainer.timeSeriesAnnotationManager
          .hasAnnotations(pkg.nodeId)
          .toEitherT
          .coreErrorToActionResult
      } yield hasAnnotations

      override val is = result.value.map(OkResult)
    }
  }

  val getAllAnnotationsWindow: OperationBuilder = (apiOperation[
    Map[String, List[AnnotationAggregateWindowResult[Option[Long]]]]
  ]("getAllAnnotationsWindow")
    summary "get aggregations of annotations based on a sliding window"
    parameters (
      pathParam[String]("id").description(
        "Id of the timeseries package to aggregate annotations for"
      ),
      queryParam[Int]("layerIds").multiValued.description(
        "Ids of the layers the annotations belong to. Multiparameter - specify as layerIds=1&layerIds=2..."
      ),
      queryParam[String]("aggregation")
        .description(
          "Aggregation function to run over the values of the annotations"
        )
        .allowableValues(validAggregations),
      queryParam[Long]("start")
        .description(
          "The starting time of the range to query over in microseconds (inclusive)"
        ),
      queryParam[Long]("end")
        .description(
          "The ending time of the the range to query over in microseconds (exclusive)"
        ),
      queryParam[Long]("period")
        .description(
          "The length of time to group the counts of annotations by in microseconds"
        ),
      queryParam[String]("channelIds")
        .description(
          "list of channel ids If absent, use all channels. Multiparameter - specify as channelIds=abc&channelIds=def... "
        )
        .multiValued
        .optional,
      queryParam[Boolean]("mergePeriods")
        .description(
          "merge consecutive result periods together to reduce the size of the result payload"
        )
        .optional
        .defaultValue(false),
      queryParam[Boolean]("startAtEpoch")
        .description(
          "Returned timestamps will be relative to the minimum package time"
        )
        .optional
        .defaultValue(false)
  ))

  get("/:id/annotations/window", operation(getAllAnnotationsWindow)) {
    new AsyncResult {
      val result = for {
        aggregationFuncName <- getAggregationFunctionName
        aggregationFunc <- getAggregationFunction(aggregationFuncName)
          .toEitherT[Future]
        start <- paramT[Long]("start")
        end <- paramT[Long]("end")
        period <- paramT[Long]("period")
        mergePeriods <- paramT[Boolean]("mergePeriods", default = false)
        startAtEpoch <- paramT[Boolean]("startAtEpoch", default = false)

        secureContainer <- getSecureContainer
        packageId <- paramT[String]("id")
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(packageId)
          .orNotFound
        (pkg, dataset) = packageAndDataset

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ViewAnnotations))(dataset)
          .coreErrorToActionResult

        packageStartTime <- if (startAtEpoch)
          TimeSeriesHelper.getPackageStartTime(pkg, secureContainer).orError
        else EitherT.pure[Future, ActionResult](0L)

        _ <- checkOrErrorT(pkg.`type` == PackageType.TimeSeries)(
          BadRequest(Error("Id does not point to a valid TimeSeries package"))
        )

        layerIds <- listParamT[Int]("layerIds").map(_.toSet)

        validLayerIds <- insecureContainer.layerManager
          .getLayerIds(packageId)
          .toEitherT
          .coreErrorToActionResult
        layerIdsToQueryWith = layerIds.intersect(validLayerIds)

        requestedChannelIds = multiParams("channelIds")
        channelIds <- getChannelIds(requestedChannelIds, pkg)(secureContainer).coreErrorToActionResult

        windows <- Source(layerIdsToQueryWith)
          .mapAsync(1) { layerId =>
            val source =
              if (mergePeriods) {
                insecureContainer.timeSeriesAnnotationManager
                  .chunkWindowAggregate(
                    frameStart = start + packageStartTime,
                    frameEnd = end + packageStartTime,
                    periodLength = period,
                    channelIds = channelIds,
                    layerId = layerId,
                    windowAggregator = aggregationFunc
                  )
              } else {
                insecureContainer.timeSeriesAnnotationManager.windowAggregate(
                  frameStart = start + packageStartTime,
                  frameEnd = end + packageStartTime,
                  periodLength = period,
                  channelIds = channelIds,
                  layerId = layerId,
                  windowAggregator = aggregationFunc
                )
              }
            source
              .runFold(Vector.empty[AnnotationAggregateWindowResult[Double]]) {
                (accu, result) =>
                  accu :+ result
              }
              .map(v => layerId -> v)
          }
          .runFold(
            Map.empty[Int, Vector[AnnotationAggregateWindowResult[Double]]]
          ) {
            case (accu, (layerId, results)) =>
              accu.updated(layerId, results)
          }
          .toEitherT
          .coreErrorToActionResult
      } yield
        windows.map {
          case (layerId, layerWindows) =>
            layerId -> layerWindows.map(
              TimeSeriesHelper
                .resetAnnotationWindowStartTime(packageStartTime)
            )
        }

      override val is = result.value.map(OkResult)
    }
  }

  val updateAnnotation: OperationBuilder = (apiOperation[TimeSeriesAnnotation](
    "updateTimeSeriesAnnotation"
  )
    summary "Update an annotation"
    parameters (
      pathParam[String]("id")
        .description("Id of the time series the annotation belongs to"),
      pathParam[Int]("layerId")
        .description("Id of the layer the annotation belongs to"),
      pathParam[Int]("annotationId")
        .description("Id of the annotation to update"),
      queryParam[Boolean]("startAtEpoch")
        .description(
          "Timestamps in request will be relative to the minimum package time"
        )
        .optional
        .defaultValue(false),
      bodyParam[TimeSeriesAnnotationWriteRequest]
  ))

  put(
    "/:id/layers/:layerId/annotations/:annotationId",
    operation(updateAnnotation)
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, TimeSeriesAnnotation] = for {
        annotationId <- paramT[Int]("annotationId")
        secureContainer <- getSecureContainer
        packageId <- paramT[String]("id")
        startAtEpoch <- paramT[Boolean]("startAtEpoch", default = false)

        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(packageId)
          .orNotFound
        (pkg, dataset) = packageAndDataset

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ManageAnnotations))(dataset)
          .coreErrorToActionResult
        _ <- secureContainer.datasetManager
          .assertNotLocked(dataset)
          .coreErrorToActionResult

        packageStartTime <- if (startAtEpoch)
          TimeSeriesHelper.getPackageStartTime(pkg, secureContainer).orError
        else EitherT.pure[Future, ActionResult](0L)

        _ <- checkOrErrorT(pkg.`type` == PackageType.TimeSeries)(
          BadRequest(Error("Id does not point to a valid TimeSeries package"))
        )

        layerId <- paramT[Int]("layerId")
        layer <- insecureContainer.layerManager
          .getBy(layerId)
          .toEitherT
          .coreErrorToActionResult

        params <- extractOrErrorT[TimeSeriesAnnotationWriteRequest](parsedBody)

        existingAnnotation <- insecureContainer.timeSeriesAnnotationManager
          .getBy(annotationId)
          .whenNone[CoreError](NotFound(s"TimeSeriesAnnotation:$annotationId"))
          .coreErrorToActionResult

        updatedAnnotation = existingAnnotation.copy(
          name = params.name,
          label = params.label,
          description = params.description,
          channelIds = params.channelIds.to[SortedSet],
          start = params.start + packageStartTime,
          end = params.end + packageStartTime,
          layerId = params.layer_id.getOrElse(existingAnnotation.layerId),
          linkedPackage = params.linkedPackage
        )
        savedAnnotation <- insecureContainer.timeSeriesAnnotationManager
          .update(pkg, updatedAnnotation)(secureContainer.timeSeriesManager)
          .coreErrorToActionResult
      } yield
        TimeSeriesHelper.resetAnnotationStartTime(packageStartTime)(
          savedAnnotation
        )

      override val is = result.value.map(OkResult)
    }
  }

  val deleteAnnotation: OperationBuilder = (apiOperation[Unit](
    "deleteTimeSeriesAnnotation"
  )
    summary "delete the annotation with the given id"
    parameters (
      pathParam[String]("id")
        .description("Time series package id the annotation belongs to"),
      pathParam[Int]("layerId")
        .description("Id of the layer the annotation belongs to"),
      pathParam[Int]("annotationId")
        .description("Id of the annotation to delete")
  ))

  delete(
    "/:id/layers/:layerId/annotations/:annotationId",
    operation(deleteAnnotation)
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Unit] = for {
        annotationId <- paramT[Int]("annotationId")
        secureContainer <- getSecureContainer
        packageId <- paramT[String]("id")
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(packageId)
          .orNotFound
        (pkg, dataset) = packageAndDataset

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ManageAnnotations))(dataset)
          .coreErrorToActionResult
        _ <- secureContainer.datasetManager
          .assertNotLocked(dataset)
          .coreErrorToActionResult

        _ <- checkOrErrorT(pkg.`type` == PackageType.TimeSeries)(
          BadRequest(Error("Id does not point to a valid TimeSeries package"))
        )

        layerId <- paramT[Int]("layerId")
        _ <- insecureContainer.layerManager
          .getBy(layerId)
          .whenNone[CoreError](
            NotFound(s"Could not find layer with id: $layerId")
          )
          .coreErrorToActionResult
        deleteResult <- insecureContainer.timeSeriesAnnotationManager
          .delete(annotationId)
          .toEitherT
          .coreErrorToActionResult
      } yield deleteResult

      override val is = result.value.map(OkResult)
    }
  }

  val deleteAnnotations: OperationBuilder = (apiOperation[Unit](
    "deleteTimeSeriesAnnotations"
  )
    summary "delete the annotations with the given ids that belong to this time series package and layer"
    parameters (
      pathParam[String]("id")
        .description("Time series package id the annotation belongs to"),
      pathParam[Int]("layerId")
        .description("Id of the layer the annotation belongs to"),
      bodyParam[List[Long]]("body")
        .description("Ids of the annotations to delete.")
  ))

  delete("/:id/layers/:layerId/annotations", operation(deleteAnnotations)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Int] = for {
        secureContainer <- getSecureContainer
        packageId <- paramT[String]("id")
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(packageId)
          .orNotFound
        (pkg, dataset) = packageAndDataset

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ManageAnnotations))(dataset)
          .coreErrorToActionResult
        _ <- secureContainer.datasetManager
          .assertNotLocked(dataset)
          .coreErrorToActionResult

        _ <- checkOrErrorT(pkg.`type` == PackageType.TimeSeries)(
          BadRequest(Error("Id does not point to a valid TimeSeries package"))
        )
        layerId <- paramT[Int]("layerId")
        layer <- insecureContainer.layerManager
          .getBy(layerId)
          .whenNone[CoreError](
            NotFound(s"Could not find layer with id: $layerId")
          )
          .coreErrorToActionResult
        ids <- extractOrErrorT[Set[Int]](parsedBody)
        deleteResult <- insecureContainer.timeSeriesAnnotationManager
          .delete(pkg.nodeId, layer.id, ids)
          .toEitherT
          .coreErrorToActionResult
      } yield deleteResult

      override val is = result.value.map(OkResult)
    }
  }

  val createLayer: OperationBuilder = (apiOperation[TimeSeriesLayer](
    "createTimeSeriesLayer"
  )
    summary "create time series layer for use with the given layer"
    parameters (
      pathParam[String]("id")
        .description("Id of the time series to create this layer for"),
      bodyParam[LayerRequest]("body")
        .description("Properties of the layer to create")
  ))

  post("/:id/layers", operation(createLayer)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, TimeSeriesLayer] = for {
        secureContainer <- getSecureContainer
        timeSeriesId <- paramT[String]("id")
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(timeSeriesId)
          .orNotFound
        (pkg, dataset) = packageAndDataset

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ManageAnnotationLayers))(
            dataset
          )
          .coreErrorToActionResult
        _ <- secureContainer.datasetManager
          .assertNotLocked(dataset)
          .coreErrorToActionResult

        _ <- checkOrErrorT(pkg.`type` == PackageType.TimeSeries)(
          BadRequest(Error("Id does not point to a valid TimeSeries package"))
        )
        layerProps <- extractOrErrorT[LayerRequest](parsedBody)
        _ <- checkOrErrorT(layerProps.name.nonEmpty)(
          BadRequest("Name cannot be empty")
        )
        exsistingLayerColors <- insecureContainer.layerManager
          .getExistingColors(timeSeriesId)
          .toEitherT
          .coreErrorToActionResult
        createdLayer <- insecureContainer.layerManager
          .create(
            timeSeriesId = timeSeriesId,
            name = layerProps.name,
            description = layerProps.description,
            color = layerProps.color
              .orElse(Colors.randomNewColor(exsistingLayerColors))
          )
          .toEitherT
          .coreErrorToActionResult
      } yield createdLayer

      override val is = result.value.map(CreatedResult)
    }

  }

  val getLayers
    : OperationBuilder = (apiOperation[PagedResponse[TimeSeriesLayer]](
    "getLayers"
  )
    summary "Get all layers for a timeseries"
    parameters (
      pathParam[String]("id"),
      queryParam[String]("name").optional,
      queryParam[Long]("limit").optional,
      queryParam[Long]("offset").optional
  ))

  get("/:id/layers", operation(getLayers)) {
    new AsyncResult {
      val result
        : EitherT[Future, ActionResult, PagedResponse[TimeSeriesLayer]] = for {
        limit <- paramT[Long]("limit", default = 100L)
        offset <- paramT[Long]("offset", default = 0L)
        secureContainer <- getSecureContainer
        timeSeriesId <- paramT[String]("id")
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(timeSeriesId)
          .orNotFound
        (timeSeries, dataset) = packageAndDataset

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ViewAnnotations))(dataset)
          .coreErrorToActionResult

        _ <- checkOrErrorT(timeSeries.`type` == PackageType.TimeSeries)(
          BadRequest(Error("Id does not point to a valid TimeSeries package"))
        )
        layers <- insecureContainer.layerManager
          .findBy(timeSeriesId = timeSeriesId, limit = limit, offset = offset)
          .toEitherT
          .coreErrorToActionResult
      } yield {
        PagedResponse[TimeSeriesLayer](
          limit = limit,
          offset = offset,
          results = layers
        )
      }

      override val is = result.value.map(OkResult)
    }
  }

  val getLayerEndpoint: OperationBuilder = (apiOperation[TimeSeriesLayer](
    "getTimeSeriesLayer"
  )
    summary "get a layer for the given time series id"
    parameters (
      pathParam[String]("id")
        .description("Id of the time series this layer belongs to"),
      pathParam[Int]("layerId").description("Id of the layer to retrieve")
  ))

  get("/:id/layers/:layerId", operation(getLayerEndpoint)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, TimeSeriesLayer] = for {
        layerId <- paramT[Int]("layerId")
        secureContainer <- getSecureContainer
        timeSeriesId <- paramT[String]("id")
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(timeSeriesId)
          .orNotFound
        (timeSeries, dataset) = packageAndDataset

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ViewAnnotations))(dataset)
          .coreErrorToActionResult

        _ <- checkOrErrorT(timeSeries.`type` == PackageType.TimeSeries)(
          BadRequest(Error("Id does not point to a valid TimeSeries package"))
        )
        layer <- insecureContainer.layerManager
          .getBy(layerId)
          .whenNone[CoreError](NotFound("Layer not found"))
          .coreErrorToActionResult
      } yield layer

      override val is = result.value.map(OkResult)
    }
  }

  val updateLayer: OperationBuilder = (apiOperation[TimeSeriesLayer](
    "updateTimeSeriesLayer"
  )
    summary "update layer for the given time series id"
    parameters (
      pathParam[String]("id")
        .description("Id of the time series this layer belongs to"),
      pathParam[Int]("layerId").description("Id of the layer to update"),
      bodyParam[LayerRequest].description("Layer properties to update")
  ))

  put("/:id/layers/:layerId", operation(updateLayer)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, TimeSeriesLayer] = for {
        timeSeriesId <- paramT[String]("id")
        layerId <- paramT[Int]("layerId")
        secureContainer <- getSecureContainer
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(timeSeriesId)
          .orNotFound
        (timeSeries, dataset) = packageAndDataset

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ManageAnnotationLayers))(
            dataset
          )
          .coreErrorToActionResult
        _ <- secureContainer.datasetManager
          .assertNotLocked(dataset)
          .coreErrorToActionResult

        _ <- checkOrErrorT(timeSeries.`type` == PackageType.TimeSeries)(
          BadRequest(Error("Id does not point to a valid TimeSeries package"))
        )
        existingLayer <- insecureContainer.layerManager
          .getBy(layerId)
          .whenNone[CoreError](NotFound("Layer not found"))
          .coreErrorToActionResult
        layerParams <- extractOrErrorT[LayerRequest](parsedBody)
        updateLayer = existingLayer.copy(
          name = layerParams.name,
          description = layerParams.description,
          color = layerParams.color.orElse(existingLayer.color)
        )
        savedLayer <- insecureContainer.layerManager
          .update(updateLayer)
          .toEitherT
          .coreErrorToActionResult
      } yield savedLayer

      override val is = result.value.map(OkResult)
    }
  }

  val deleteLayer: OperationBuilder = (apiOperation[Unit](
    "deleteTimeSeriesLayer"
  )
    summary "delete layer for the given time series id"
    parameters (
      pathParam[String]("id")
        .description("Id of the time series this layer belongs to"),
      pathParam[Int]("layerId").description("Id of the layer to delete")
  ))

  delete("/:id/layers/:layerId", operation(deleteLayer)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Unit] = for {
        timeSeriesId <- paramT[String]("id")
        layerId <- paramT[Int]("layerId")
        secureContainer <- getSecureContainer
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(timeSeriesId)
          .orNotFound
        (timeSeries, dataset) = packageAndDataset

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ManageAnnotationLayers))(
            dataset
          )
          .coreErrorToActionResult
        _ <- secureContainer.datasetManager
          .assertNotLocked(dataset)
          .coreErrorToActionResult

        _ <- checkOrErrorT(timeSeries.`type` == PackageType.TimeSeries)(
          BadRequest(Error("Id does not point to a valid TimeSeries package"))
        )
        deleteResult <- insecureContainer.layerManager
          .delete(layerId)
          .toEitherT
          .coreErrorToActionResult
      } yield deleteResult

      override val is = result.value.map(OkResult)
    }
  }
}
