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

import java.net.URL
import java.time.{ LocalDate, OffsetDateTime, ZonedDateTime }

import akka.Done
import akka.http.scaladsl.model.headers.{ Authorization, OAuth2BearerToken }
import akka.actor.ActorSystem
import cats.data.EitherT
import cats.implicits._
import com.pennsieve.audit.middleware.Auditor
import com.pennsieve.auth.middleware.DatasetPermission
import com.pennsieve.aws.email.{ Email, SesMessageResult }
import com.pennsieve.aws.queue.SQSClient
import com.pennsieve.clients.{ DatasetAssetClient, ModelServiceClient }
import com.pennsieve.core.utilities
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.core.utilities.{ checkOrErrorT, JwtAuthenticator }
import com.pennsieve.discover.client.definitions.{
  DatasetPublishStatus,
  PublicDatasetDTO
}
import com.pennsieve.discover.client.publish.PublishClient
import com.pennsieve.discover.client.search.SearchClient
import com.pennsieve.doi.client.definitions.{
  CreateDraftDoiRequest,
  CreatorDTO
}
import com.pennsieve.doi.client.doi._
import com.pennsieve.doi.models._
import com.pennsieve.domain
import com.pennsieve.domain.StorageAggregation.{ sdatasets, spackages }
import com.pennsieve.domain._
import com.pennsieve.dtos.Builders._
import com.pennsieve.dtos._
import com.pennsieve.helpers.APIContainers.{
  InsecureAPIContainer,
  SecureContainerBuilderType
}
import com.pennsieve.helpers.Param
import com.pennsieve.helpers.ResultHandlers._
import com.pennsieve.helpers.either.EitherErrorHandler.implicits._
import com.pennsieve.helpers.either.EitherTErrorHandler.implicits._
import com.pennsieve.helpers.either.EitherThrowableErrorConverter.implicits._
import com.pennsieve.managers.{
  ChangelogManager,
  CollaboratorChanges,
  DatasetManager
}
import com.pennsieve.models._
import com.pennsieve.notifications.{
  DiscoverPublishNotification,
  MessageType,
  NotificationMessage
}
import com.pennsieve.web.Settings
import io.circe.syntax._
import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }
import mouse.all._
import net.ceedubs.ficus.Ficus._
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.IOUtils
import org.apache.http.HttpHeaders
import org.json4s.JValue
import org.json4s.JsonAST.JNothing
import org.scalatra._
import org.scalatra.servlet.{
  FileUploadSupport,
  MultipartConfig,
  SizeConstraintExceededException
}
import org.scalatra.swagger.Swagger
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder

import scala.collection.{ immutable, mutable }
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util._

case class CreateDataSetRequest(
  name: String,
  description: Option[String],
  properties: List[ModelPropertyRO],
  status: Option[String] = None,
  automaticallyProcessPackages: Boolean = false,
  license: Option[License] = None,
  tags: List[String] = List.empty
)

case class UpdateDataSetRequest(
  name: Option[String] = None,
  description: Option[String] = None,
  status: Option[String] = None,
  automaticallyProcessPackages: Option[Boolean] = None,
  license: Option[License] = None,
  tags: Option[List[String]] = None,
  dataUseAgreementId: Option[Int] = None
)

case class DatasetPermissionResponse(
  userId: Int,
  datasetId: Int,
  permission: DBPermission
)

// Generic role DTO used for add/edit/delete for users and teams
case class CollaboratorRoleDTO(id: String, role: Role)

case class UserCollaboratorRoleDTO(
  id: String,
  firstName: String,
  lastName: String,
  email: String,
  role: Role
)

case class TeamCollaboratorRoleDTO(id: String, name: String, role: Role)

case class RemoveCollaboratorRequest(id: String)

case class OrganizationRoleDTO(
  role: Option[Role],
  id: Option[String] = None,
  name: Option[String] = None
)

case class DatasetRoleResponse(userId: Int, datasetId: Int, role: Role)

case class PublishCompleteRequest(
  publishedDatasetId: Option[Int],
  publishedVersionCount: Int,
  lastPublishedDate: Option[OffsetDateTime],
  status: PublishStatus,
  success: Boolean,
  error: Option[String]
)

case class DatasetReadmeDTO(readme: String)

case class SwitchOwnerRequest(id: String)

case class AddContributorRequest(contributorId: Int)

case class AddCollectionRequest(collectionId: Int)

case class GrantPreviewAccessRequest(userId: Int)

case class RemovePreviewAccessRequest(userId: Int)

case class PreviewAccessRequest(
  datasetId: Int,
  userId: Int,
  dataUseAgreementId: Option[Int] = None
)

/**
  * TODO: should all user information be included here? The UserDTO contains the
  * email address of users, should that be exposed when we allow previewing
  * across organizations?
  */
case class DatasetPreviewerDTO(user: UserDTO, embargoAccess: EmbargoAccess)

object DatasetPreviewerDTO {
  def apply(user: User, previewer: DatasetPreviewer): DatasetPreviewerDTO =
    DatasetPreviewerDTO(
      user = Builders.userDTO(user, None, None, None, Seq.empty),
      embargoAccess = previewer.embargoAccess
    )
}

case class SwitchContributorsOrderRequest(
  contributorId: Int,
  otherContributorId: Int
)

case class DatasetBannerDTO(banner: Option[URL])

case class BatchPackagesPage(
  packages: Seq[ExtendedPackageDTO],
  failures: Seq[BatchPackagesFailure]
)

case class BatchPackagesFailure(id: ExternalId, error: String)

case class PaginatedStatusLogEntries(
  limit: Int,
  offset: Int,
  totalCount: Long,
  entries: List[StatusLogEntryDTO]
)

case class PublicationStatusRequest(comment: Option[String])

case class PaginatedDatasets(
  limit: Int,
  offset: Int,
  totalCount: Long,
  datasets: List[DataSetDTO]
)

case class PaginatedPublishedDatasets(
  limit: Int,
  offset: Int,
  totalCount: Long,
  datasets: List[DatasetAndPublishedDataset]
)

case class DatasetAndPublishedDataset(
  dataset: Option[DataSetDTO],
  publishedDataset: PublicDatasetDTO,
  embargoAccess: Option[EmbargoAccess]
)

case class DatasetIgnoreFileDTO(fileName: String)

case class ChangeCollectionRequest(collectionId: Int)

case class ChangelogEventGroupDTO(
  datasetId: Int,
  userIds: List[Int],
  eventType: ChangelogEventName,
  totalCount: Long,
  timeRange: ChangelogEventGroup.TimeRange,
  event: Option[ChangelogEventAndType] = None,
  eventCursor: Option[SerializedCursor]
)

object ChangelogEventGroupDTO {
  def from(
    eventGroup: ChangelogEventGroup,
    eventOrCursor: Either[ChangelogEventAndType, ChangelogEventCursor]
  ): ChangelogEventGroupDTO =
    ChangelogEventGroupDTO(
      datasetId = eventGroup.datasetId,
      userIds = eventGroup.userIds,
      eventType = eventGroup.eventType,
      totalCount = eventGroup.totalCount,
      timeRange = eventGroup.timeRange,
      event = eventOrCursor.left.toOption,
      eventCursor =
        eventOrCursor.right.toOption.map(ChangelogEventCursor.encodeBase64(_))
    )

}

case class ChangelogPage(
  cursor: Option[SerializedCursor],
  eventGroups: List[ChangelogEventGroupDTO]
)

case class ChangelogEventPage(
  cursor: Option[SerializedCursor],
  events: List[ChangelogEventAndType]
)

class DataSetsController(
  val insecureContainer: InsecureAPIContainer,
  val secureContainerBuilder: SecureContainerBuilderType,
  implicit
  val system: ActorSystem,
  auditLogger: Auditor,
  sqsClient: SQSClient,
  modelServiceClient: ModelServiceClient,
  publishClient: PublishClient,
  searchClient: SearchClient,
  doiClient: DoiClient,
  datasetAssetClient: DatasetAssetClient,
  maxFileUploadSize: Int,
  asyncExecutor: ExecutionContext
)(implicit
  val swagger: Swagger
) extends ScalatraServlet
    with AuthenticatedController
    with FileUploadSupport {

  override protected implicit def executor: ExecutionContext = asyncExecutor

  override val swaggerTag = "DataSets"

  configureMultipartHandling(
    MultipartConfig(maxFileSize = Some(maxFileUploadSize))
  )

  error {
    case e: SizeConstraintExceededException =>
      RequestEntityTooLarge("Upload is too large")
  }

  private val NotificationsQueueUrl: String =
    insecureContainer.config
      .as[String]("sqs.notifications_queue")

  // private val NotificationsCenterQueueUrl: String =
  //   insecureContainer.config
  //     .as[String]("pennsieve.notifications_center.queue_url")

  /**
    * Parameter decoders for enumeratum enumerations for the `paramT`,
    * `optParamT`, etc methods.
    */
  implicit val publicationStatusParam = Param.enumParam(PublicationStatus)

  implicit val publicationTypeParam = Param.enumParam(PublicationType)

  implicit val orderByColumnParam =
    Param.enumParam(DatasetManager.OrderByColumn)

  implicit val orderByDirectionParam =
    Param.enumParam(DatasetManager.OrderByDirection)

  implicit val roleParam: Param[Role] =
    Param.enumParam(Role)

  implicit val localDateParam: Param[LocalDate] =
    Param[String]
      .emap(s => Either.catchNonFatal(LocalDate.parse(s)).convertToBadRequest)

  implicit val externalIdParam: Param[ExternalId] =
    Param[String].map(
      s =>
        s.parseInt.leftMap(_ => s) match {
          case Left(nodeId) => ExternalId.nodeId(nodeId)
          case Right(intId) => ExternalId.intId(intId)
        }
    )

  implicit class JValueExtended(value: JValue) {
    def hasField(childString: String): Boolean =
      (value \ childString) != JNothing
  }

  /**
    * Send a notification to the legacy (not "center") notification service.
    *
    * TODO: combine this with the `NotificationServiceClient`. It is probably
    * better to use this SQS version instead of the REST client.
    */
  private def sendNotification(
    notification: NotificationMessage
  ): EitherT[Future, CoreError, Done] =
    sqsClient
      .send(NotificationsQueueUrl, notification.asJson.noSpaces)
      .map(_ => Done)

  private def setETagHeader(
    etag: ETag
  )(implicit
    response: HttpServletResponse
  ): EitherT[Future, ActionResult, Unit] =
    EitherT.rightT(response.setHeader(HttpHeaders.ETAG, etag.asHeader))

  // Set a null etag if the entity does not exist
  private def setETagHeader(
    etag: Option[ETag]
  )(implicit
    response: HttpServletResponse
  ): EitherT[Future, ActionResult, Unit] =
    EitherT.rightT(
      response.setHeader(HttpHeaders.ETAG, etag.map(_.asHeader).getOrElse("0"))
    )

  private def ifMatchHeader(
    implicit
    request: HttpServletRequest
  ): Option[String] =
    // convert null => None
    Option(request.getHeader(HttpHeaders.IF_MATCH))

  private def checkIfMatchTimestamp(
    entity: String,
    etag: ETag
  )(implicit
    request: HttpServletRequest
  ): EitherT[Future, ActionResult, Boolean] = {
    checkOrErrorT[CoreError](ifMatchHeader match {
      case None => true
      case Some(updatedMilli) => updatedMilli == etag.asHeader
    })(StaleUpdateError(s"$entity has changed since last retrieved")).coreErrorToActionResult
  }

  // To be used when then entity may not yet exist
  private def checkIfMatchTimestamp(
    entity: String,
    etag: Option[ETag]
  )(implicit
    request: HttpServletRequest
  ): EitherT[Future, ActionResult, Boolean] = {
    etag match {
      case None =>
        checkOrErrorT[CoreError](
          ifMatchHeader == None || ifMatchHeader == Some("0")
        )(StaleUpdateError(s"$entity already exists")).coreErrorToActionResult
      case Some(etag) => checkIfMatchTimestamp(entity, etag)
    }
  }

  get(
    "/:id",
    operation(
      apiOperation[DataSetDTO]("getDataSet")
        summary "gets a data set"
        parameters (
          pathParam[String]("id").description("data set id"),
          queryParam[Boolean]("includePublishedDataset").optional
            .description(
              "If true, information about publication will be returned"
            )
            .defaultValue(false)
      )
    )
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, DataSetDTO] = for {
        datasetId <- paramT[String]("id")
        secureContainer <- getSecureContainer
        traceId <- getTraceId(request)
        storageServiceClient = secureContainer.storageManager
        dataset <- secureContainer.datasetManager
          .getByNodeId(datasetId)
          .coreErrorToActionResult

        status <- secureContainer.datasetStatusManager
          .get(dataset.statusId)
          .coreErrorToActionResult

        _ <- secureContainer
          .authorizeDataset(
            Set(
              DatasetPermission.ViewFiles,
              DatasetPermission.ViewRecords,
              DatasetPermission.ViewAnnotations,
              DatasetPermission.ViewDiscussionComments
            )
          )(dataset)
          .coreErrorToActionResult

        storageMap <- storageServiceClient
          .getStorage(sdatasets, List(dataset.id))
          .orError

        storage = storageMap.get(dataset.id).flatten

        includePublishedDataset <- paramT[Boolean](
          "includePublishedDataset",
          default = false
        )

        publicationStatus <- dataset.publicationStatusId
          .map(
            secureContainer.datasetPublicationStatusManager
              .getPublicationStatus(_)
              .coreErrorToActionResult
          )
          .getOrElse(EitherT.rightT[Future, ActionResult](None))

        contributors <- secureContainer.datasetManager
          .getContributors(dataset)
          .coreErrorToActionResult

        dto <- datasetDTO(
          dataset,
          status,
          includeChildren = true,
          storage = storage,
          datasetPublicationStatus = publicationStatus,
          contributors = contributors.map(_._1),
          includeBannerUrl = true,
          includePublishedDataset = includePublishedDataset
        )(
          asyncExecutor,
          secureContainer,
          datasetAssetClient,
          DataSetPublishingHelper.getPublishedDatasetsFromDiscover(
            publishClient
          ),
          system,
          jwtConfig
        ).coreErrorToActionResult

        _ <- auditLogger
          .message()
          .append("dataset-id", dataset.id)
          .append("dataset-node-id", dataset.nodeId)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult

        _ <- setETagHeader(dataset.etag)
      } yield dto

      override val is = result.value.map(OkResult(_))
    }
  }

  get(
    "/:id/packageTypeCounts",
    operation(
      apiOperation[Map[String, Int]]("getPackageTypeCounts")
        summary "gets a data package type counts"
        parameters (
          pathParam[String]("id").description("data set id")
        )
    )
  ) {
    new AsyncResult {

      val result: EitherT[Future, ActionResult, Map[String, Long]] = for {
        datasetId <- paramT[String]("id")
        secureContainer <- getSecureContainer

        dataset <- secureContainer.datasetManager
          .getByNodeId(datasetId)
          .coreErrorToActionResult

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ViewFiles))(dataset)
          .coreErrorToActionResult

        counts <- secureContainer.packageManager
          .packageTypes(dataset)
          .map(_.map { case (k, v) => (k.toString, v.toLong) })
          .coreErrorToActionResult
      } yield counts
      override val is = result.value.map(OkResult(_))
    }
  }

  get(
    "/",
    operation(
      apiOperation[List[DataSetDTO]]("getDataSets")
        summary "gets all data sets that a user has permission to and that belong to the given organization"
        parameters (
          queryParam[Boolean]("includeBannerUrl").optional
            .description(
              "If true, presigned banner image URLS will be returned with each dataset"
            )
            .defaultValue(false),
          queryParam[Boolean]("includePublishedDataset").optional
            .description(
              "If true, information about publication will be returned"
            )
            .defaultValue(false)
      )
    )
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, List[DataSetDTO]] =
        for {
          secureContainer <- getSecureContainer
          organization = secureContainer.organization
          traceId <- getTraceId(request)
          storageServiceClient = secureContainer.storageManager

          includeBannerUrl <- paramT[Boolean](
            "includeBannerUrl",
            default = false
          )
          includePublishedDataset <- paramT[Boolean](
            "includePublishedDataset",
            default = false
          )

          maybeOverridingRole = {
            secureContainer.organizationRoleOverrides
              .get(secureContainer.organization.id)
              .flatten
          }

          datasets <- {
            secureContainer.datasetManager
              .find(Role.Viewer, maybeOverridingRole)
              .coreErrorToActionResult()
          }

          _ <- auditLogger
            .message()
            .append("description", "Datasets")
            .append("organization-id", organization.id)
            .append("organization-node-id", organization.nodeId)
            .append("dataset-ids", datasets.map(_.dataset.id): _*)
            .append("dataset-node-ids", datasets.map(_.dataset.nodeId): _*)
            .log(traceId)
            .toEitherT
            .coreErrorToActionResult

          dto <- {
            datasetDTOs(
              datasets,
              includeBannerUrl = includeBannerUrl,
              includePublishedDataset = includePublishedDataset
            )(
              asyncExecutor,
              secureContainer,
              storageServiceClient,
              datasetAssetClient,
              DataSetPublishingHelper.getPublishedDatasetsFromDiscover(
                publishClient
              ),
              system,
              jwtConfig
            ).orError
          }
        } yield dto

      override val is = result.value.map(OkResult(_))
    }
  }

  val AllowableOrderByValues: immutable.IndexedSeq[String] =
    DatasetManager.OrderByColumn.values.map(_.entryName)

  val AllowableOrderByDirectionValues =
    DatasetManager.OrderByDirection.values.map(_.entryName)

  val DatasetsDefaultLimit: Int = 25
  val DatasetsDefaultOffset: Int = 0

  get(
    "/paginated",
    operation(
      apiOperation[PaginatedDatasets]("getPaginatedDataSets")
        summary "get a paginated list of datasets"
        parameters (
          queryParam[Int]("limit").optional
            .description("max number of datasets returned")
            .defaultValue(DatasetsDefaultLimit),
          queryParam[Int]("offset").optional
            .description("offset used for pagination of results")
            .defaultValue(DatasetsDefaultOffset),
          queryParam[String]("query").optional
            .description("parameter for the text search"),
          queryParam[String]("publicationStatus").optional
            .description("Filter by publication status"),
          queryParam[String]("publicationType").optional
            .description("Filter by publication type"),
          queryParam[Int]("collectionId").optional
            .description("Filter by collection"),
          queryParam[String]("orderBy").optional
            .description(
              s"which data field to sort results by - values can be Name, IntId or UpdatedAt"
            )
            .allowableValues(AllowableOrderByValues)
            .defaultValue(DatasetManager.OrderByColumn.Name.entryName),
          queryParam[String]("orderDirection").optional
            .description(
              s"which direction to order the results by - value can be Desc or Asc"
            )
            .allowableValues(AllowableOrderByDirectionValues)
            .defaultValue(DatasetManager.OrderByDirection.Asc.entryName),
          queryParam[Boolean]("onlyMyDatasets").optional
            .description(
              s"if true, will only show dataset for which the user is the owner"
            ),
          queryParam[String]("withRole").optional
            .description(
              s"only show datasets for which the user has the role passed as argument"
            ),
          queryParam[Boolean]("canPublish").optional
            .description(
              "If true, only datasets that can be published will be returned.  If false, only datasets that can NOT be published will be returned."
            ),
          queryParam[Boolean]("includeBannerUrl").optional
            .description(
              "If true, presigned banner image URLS will be returned with each dataset"
            )
            .defaultValue(false),
          queryParam[Boolean]("includePublishedDataset").optional
            .description(
              "If true, information about publication will be returned"
            )
            .defaultValue(false)
      )
    )
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, PaginatedDatasets] =
        for {
          secureContainer <- getSecureContainer
          storageServiceClient = secureContainer.storageManager

          limit <- paramT[Int]("limit", default = DatasetsDefaultLimit)
          offset <- paramT[Int]("offset", default = DatasetsDefaultOffset)

          orderByDirection <- paramT[DatasetManager.OrderByDirection](
            "orderDirection",
            default = DatasetManager.OrderByDirection.Asc
          )

          orderBy <- paramT[DatasetManager.OrderByColumn](
            "orderBy",
            default = DatasetManager.OrderByColumn.Name
          )

          textSearch <- optParamT[String]("query")

          status <- optParamT[String]("status")
            .flatMap {
              _.traverse { statusName =>
                secureContainer.db
                  .run(
                    secureContainer.datasetStatusManager.getByName(statusName)
                  )
                  .toEitherT
                  .coreErrorToActionResult
              }
            }

          organization = secureContainer.organization

          ownerOnly <- optParamT[Boolean]("onlyMyDatasets").map {
            case Some(true) => Some(Role.Owner)
            case _ => None
          }

          withRole <- optParamT[Role]("withRole")

          _ <- checkOrErrorT[CoreError](ownerOnly.isEmpty || withRole.isEmpty)(
            PredicateError(
              "can't use withRole and onlyMyDatasets option together"
            )
          ).coreErrorToActionResult

          publicationTypes <- listParamT[PublicationType]("publicationType")
            .map {
              case Nil => None
              case types => Some(types.toSet)
            }

          publicationStatuses <- listParamT[PublicationStatus](
            "publicationStatus"
          ).map {
            case Nil => None
            case statuses => Some(statuses.toSet)
          }

          collectionId <- optParamT[Int]("collectionId")

          includeBannerUrl <- paramT[Boolean](
            "includeBannerUrl",
            default = false
          )

          includePublishedDataset <- paramT[Boolean](
            "includePublishedDataset",
            default = false
          )

          canPublish <- optParamT[Boolean]("canPublish")

          maybeOverridingRole = secureContainer.organizationRoleOverrides
            .get(secureContainer.organization.id)
            .flatten

          datasetsAndCount <- secureContainer.datasetManager
            .getDatasetPaginated(
              withRole = ownerOnly.getOrElse(withRole.getOrElse(Role.Viewer)),
              overrideRole = maybeOverridingRole,
              limit = limit.some,
              offset = offset.some,
              orderBy = (orderBy, orderByDirection),
              status = status,
              textSearch = textSearch,
              publicationStatuses = publicationStatuses,
              publicationTypes = publicationTypes,
              canPublish = canPublish,
              restrictToRole = withRole.isDefined || ownerOnly.isDefined,
              collectionId = collectionId
            )
            .coreErrorToActionResult

          traceId <- getTraceId(request)

          (datasetsAndStatus, datasetCount) = datasetsAndCount

          _ <- auditLogger
            .message()
            .append("description", "Dataset search results (paginated)")
            .append("organization-id", organization.id)
            .append("organization-node-id", organization.nodeId)
            .append("dataset-ids", datasetsAndStatus.map(_.dataset.id): _*)
            .append(
              "dataset-node-ids",
              datasetsAndStatus.map(_.dataset.nodeId): _*
            )
            .log(traceId)
            .toEitherT
            .coreErrorToActionResult

          dtos <- datasetDTOs(
            datasetsAndStatus,
            includeBannerUrl = includeBannerUrl,
            includePublishedDataset = includePublishedDataset
          )(
            asyncExecutor,
            secureContainer,
            storageServiceClient,
            datasetAssetClient,
            DataSetPublishingHelper.getPublishedDatasetsFromDiscover(
              publishClient
            ),
            system,
            jwtConfig
          ).orError

        } yield PaginatedDatasets(limit, offset, datasetCount, dtos)

      override val is = result.value.map(OkResult(_))
    }
  }

  post(
    "/",
    operation(
      apiOperation[DataSetDTO]("createDataSet")
        summary "creates a new data set that belongs to the current organization a user is logged into"
        parameters (
          bodyParam[CreateDataSetRequest]("body")
            .description("name and properties of new data set")
          )
    )
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, DataSetDTO] = for {
        secureContainer <- getSecureContainer
        traceId <- getTraceId(request)

        user = secureContainer.user

        body <- extractOrErrorT[CreateDataSetRequest](parsedBody)

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

        dataUseAgreement <- secureContainer.dataUseAgreementManager.getDefault.coreErrorToActionResult

        newDataset <- secureContainer.datasetManager
          .create(
            body.name,
            body.description,
            DatasetState.READY,
            statusId = Some(status.id),
            automaticallyProcessPackages = body.automaticallyProcessPackages,
            license = body.license,
            tags = body.tags,
            dataUseAgreement = dataUseAgreement
          )
          .coreErrorToActionResult

        _ <- secureContainer.changelogManager
          .logEvent(newDataset, ChangelogEventDetail.CreateDataset())
          .coreErrorToActionResult

        _ <- auditLogger
          .message()
          .append("dataset-id", newDataset.id)
          .append("dataset-node-id", newDataset.nodeId)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult

        dto <- datasetDTO(
          newDataset,
          status,
          None,
          contributors = Seq.empty,
          includeChildren = false
        )(
          asyncExecutor,
          secureContainer,
          datasetAssetClient,
          DataSetPublishingHelper.getPublishedDatasetsFromDiscover(
            publishClient
          ),
          system,
          jwtConfig
        ).orError
      } yield dto

      override val is = result.value.map(CreatedResult)
    }
  }

  val updateDataSetOperation: OperationBuilder = (apiOperation[DataSetDTO](
    "updateDataSet"
  )
    summary "updates a data set"
    parameters (
      pathParam[String]("id").description("data set id"),
      bodyParam[UpdateDataSetRequest]("body")
        .description("new dataset properties")
  ))

  put("/:id", operation(updateDataSetOperation)) {

    new AsyncResult {
      val result: EitherT[Future, ActionResult, DataSetDTO] = for {
        traceId <- getTraceId(request)
        datasetId <- paramT[String]("id")
        req <- extractOrErrorT[UpdateDataSetRequest](parsedBody)
        secureContainer <- getSecureContainer
        storageServiceClient = secureContainer.storageManager

        dataset <- secureContainer.datasetManager
          .getByNodeId(datasetId)
          .orNotFound

        _ <- secureContainer
          .authorizeDataset(
            Set(
              DatasetPermission.EditDatasetName,
              DatasetPermission.EditDatasetDescription,
              DatasetPermission.EditDatasetAutomaticallyProcessingPackages,
              DatasetPermission.EditContributors
            )
          )(dataset)
          .coreErrorToActionResult

        _ <- secureContainer.datasetManager
          .assertNotLocked(dataset)
          .coreErrorToActionResult

        _ <- checkIfMatchTimestamp("dataset", dataset.etag)

        oldStatus <- secureContainer.datasetStatusManager
          .get(dataset.statusId)
          .coreErrorToActionResult

        newStatus <- req.status match {
          case Some(name) => {
            secureContainer.db
              .run(secureContainer.datasetStatusManager.getByName(name))
              .toEitherT
              .coreErrorToActionResult
          }
          case None => // Keep existing status
            EitherT.rightT[Future, ActionResult](oldStatus)
        }

        // Sending dataUseAgreementId = null removes the data use agreement,
        // but excluding dataUseAgreementId from the request does nothing
        dataUseAgreementId <- req.dataUseAgreementId match {
          case Some(agreementId) =>
            secureContainer.dataUseAgreementManager
              .get(agreementId)
              .map(_.id.some)
              .coreErrorToActionResult
          case None if parsedBody.hasField("dataUseAgreementId") =>
            EitherT.rightT[Future, ActionResult](None)
          case _ =>
            EitherT.rightT[Future, ActionResult](dataset.dataUseAgreementId)
        }

        // Note: updating these columns runs the `dataset_update_etag` and
        // `dataset_update_updated_at` triggers in Postgres. If any new columns
        // are added to this endpoint those triggers must also be updated.
        updatedDataset <- secureContainer.datasetManager
          .update(
            dataset.copy(
              name = req.name.getOrElse(dataset.name),
              description = req.description.orElse(dataset.description),
              statusId = newStatus.id,
              automaticallyProcessPackages = req.automaticallyProcessPackages
                .getOrElse(dataset.automaticallyProcessPackages),
              license = req.license.orElse(dataset.license),
              tags = req.tags.getOrElse(dataset.tags),
              dataUseAgreementId = dataUseAgreementId
            )
          )
          .orForbidden

        _ <- secureContainer.changelogManager
          .logEvents(
            updatedDataset,
            diffDatasetChanges(dataset, updatedDataset, oldStatus, newStatus)
          )
          .coreErrorToActionResult

        organization = secureContainer.organization

        // user = secureContainer.user

        // _ <- {
        //   req.status match {
        //     // Validate that the update status was successful
        //     case Some(status) if status == newStatus.name =>
        //       val userName = s"${user.firstName} ${user.lastName}"

        //       val organizationProducerId = s"organization:${organization.id}"

        //       val message: EventMessage =
        //         DatasetStatusUpdateMessage(
        //           userName,
        //           dataset.name,
        //           newStatus.name,
        //           ProducerId(
        //             DigestUtils.md2Hex(
        //               organizationProducerId + s":dataset:${dataset.id}"
        //             )
        //           ),
        //           List(
        //             RelatedProducer(
        //               ProducerId(DigestUtils.md2Hex(s"user:${user.id}")),
        //               ProducerType.User,
        //               user.email
        //             ),
        //             RelatedProducer(
        //               ProducerId(DigestUtils.md2Hex(organizationProducerId)),
        //               ProducerType.Organization,
        //               organization.name
        //             )
        //           )
        //         )

        //       sqsClient.send(
        //         NotificationsCenterQueueUrl,
        //         message.asJson.noSpaces
        //       )

        //     case None => EitherT.rightT[Future, CoreError](())
        //   }
        // }.coreErrorToActionResult

        publicationStatus <- dataset.publicationStatusId
          .map(
            secureContainer.datasetPublicationStatusManager
              .getPublicationStatus(_)
              .coreErrorToActionResult
          )
          .getOrElse(EitherT.rightT[Future, ActionResult](None))

        contributors <- secureContainer.datasetManager
          .getContributors(dataset)
          .coreErrorToActionResult

        storageMap <- storageServiceClient
          .getStorage(sdatasets, List(updatedDataset.id))
          .orError
        storage = storageMap.get(dataset.id).flatten

        _ <- auditLogger
          .message()
          .append("dataset-id", updatedDataset.id)
          .append("dataset-node-id", updatedDataset.nodeId)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult

        dto <- datasetDTO(
          updatedDataset,
          newStatus,
          contributors = contributors.map(_._1),
          includeChildren = false,
          datasetPublicationStatus = publicationStatus,
          storage = storage
        )(
          asyncExecutor,
          secureContainer,
          datasetAssetClient,
          DataSetPublishingHelper.getPublishedDatasetsFromDiscover(
            publishClient
          ),
          system,
          jwtConfig
        ).orError

        _ <- setETagHeader(updatedDataset.etag)

      } yield dto

      override val is = result.value.map(OkResult)
    }
  }

  /**
    * Generate changelog events
    */
  def diffDatasetChanges(
    oldDataset: Dataset,
    newDataset: Dataset,
    oldStatus: DatasetStatus,
    newStatus: DatasetStatus
  ): List[(ChangelogEventDetail, Option[ZonedDateTime])] = {

    val events = mutable.ArrayBuffer[ChangelogEventDetail]()

    if (oldDataset.name != newDataset.name) {
      events += ChangelogEventDetail
        .UpdateName(oldName = oldDataset.name, newName = newDataset.name)
    }

    if (oldDataset.description != newDataset.description) {
      events += ChangelogEventDetail
        .UpdateDescription(
          oldDescription = oldDataset.description,
          newDescription = newDataset.description
        )
    }

    if (oldDataset.license != newDataset.license) {
      events += ChangelogEventDetail
        .UpdateLicense(
          oldLicense = oldDataset.license,
          newLicense = newDataset.license
        )
    }

    for (tag <- newDataset.tags.toSet -- oldDataset.tags.toSet) {
      events += ChangelogEventDetail.AddTag(tag)
    }

    for (tag <- oldDataset.tags.toSet -- newDataset.tags.toSet) {
      events += ChangelogEventDetail.RemoveTag(tag)
    }

    if (oldStatus.id != newStatus.id) {
      events += ChangelogEventDetail.UpdateStatus(
        oldStatus = oldStatus,
        newStatus = newStatus
      )
    }

    val now = ZonedDateTime.now()
    events.toList.map(e => (e, Some(now)))
  }

  val deleteDataSetOperation: OperationBuilder = (apiOperation[Done](
    "deleteDataSet"
  )
    summary "Delete a data set"
    parameters
      pathParam[String]("id").description("data set id"))

  delete("/:id", operation(deleteDataSetOperation)) {

    new AsyncResult {
      val deleteResult: EitherT[Future, ActionResult, Done] = for {
        traceId <- getTraceId(request)
        datasetId <- paramT[String]("id")
        secureContainer <- getSecureContainer

        dataset <- secureContainer.datasetManager
          .getByNodeId(datasetId)
          .orNotFound

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.DeleteDataset))(dataset)
          .coreErrorToActionResult

        // if the dataset is locked,
        // it is in the middle of the publishing process and cannot be deleted until that is done
        _ <- secureContainer.datasetManager
          .assertNotLocked(dataset)
          .coreErrorToActionResult

        datasetPublicationLog <- secureContainer.datasetPublicationStatusManager
          .getLogByDataset(dataset.id)
          .coreErrorToActionResult

        _ <- checkOrErrorT[CoreError](
          datasetPublicationLog.isEmpty ||
            datasetPublicationLog
              .find(_.publicationStatus == PublicationStatus.Completed)
              .forall(ps => ps.publicationType == PublicationType.Removal)
        )(
          PredicateError(
            "datasets must be unpublished from discover before they can be deleted"
          )
        ).coreErrorToActionResult

        organization = secureContainer.organization

        _ <- DataSetPublishingHelper
          .sendUnpublishRequest(
            organization,
            dataset,
            secureContainer.user,
            publishClient,
            sendNotification
          )(ec, system, jwtConfig)
          .coreErrorToActionResult

        deleteMessage <- secureContainer.datasetManager
          .delete(traceId, dataset)
          .orForbidden

        _ <- sqsClient
          .send(insecureContainer.sqs_queue, deleteMessage.asJson.noSpaces)
          .coreErrorToActionResult

      } yield Done

      override val is = deleteResult.value.map(OkResult)
    }
  }

  val reserveDoiOperation: OperationBuilder = (apiOperation[DoiDTO](
    "reserveDoi"
  )
    summary "reserves a new DOI for the data set"
    parameters (pathParam[String]("id").description("data set id"),
    bodyParam[CreateDraftDoiRequest]("body")
      .description("metadata about dataset for DOI")))

  post("/:id/doi", operation(reserveDoiOperation)) {

    new AsyncResult {
      val result: EitherT[Future, ActionResult, DoiDTO] = for {
        datasetId <- paramT[String]("id")
        body <- extractOrErrorT[CreateDraftDoiRequest](parsedBody)
        secureContainer <- getSecureContainer
        organization = secureContainer.organization

        dataset <- secureContainer.datasetManager
          .getByNodeId(datasetId)
          .orNotFound

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ReserveDoi))(dataset)
          .coreErrorToActionResult

        _ <- secureContainer.datasetManager
          .assertNotLocked(dataset)
          .coreErrorToActionResult

        currentOwner <- secureContainer.datasetManager
          .getOwner(dataset)
          .coreErrorToActionResult()

        contributorsAndUsers <- secureContainer.datasetManager
          .getContributors(dataset)
          .coreErrorToActionResult

        contributors = contributorsAndUsers.map {
          case (contributor, user) =>
            ContributorDTO(contributor, user)
        }

        contributorsName = contributors
          .map(
            x =>
              CreatorDTO(
                firstName = x.firstName,
                lastName = x.lastName,
                middleInitial = x.middleInitial,
                orcid = x.orcid
              )
          )
          .toIndexedSeq

        bodyWithDefaults = body.copy(
          title = Some(dataset.name),
          creators = Some(contributorsName)
        )

        token = JwtAuthenticator.generateServiceToken(
          1.minute,
          organization.id,
          Some(dataset.id)
        )

        tokenHeader = Authorization(OAuth2BearerToken(token.value))

        doiServiceResponse <- doiClient
          .createDraftDoi(
            organization.id,
            dataset.id,
            bodyWithDefaults,
            List(tokenHeader)
          )
          .leftMap {
            case Left(e) => InternalServerError(e.getMessage)
            case Right(resp) => InternalServerError(resp.toString)
          }

        doi <- handleCreateDoiResponse(
          secureContainer.user.nodeId,
          dataset.id,
          doiServiceResponse
        ).toEitherT[Future].coreErrorToActionResult

        _ <- secureContainer.datasetManager
          .touchUpdatedAtTimestamp(dataset)
          .coreErrorToActionResult

      } yield doi

      override val is = result.value.map(CreatedResult(_))
    }
  }
  val getDoiOperation: OperationBuilder = (apiOperation[DoiDTO]("getDoi")
    summary "retrieves DOI information for the data set"
    parameters (
      pathParam[String]("id").description("data set id")
    ))

  get("/:id/doi", operation(getDoiOperation)) {

    new AsyncResult {
      val result: EitherT[Future, ActionResult, DoiDTO] = for {
        datasetId <- paramT[String]("id")
        secureContainer <- getSecureContainer
        organization = secureContainer.organization

        dataset <- secureContainer.datasetManager
          .getByNodeId(datasetId)
          .orNotFound

        // DOI can be viewed if the dataset can be viewed
        _ <- secureContainer
          .authorizeDataset(
            Set(
              DatasetPermission.ViewFiles,
              DatasetPermission.ViewRecords,
              DatasetPermission.ViewAnnotations,
              DatasetPermission.ViewDiscussionComments
            )
          )(dataset)
          .coreErrorToActionResult

        token = JwtAuthenticator.generateServiceToken(
          1.minute,
          organization.id,
          Some(dataset.id)
        )

        tokenHeader = Authorization(OAuth2BearerToken(token.value))

        doiServiceResponse <- doiClient
          .getLatestDoi(organization.id, dataset.id, List(tokenHeader))
          .leftMap {
            case Left(e) => InternalServerError(e.getMessage)
            case Right(resp) => InternalServerError(resp.toString)
          }

        doi <- handleGetDoiResponse(
          secureContainer.user.nodeId,
          dataset.id,
          doiServiceResponse
        ).toEitherT[Future].coreErrorToActionResult

      } yield doi

      override val is = result.value.map(OkResult(_))

    }
  }

  // OLD PERMISSION SYSTEM

  val getCollaborators: OperationBuilder = (apiOperation[CollaboratorsDTO](
    "getCollaborators"
  )
    summary "get the collaborators of the data set"
    parameters
      pathParam[String]("id").description("data set id"))

  get("/:id/collaborators", operation(getCollaborators)) {
    new AsyncResult {
      val result = for {
        secureContainer <- getSecureContainer
        datasetId <- paramT[String]("id")
        dataset <- secureContainer.datasetManager
          .getByNodeId(datasetId)
          .orBadRequest

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ViewPeopleAndRoles))(dataset)
          .coreErrorToActionResult

        collaborators <- secureContainer.datasetManager
          .getCollaborators(dataset)
          .orBadRequest
        dto <- Builders
          .collaboratorsDTO(collaborators)(secureContainer)
          .orError
      } yield dto

      override val is = result.value.map(OkResult)
    }
  }

  val shareDataSetOperation
    : OperationBuilder = (apiOperation[CollaboratorChanges]("shareDataSet")
    summary "share this data set with another user"
    parameters (
      pathParam[String]("id").description("data set id"),
      bodyParam[Set[String]]("body")
        .description("List of user ids to share this data set with"),
  ))

  put("/:id/collaborators", operation(shareDataSetOperation)) {
    new AsyncResult {
      val shareResponse = for {
        datasetId <- paramT[String]("id")
        reqIds <- extractOrErrorT[Set[String]](parsedBody)
        secureContainer <- getSecureContainer
        dataset <- secureContainer.datasetManager
          .getByNodeId(datasetId)
          .coreErrorToActionResult

        _ <- secureContainer
          .authorizeDataset(
            Set(DatasetPermission.AddPeople, DatasetPermission.ChangeRoles)
          )(dataset)
          .coreErrorToActionResult

        results <- secureContainer.datasetManager
          .addCollaborators(dataset, reqIds)
          .coreErrorToActionResult

        // TODO logEvent
      } yield results

      override val is = shareResponse.value.map(OkResult)
    }
  }

  val unShareDataSetOperation
    : OperationBuilder = (apiOperation[CollaboratorChanges]("unShareDataSet")
    summary "unshare this data set with other users"
    parameters (
      pathParam[String]("id").description("data set id"),
      bodyParam[Set[String]]("body")
        .description("List of user ids to unshare this data set with"),
  ))

  delete("/:id/collaborators", operation(unShareDataSetOperation)) {
    new AsyncResult {
      val unshareResponse = for {
        secureContainer <- getSecureContainer
        datasetId <- paramT[String]("id")
        dataset <- secureContainer.datasetManager
          .getByNodeId(datasetId)
          .orNotFound

        _ <- secureContainer
          .authorizeDataset(
            Set(DatasetPermission.AddPeople, DatasetPermission.ChangeRoles)
          )(dataset)
          .coreErrorToActionResult

        _ <- secureContainer.datasetManager
          .assertNotLocked(dataset)
          .coreErrorToActionResult

        unShareIds <- extractOrErrorT[Set[String]](parsedBody)
        results <- secureContainer.datasetManager
          .removeCollaborators(dataset, unShareIds)
          .orForbidden

        // TODO: logEvent
      } yield results

      override val is = unshareResponse.value.map(OkResult(_))
    }
  }

  val getDatasetPermission
    : OperationBuilder = (apiOperation[DatasetPermissionResponse](
    "getDatasetPermission"
  )
    summary "get the user's effective permission to the dataset"
    parameters
      pathParam[String]("id").description("data set id"))

  get("/:id/permission", operation(getDatasetPermission)) {
    new AsyncResult {
      val results: EitherT[Future, ActionResult, DatasetPermissionResponse] =
        for {
          secureContainer <- getSecureContainer
          user = secureContainer.user
          datasetNodeId <- paramT[String]("id")

          dataset <- secureContainer.datasetManager
            .getByNodeId(datasetNodeId)
            .coreErrorToActionResult

          _ <- secureContainer
            .authorizeDataset(Set(DatasetPermission.ViewPeopleAndRoles))(
              dataset
            )
            .coreErrorToActionResult
          role <- secureContainer.datasetManager
            .maxRole(dataset, user)
            .coreErrorToActionResult
        } yield {
          DatasetPermissionResponse(user.id, dataset.id, role.toPermission)
        }

      override val is = results.value.map(OkResult)
    }
  }

  //CONTRIBUTORS

  val getContributors: OperationBuilder = (apiOperation[Seq[ContributorDTO]](
    "getContributors"
  )
    summary "get the contributors to the data set"
    parameters
      pathParam[String]("id").description("data set id"))

  get("/:id/contributors", operation(getContributors)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Seq[ContributorDTO]] =
        for {
          secureContainer <- getSecureContainer
          datasetId <- paramT[String]("id")
          dataset <- secureContainer.datasetManager
            .getByNodeId(datasetId)
            .orNotFound

          contributorsAndUsers <- secureContainer.datasetManager
            .getContributors(dataset)
            .coreErrorToActionResult
        } yield contributorsAndUsers.map(ContributorDTO(_))

      override val is = result.value.map(OkResult(_))
    }
  }

  val addContributor: OperationBuilder = (apiOperation("addContributor")
    summary "add a contributor to this dataset"
    parameters (
      pathParam[String]("id").description("data set id"),
      bodyParam[AddContributorRequest]("body")
        .description("contributor id to add to the dataset")
  ))

  put("/:id/contributors", operation(addContributor)) {

    new AsyncResult {
      val addResponse: EitherT[Future, ActionResult, ContributorDTO] = for {
        datasetId <- paramT[String]("id")
        contributorId <- extractOrErrorT[AddContributorRequest](parsedBody)

        secureContainer <- getSecureContainer
        dataset <- secureContainer.datasetManager
          .getByNodeId(datasetId)
          .orNotFound
        _ <- secureContainer.datasetManager
          .assertNotLocked(dataset)
          .coreErrorToActionResult

        results <- secureContainer.datasetManager
          .addContributor(dataset, contributorId.contributorId)
          .coreErrorToActionResult

        dto <- secureContainer.contributorsManager
          .getContributor(contributorId.contributorId)
          .coreErrorToActionResult
          .map(ContributorDTO(_))

        _ <- secureContainer.changelogManager
          .logEvent(dataset, ChangelogEventDetail.AddContributor(dto))
          .coreErrorToActionResult

        _ <- secureContainer.datasetManager
          .touchUpdatedAtTimestamp(dataset)
          .coreErrorToActionResult

      } yield dto

      override val is = addResponse.value.map(OkResult)
    }
  }

  val switchContributorsOrder: OperationBuilder = (apiOperation(
    "switchContributorsOrder"
  )
    summary "switch the position of the two contributors"
    parameters (
      pathParam[String]("datasetId").description("data set id"),
      bodyParam[SwitchContributorsOrderRequest]("body")
        .description("ids of the contributor whose order need to be switched")
  ))

  post("/:datasetId/contributors/switch", operation(switchContributorsOrder)) {

    new AsyncResult {
      val switchResponse: EitherT[Future, ActionResult, Unit] = for {
        datasetId <- paramT[String]("datasetId")
        body <- extractOrErrorT[SwitchContributorsOrderRequest](parsedBody)

        secureContainer <- getSecureContainer

        dataset <- secureContainer.datasetManager
          .getByNodeId(datasetId)
          .orNotFound

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.EditContributors))(dataset)
          .coreErrorToActionResult

        _ <- secureContainer.datasetManager
          .assertNotLocked(dataset)
          .coreErrorToActionResult

        contributor <- secureContainer.datasetManager
          .getContributor(dataset, body.contributorId)
          .coreErrorToActionResult

        otherContributor <- secureContainer.datasetManager
          .getContributor(dataset, body.otherContributorId)
          .coreErrorToActionResult

        _ <- secureContainer.datasetManager
          .switchContributorOrder(dataset, contributor, otherContributor)
          .coreErrorToActionResult

        _ <- secureContainer.datasetManager
          .touchUpdatedAtTimestamp(dataset)
          .coreErrorToActionResult

      } yield ()
      override val is = switchResponse.value.map(OkResult)
    }
  }

  val removeContributor: OperationBuilder = (apiOperation("removeContributor")
    summary "remove contributor from this dataset"
    parameters (
      pathParam[String]("datasetId").description("data set node id"),
      pathParam[Int]("contributorId")
        .description("contributor id to remove from the dataset"),
  ))

  delete(
    "/:datasetId/contributors/:contributorId",
    operation(removeContributor)
  ) {
    new AsyncResult {
      val removeResponse: EitherT[Future, ActionResult, Unit] = for {
        datasetId <- paramT[String]("datasetId")
        contributorId <- paramT[Int]("contributorId")

        secureContainer <- getSecureContainer
        dataset <- secureContainer.datasetManager
          .getByNodeId(datasetId)
          .orNotFound

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.EditContributors))(dataset)
          .coreErrorToActionResult

        _ <- secureContainer.datasetManager
          .assertNotLocked(dataset)
          .coreErrorToActionResult

        toDelete <- secureContainer.contributorsManager
          .getContributor(contributorId)
          .coreErrorToActionResult
          .map(ContributorDTO(_))

        _ <- secureContainer.datasetManager
          .removeContributor(dataset, contributorId)
          .coreErrorToActionResult

        _ <- secureContainer.changelogManager
          .logEvent(dataset, ChangelogEventDetail.RemoveContributor(toDelete))
          .coreErrorToActionResult

        _ <- secureContainer.datasetManager
          .touchUpdatedAtTimestamp(dataset)
          .coreErrorToActionResult
      } yield ()

      override val is = removeResponse.value.map(OkResult)
    }
  }

  // COLLECTIONS

  val getCollections: OperationBuilder = (apiOperation[Seq[CollectionDTO]](
    "getCollections"
  )
    summary "get the collections to the data set"
    parameters
      pathParam[String]("id").description("data set id"))

  get("/:id/collections", operation(getCollections)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Seq[CollectionDTO]] =
        for {
          secureContainer <- getSecureContainer
          datasetId <- paramT[String]("id")
          dataset <- secureContainer.datasetManager
            .getByNodeId(datasetId)
            .orNotFound

          collections <- secureContainer.datasetManager
            .getCollections(dataset)
            .coreErrorToActionResult
        } yield collections.map(CollectionDTO(_))

      override val is = result.value.map(OkResult(_))
    }
  }

  val addCollection: OperationBuilder = (apiOperation("addCollection")
    summary "add this dataset to a collection"
    parameters (
      pathParam[String]("id").description("data set id"),
      bodyParam[AddCollectionRequest]("body")
        .description("collection id which the dataset is to be adeed to")
  ))

  put("/:id/collections", operation(addCollection)) {

    new AsyncResult {
      val result: EitherT[Future, ActionResult, Seq[CollectionDTO]] = for {
        datasetId <- paramT[String]("id")
        addCollectionRequest <- extractOrErrorT[AddCollectionRequest](
          parsedBody
        )

        secureContainer <- getSecureContainer

        dataset <- secureContainer.datasetManager
          .getByNodeId(datasetId)
          .orNotFound

        _ <- secureContainer.datasetManager
          .assertNotLocked(dataset)
          .coreErrorToActionResult

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ManageDatasetCollections))(
            dataset
          )
          .coreErrorToActionResult

        collection <- secureContainer.datasetManager
          .addCollection(dataset, addCollectionRequest.collectionId)
          .coreErrorToActionResult

        _ <- secureContainer.changelogManager
          .logEvent(dataset, ChangelogEventDetail.AddCollection(collection))
          .coreErrorToActionResult

        collections <- secureContainer.datasetManager
          .getCollections(dataset)
          .coreErrorToActionResult

        _ <- secureContainer.datasetManager
          .touchUpdatedAtTimestamp(dataset)
          .coreErrorToActionResult

      } yield collections.map(CollectionDTO(_))

      override val is = result.value.map(OkResult(_))
    }
  }

  val removeCollection: OperationBuilder = (apiOperation("removeCollection")
    summary "remove this dataset from the Collection"
    parameters (
      pathParam[String]("datasetId").description("data set node id"),
      pathParam[Int]("collectionId")
        .description("collection id from which the dataset is to be removed"),
  ))

  delete("/:datasetId/collections/:collectionId", operation(removeCollection)) {
    new AsyncResult {
      val removeResponse: EitherT[Future, ActionResult, Unit] = for {
        datasetId <- paramT[String]("datasetId")
        collectionId <- paramT[Int]("collectionId")

        secureContainer <- getSecureContainer
        dataset <- secureContainer.datasetManager
          .getByNodeId(datasetId)
          .orNotFound

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ManageDatasetCollections))(
            dataset
          )
          .coreErrorToActionResult

        _ <- secureContainer.datasetManager
          .assertNotLocked(dataset)
          .coreErrorToActionResult

        collection <- secureContainer.datasetManager
          .removeCollection(dataset, collectionId)
          .coreErrorToActionResult

        _ <- secureContainer.changelogManager
          .logEvent(dataset, ChangelogEventDetail.RemoveCollection(collection))
          .coreErrorToActionResult

        _ <- secureContainer.datasetManager
          .touchUpdatedAtTimestamp(dataset)
          .coreErrorToActionResult
      } yield ()

      override val is = removeResponse.value.map(OkResult)
    }
  }
  // NEW ROLE-BASED PERMISSIONS

  val getUserCollaborators
    : OperationBuilder = (apiOperation[List[UserCollaboratorRoleDTO]](
    "getUserCollaborators"
  )
    summary "get the individual users collaborating on the data set"
    parameters
      pathParam[String]("id").description("data set id"))

  get("/:id/collaborators/users", operation(getUserCollaborators)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, List[UserCollaboratorRoleDTO]] =
        for {
          secureContainer <- getSecureContainer
          datasetId <- paramT[String]("id")
          dataset <- secureContainer.datasetManager
            .getByNodeId(datasetId)
            .orNotFound

          _ <- secureContainer
            .authorizeDataset(Set(DatasetPermission.ViewPeopleAndRoles))(
              dataset
            )
            .coreErrorToActionResult
          usersAndRoles <- secureContainer.datasetManager
            .getUserCollaborators(dataset)
            .coreErrorToActionResult
        } yield
          usersAndRoles.map {
            case (user, role) =>
              UserCollaboratorRoleDTO(
                user.nodeId,
                user.firstName,
                user.lastName,
                user.email,
                role
              )
          }
      override val is = result.value.map(OkResult(_))
    }
  }

  val addUserCollaborator: OperationBuilder = (apiOperation(
    "addUserCollaborator"
  )
    summary "add a user as a collaborator on this dataset"
    parameters (
      pathParam[String]("id").description("data set id"),
      bodyParam[CollaboratorRoleDTO]("body")
        .description("User to share this dataset with")
  ))

  put("/:id/collaborators/users", operation(addUserCollaborator)) {
    new AsyncResult {
      val addResponse: EitherT[Future, ActionResult, Unit] = for {
        datasetId <- paramT[String]("id")
        userDto <- extractOrErrorT[CollaboratorRoleDTO](parsedBody)

        secureContainer <- getSecureContainer
        dataset <- secureContainer.datasetManager
          .getByNodeId(datasetId)
          .orNotFound

        _ <- secureContainer
          .authorizeDataset(
            Set(DatasetPermission.AddPeople, DatasetPermission.ChangeRoles)
          )(dataset)
          .coreErrorToActionResult

        user <- secureContainer.userManager
          .getByNodeId(userDto.id)
          .orNotFound

        oldRole <- secureContainer.datasetManager
          .addUserCollaborator(dataset, user, userDto.role)
          .coreErrorToActionResult

        _ <- secureContainer.changelogManager
          .logEvent(
            dataset,
            ChangelogEventDetail.UpdatePermission(
              oldRole = oldRole.oldRole,
              newRole = userDto.role.some,
              userId = user.id.some,
              teamId = None,
              organizationId = None
            )
          )
          .coreErrorToActionResult

      } yield ()

      override val is = addResponse.value.map(OkResult)
    }
  }

  val deleteUserCollaborator: OperationBuilder = (apiOperation(
    "deleteUserCollaborator"
  )
    summary "remove a user who is a collaborator on the dataset"
    parameters (
      pathParam[String]("id").description("data set id"),
      bodyParam[CollaboratorRoleDTO]("body")
        .description("User to unshare this dataset with")
  ))

  delete("/:id/collaborators/users", operation(deleteUserCollaborator)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Unit] =
        for {
          secureContainer <- getSecureContainer
          datasetId <- paramT[String]("id")
          userDto <- extractOrErrorT[RemoveCollaboratorRequest](parsedBody)
          dataset <- secureContainer.datasetManager
            .getByNodeId(datasetId)
            .orNotFound

          _ <- secureContainer
            .authorizeDataset(
              Set(DatasetPermission.AddPeople, DatasetPermission.ChangeRoles)
            )(dataset)
            .coreErrorToActionResult

          user <- secureContainer.userManager
            .getByNodeId(userDto.id)
            .orNotFound
          oldRole <- secureContainer.datasetManager
            .deleteUserCollaborator(dataset, user)
            .orForbidden

          _ <- secureContainer.changelogManager
            .logEvent(
              dataset,
              ChangelogEventDetail.UpdatePermission(
                oldRole = oldRole.oldRole,
                newRole = None,
                userId = user.id.some,
                teamId = None,
                organizationId = None
              )
            )
            .coreErrorToActionResult

        } yield ()

      override val is = result.value.map(OkResult(_))
    }
  }

  val getTeamCollaborators
    : OperationBuilder = (apiOperation[List[TeamCollaboratorRoleDTO]](
    "getTeamCollaborators"
  )
    summary "get all teams collaborating on the data set"
    parameters
      pathParam[String]("id").description("data set id"))

  get("/:id/collaborators/teams", operation(getTeamCollaborators)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, List[TeamCollaboratorRoleDTO]] =
        for {
          secureContainer <- getSecureContainer
          datasetId <- paramT[String]("id")
          dataset <- secureContainer.datasetManager
            .getByNodeId(datasetId)
            .orNotFound

          _ <- secureContainer
            .authorizeDataset(Set(DatasetPermission.ViewPeopleAndRoles))(
              dataset
            )
            .coreErrorToActionResult
          teamsAndRoles <- secureContainer.datasetManager
            .getTeamCollaborators(dataset)
            .coreErrorToActionResult
        } yield
          teamsAndRoles.map {
            case (team, role) =>
              TeamCollaboratorRoleDTO(team.nodeId, team.name, role)
          }
      override val is = result.value.map(OkResult(_))
    }
  }

  val addTeamCollaborator: OperationBuilder = (apiOperation(
    "addTeamCollaborator"
  )
    summary "share this dataset with a team"
    parameters (
      pathParam[String]("id").description("data set id"),
      bodyParam[CollaboratorRoleDTO]("body")
        .description("Team to share this dataset with")
  ))

  put("/:id/collaborators/teams", operation(addTeamCollaborator)) {
    new AsyncResult {
      val addResponse: EitherT[Future, ActionResult, Unit] = for {
        datasetId <- paramT[String]("id")
        teamDto <- extractOrErrorT[CollaboratorRoleDTO](parsedBody)
        secureContainer <- getSecureContainer
        dataset <- secureContainer.datasetManager
          .getByNodeId(datasetId)
          .orNotFound

        _ <- secureContainer
          .authorizeDataset(
            Set(DatasetPermission.AddPeople, DatasetPermission.ChangeRoles)
          )(dataset)
          .coreErrorToActionResult

        team <- secureContainer.organizationManager
          .getTeamWithOrganizationTeamByNodeId(
            secureContainer.organization,
            teamDto.id
          )
          .orNotFound

        _ <- checkOrErrorT(team._2.systemTeamType isEmpty)(
          BadRequest(
            s"team ${teamDto.id} is managed by Pennsieve and cannot be added by users"
          )
        )

        oldRole <- secureContainer.datasetManager
          .addTeamCollaborator(dataset, team._1, teamDto.role)
          .coreErrorToActionResult

        _ <- secureContainer.changelogManager
          .logEvent(
            dataset,
            ChangelogEventDetail.UpdatePermission(
              oldRole = oldRole.oldRole,
              newRole = teamDto.role.some,
              userId = None,
              teamId = team._1.id.some,
              organizationId = None
            )
          )
          .coreErrorToActionResult
      } yield ()

      override val is = addResponse.value.map(OkResult)
    }
  }

  val deleteTeamCollaborator: OperationBuilder = (apiOperation(
    "deleteTeamCollaborator"
  )
    summary "unshare this dataset with a team"
    parameters (
      pathParam[String]("id").description("data set id"),
      bodyParam[CollaboratorRoleDTO]("body")
        .description("Team to unshare this dataset with")
  ))

  delete("/:id/collaborators/teams", operation(deleteTeamCollaborator)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Unit] =
        for {
          secureContainer <- getSecureContainer
          datasetId <- paramT[String]("id")
          teamDto <- extractOrErrorT[RemoveCollaboratorRequest](parsedBody)
          dataset <- secureContainer.datasetManager
            .getByNodeId(datasetId)
            .orNotFound

          _ <- secureContainer
            .authorizeDataset(
              Set(DatasetPermission.AddPeople, DatasetPermission.ChangeRoles)
            )(dataset)
            .coreErrorToActionResult

          team <- secureContainer.organizationManager
            .getTeamWithOrganizationTeamByNodeId(
              secureContainer.organization,
              teamDto.id
            )
            .orNotFound

          _ <- checkOrErrorT(team._2.systemTeamType isEmpty)(
            BadRequest(
              s"team ${teamDto.id} is managed by Blackynn and cannot be removed by users"
            )
          )
          oldRole <- secureContainer.datasetManager
            .deleteTeamCollaborator(dataset, team._1)
            .orForbidden

          _ <- secureContainer.changelogManager
            .logEvent(
              dataset,
              ChangelogEventDetail.UpdatePermission(
                oldRole = oldRole.oldRole,
                newRole = None,
                userId = None,
                teamId = team._1.id.some,
                organizationId = None
              )
            )
            .coreErrorToActionResult

        } yield ()

      override val is = result.value.map(OkResult(_))
    }
  }

  val getOrganizationCollaboratorRole: OperationBuilder = (apiOperation(
    "getOrganizationCollaboratorRole"
  )
    summary "get the organizations allowed role on the dataset"
    parameters (
      pathParam[String]("id").description("data set id"),
      bodyParam[OrganizationRoleDTO]("body")
        .description("Role of the organization")
  ))

  get(
    "/:id/collaborators/organizations",
    operation(getOrganizationCollaboratorRole)
  ) {
    new AsyncResult {
      val getResponse: EitherT[Future, ActionResult, OrganizationRoleDTO] =
        for {
          datasetId <- paramT[String]("id")
          secureContainer <- getSecureContainer
          dataset <- secureContainer.datasetManager
            .getByNodeId(datasetId)
            .orNotFound

          _ <- secureContainer
            .authorizeDataset(Set(DatasetPermission.ViewPeopleAndRoles))(
              dataset
            )
            .coreErrorToActionResult
        } yield
          OrganizationRoleDTO(
            dataset.role,
            Some(secureContainer.organization.nodeId),
            Some(secureContainer.organization.name)
          )

      override val is = getResponse.value.map(OkResult)
    }
  }

  val setOrganizationCollaboratorRole: OperationBuilder = (apiOperation(
    "setOrganizationCollaboratorRole"
  )
    summary "share this dataset with the rest of the organization"
    parameters (
      pathParam[String]("id").description("data set id"),
      bodyParam[OrganizationRoleDTO]("body")
        .description("Role to set for the organization")
  ))

  put(
    "/:id/collaborators/organizations",
    operation(setOrganizationCollaboratorRole)
  ) {
    new AsyncResult {
      val addResponse: EitherT[Future, ActionResult, Unit] = for {
        datasetId <- paramT[String]("id")
        organizationRoleRequest <- extractOrErrorT[OrganizationRoleDTO](
          parsedBody
        )
        secureContainer <- getSecureContainer
        dataset <- secureContainer.datasetManager
          .getByNodeId(datasetId)
          .orNotFound

        _ <- secureContainer
          .authorizeDataset(
            Set(DatasetPermission.AddPeople, DatasetPermission.ChangeRoles)
          )(dataset)
          .coreErrorToActionResult

        _ <- secureContainer.datasetManager
          .setOrganizationCollaboratorRole(
            dataset,
            organizationRoleRequest.role
          )
          .orForbidden

        _ <- secureContainer.changelogManager
          .logEvent(
            dataset,
            ChangelogEventDetail.UpdatePermission(
              oldRole = dataset.role,
              newRole = organizationRoleRequest.role,
              userId = None,
              teamId = None,
              organizationId = secureContainer.organization.id.some
            )
          )
          .coreErrorToActionResult

      } yield ()

      override val is = addResponse.value.map(OkResult)
    }
  }

  val removeOrganizationCollaborator: OperationBuilder = (apiOperation(
    "removeOrganizationCollaborator"
  )
    summary "unshare this dataset with the organization"
    parameters (
      pathParam[String]("id").description("data set id")
    ))

  delete(
    "/:id/collaborators/organizations",
    operation(removeOrganizationCollaborator)
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Unit] =
        for {
          secureContainer <- getSecureContainer
          datasetId <- paramT[String]("id")
          dataset <- secureContainer.datasetManager
            .getByNodeId(datasetId)
            .orNotFound

          _ <- secureContainer
            .authorizeDataset(
              Set(DatasetPermission.AddPeople, DatasetPermission.ChangeRoles)
            )(dataset)
            .coreErrorToActionResult

          _ <- secureContainer.datasetManager
            .setOrganizationCollaboratorRole(dataset, None)
            .orForbidden

          _ <- secureContainer.changelogManager
            .logEvent(
              dataset,
              ChangelogEventDetail.UpdatePermission(
                oldRole = dataset.role,
                newRole = None,
                userId = None,
                teamId = None,
                organizationId = secureContainer.organization.id.some
              )
            )
            .coreErrorToActionResult

        } yield ()

      override val is = result.value.map(OkResult(_))
    }
  }

  val getDatasetRole: OperationBuilder = (apiOperation[DatasetRoleResponse](
    "getDatasetRole"
  )
    summary "get the user's effective role on the dataset"
    parameters
      pathParam[String]("id").description("data set id"))

  get("/:id/role", operation(getDatasetRole)) {
    new AsyncResult {
      val results: EitherT[Future, ActionResult, DatasetRoleResponse] =
        for {
          secureContainer <- getSecureContainer
          user = secureContainer.user
          datasetId <- paramT[String]("id")
          dataset <- secureContainer.datasetManager
            .getByNodeId(datasetId)
            .orForbidden

          role <- secureContainer.datasetManager
            .maxRole(dataset, user)
            .coreErrorToActionResult
        } yield {
          DatasetRoleResponse(
            userId = user.id,
            datasetId = dataset.id,
            role = role
          )
        }
      override val is = results.value.map(OkResult)
    }
  }

  val switchOwner: OperationBuilder = (apiOperation("switchOwner")
    summary "switch the owner of a dataset. Previous owner is downgraded to manager"
    parameters (
      pathParam[String]("id").description("data set node id"),
      bodyParam[String]("body").description("intended new owner node id")
  ))

  put("/:id/collaborators/owner", operation(switchOwner)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Unit] = for {

        traceId <- getTraceId(request)

        datasetId <- paramT[String]("id")

        body <- extractOrErrorT[SwitchOwnerRequest](parsedBody)

        secureContainer <- getSecureContainer

        newOwner <- secureContainer.userManager
          .getByNodeId(body.id)
          .coreErrorToActionResult

        dataset <- secureContainer.datasetManager
          .getByNodeId(datasetId)
          .coreErrorToActionResult

        _ <- secureContainer.datasetManager
          .assertNotLocked(dataset)
          .coreErrorToActionResult

        _ <- auditLogger
          .message()
          .append("dataset-id", dataset.id)
          .append("dataset-node-id", dataset.nodeId)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult

        currentOwner <- secureContainer.datasetManager
          .getOwner(dataset)
          .coreErrorToActionResult()

        user = secureContainer.user
        organization = secureContainer.organization

        _ <- checkOrErrorT(currentOwner.id == user.id)(
          Forbidden("Must be owner to change ownership")
        )

        _ <- secureContainer.datasetManager
          .switchOwner(dataset, currentOwner, newOwner)
          .coreErrorToActionResult

        _ <- secureContainer.changelogManager
          .logEvent(
            dataset,
            ChangelogEventDetail
              .UpdateOwner(oldOwner = currentOwner.id, newOwner = newOwner.id)
          )
          .coreErrorToActionResult

        _ <- secureContainer.datasetManager
          .touchUpdatedAtTimestamp(dataset)
          .coreErrorToActionResult

        message = insecureContainer.messageTemplates
          .datasetOwnerChangedNotification(
            newOwner.email,
            currentOwner,
            dataset,
            organization
          )

        _ <- insecureContainer.emailer
          .sendEmail(
            to = Email(newOwner.email),
            from = Settings.support_email,
            message = message,
            subject = s"You’re now a dataset owner"
          )
          .leftMap(error => InternalServerError(error.getMessage))
          .toEitherT[Future]

      } yield ()
      override val is = result.value.map(OkResult(_))
    }
  }

  val getPackages: OperationBuilder = {
    (apiOperation[PackagesPage]("getPackages")
      summary "get the packages in a dataset"
      parameters (
        pathParam[String]("id").description("data set id"),
        queryParam[Option[String]]("cursor")
          .description("cursor to get next page"),
        queryParam[Int]("pageSize")
          .defaultValue(100)
          .description("number of packages in page"),
        queryParam[Boolean]("includeSourceFiles")
          .defaultValue(false)
          .description("include source files for each package"),
        queryParam[Option[String]]("filename")
          .description(
            "returns only the packages that have a file matching the filename"
          ),
        queryParam[Option[Set[String]]]("types")
          .description("optional flag to include only packages of this type")
    ))
  }

  private val IdExtractor = "package:(\\d+)".r

  private val DefaultPageSize: Int =
    insecureContainer.config
      .getInt("pennsieve.packages_pagination.default_page_size")

  private val MaxPageSize: Int =
    insecureContainer.config
      .getInt("pennsieve.packages_pagination.max_page_size")

  private val ExtractFilters = """(\s*\w+)(\s*:\s*\w+)*""".r

  implicit val convertToSetPackageType
    : String => Either[ActionResult, Set[PackageType]] =
    packageTypes =>
      ExtractFilters.unapplySeq(packageTypes) match {
        case Some(strings) =>
          strings
            .filter(_ != null)
            .map { s =>
              val strWithoutColons = s.replace(":", "")
              PackageType
                .withNameInsensitiveOption(strWithoutColons)
                .toRight(BadRequest("Invalid type name"))
            }
            .sequence
            .map(_.toSet)

        case None =>
          PackageType
            .withNameInsensitiveOption(packageTypes)
            .toRight(BadRequest("Invalid type name"))
            .map(Set(_))
      }

  get("/:id/packages", operation(getPackages)) {
    new AsyncResult {
      private def createPackagesPage(
        page: Seq[ExtendedPackageDTO],
        pageSize: Int,
        dataset: Dataset
      ): PackagesPage =
        if (page.length == pageSize + 1) {
          val cursor =
            page.lastOption
              .map { nextPackage =>
                s"package:${nextPackage.content.id}"
              }

          PackagesPage(page.dropRight(1), cursor)
        } else {
          PackagesPage(page, cursor = None)
        }

      val result: EitherT[Future, ActionResult, PackagesPage] = {
        for {
          traceId <- getTraceId(request)

          datasetId <- paramT[String]("id")

          cursor <- optParamT[String]("cursor")

          filename <- optParamT[String]("filename")

          includeSources <- paramT[Boolean](
            "includeSourceFiles",
            default = false
          )

          maybePackageTypes <- optParamT[Set[PackageType]]("types")

          pageSize <- {
            paramT[Int]("pageSize", default = DefaultPageSize)
              .subflatMap { size =>
                if (size > MaxPageSize)
                  BadRequest(
                    s"Invalid page size must be less than or equal to $MaxPageSize"
                  ).asLeft
                else size.asRight
              }
          }

          startAtId <- {
            EitherT.fromEither[Future] {
              cursor match {
                case Some(IdExtractor(id)) =>
                  Try(Some(id.toInt)).toEither
                    .leftMap(
                      _ => BadRequest("Cursor format must be package:{integer}")
                    )

                case Some(_) =>
                  BadRequest("Cursor format must be package:{integer}").asLeft

                case None => None.asRight
              }
            }
          }

          secureContainer <- getSecureContainer
          organization = secureContainer.organization

          dataset <- secureContainer.datasetManager
            .getByNodeId(datasetId)
            .coreErrorToActionResult

          packagesPageAndFiles <- (
            if (includeSources) {
              secureContainer.packageManager
                .getPackagesPageWithFiles(
                  dataset,
                  startAtId,
                  pageSize,
                  maybePackageTypes,
                  filename
                )
            } else {
              secureContainer.packageManager
                .getPackagesPage(
                  dataset,
                  startAtId,
                  pageSize,
                  maybePackageTypes,
                  filename
                )
                .map(_.toSeq)
                .map(pkgs => pkgs.map(p => (p, Seq.empty, false)))
            }
          ).coreErrorToActionResult()

          packages = packagesPageAndFiles.map(_._1)

          singleSourcePackageMap <- secureContainer.fileManager
            .getSingleSourceMap(packages)
            .toEitherT
            .coreErrorToActionResult()

          packageDtos = packagesPageAndFiles.map {
            case (p, files, isTruncated) => {
              ExtendedPackageDTO.simple(
                `package` = p,
                dataset = dataset,
                objects = buildSimpleFileMapIfNonEmpty(files, p),
                isTruncated = if (isTruncated) Some(isTruncated) else None,
                withExtension = singleSourcePackageMap
                  .get(p.id)
                  .flatten
                  .flatMap(_.fileExtension)
              )
            }
          }

          packagesPage = createPackagesPage(packageDtos, pageSize, dataset)

          _ <- auditLogger
            .message()
            .append("description", "Dataset packages")
            .append("organization-id", organization.id)
            .append("organization-node-id", organization.nodeId)
            .append("package-ids", packagesPage.packages.map(_.content.id): _*)
            .append(
              "package-node-ids",
              packagesPage.packages.map(_.content.nodeId): _*
            )
            .log(traceId)
            .toEitherT
            .coreErrorToActionResult

        } yield packagesPage
      }

      override val is = result.value.map(OkResult)
    }
  }

  val getBatchPackagesOperation = (apiOperation[BatchPackagesPage](
    "getBatchPackages"
  )
    summary "get multiple packages"
    parameters (pathParam[String]("id").required.description("dataset id"),
    queryParam[ExternalId]("packageId").description("package id"),
  ))

  get("/:id/packages/batch", operation(getBatchPackagesOperation)) {

    new AsyncResult {
      val result = for {
        secureContainer <- getSecureContainer
        user = secureContainer.user
        organization = secureContainer.organization

        traceId <- getTraceId(request)
        datasetNodeId <- paramT[String]("id")
        packageIds <- listParamT[ExternalId]("packageId")

        dataset <- secureContainer.datasetManager
          .getByNodeId(datasetNodeId)
          .orError

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ViewFiles))(dataset)
          .coreErrorToActionResult

        packages <- secureContainer.packageManager
          .getByExternalIdsForDataset(dataset, packageIds)
          .coreErrorToActionResult

        _ <- auditLogger
          .message()
          .append("description", "Dataset batch packages")
          .append("organization-id", organization.id)
          .append("organization-node-id", organization.nodeId)
          .append("package-ids", packages.map(_.id): _*)
          .append("package-node-ids", packages.map(_.nodeId): _*)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult

        storage <- secureContainer.storageManager
          .getStorage(spackages, packages.map(_.id))
          .coreErrorToActionResult

        packageSingleSourceMap <- secureContainer.fileManager
          .getSingleSourceMap(packages)
          .toEitherT
          .coreErrorToActionResult

        dtos = packages.map(
          pkg =>
            ExtendedPackageDTO
              .simple(
                `package` = pkg,
                dataset = dataset,
                storage = storage.get(pkg.id).flatten,
                withExtension = packageSingleSourceMap
                  .get(pkg.id)
                  .flatten
                  .flatMap(_.fileExtension)
              )
        )

        notFound = (packageIds
          .to[Set] -- (packages.map(_.id).map(ExternalId.intId _) ++ packages
          .map(_.nodeId)
          .map(ExternalId.nodeId _))).toList

      } yield
        BatchPackagesPage(
          packages = dtos,
          failures = notFound.map(BatchPackagesFailure(_, "not found"))
        )

      override val is = result.value
    }
  }

  val getPublishStatus: OperationBuilder = (
    apiOperation[DatasetPublishStatus]("getPublishStatus")
      summary "retrieve the publishing status of a dataset"
      parameters (pathParam[String]("id").required.description("dataset id"))
  )

  get("/:id/published", operation(getPublishStatus)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, DatasetPublishStatus] =
        for {
          datasetNodeId <- paramT[String]("id")

          secureContainer <- getSecureContainer
          organization = secureContainer.organization

          dataset <- secureContainer.datasetManager
            .getByNodeId(datasetNodeId)
            .coreErrorToActionResult

          status <- DataSetPublishingHelper
            .getPublishedStatus(
              publishClient,
              organization,
              dataset,
              secureContainer.user
            )(ec, system, jwtConfig)
            .coreErrorToActionResult
        } yield status

      val is = result.value.map(OkResult)
    }
  }

  val getPublishStatuses: OperationBuilder = (
    apiOperation[DatasetPublishStatus]("getPublishStatuses")
      summary "retrieve the publishing status of all datasets in the organization"
  )

  get("/published", operation(getPublishStatuses)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, List[DatasetPublishStatus]] =
        for {
          secureContainer <- getSecureContainer
          statuses <- DataSetPublishingHelper
            .getPublishedStatusForOrganization(
              publishClient,
              secureContainer.organization,
              secureContainer.user
            )(ec, system, jwtConfig)
            .coreErrorToActionResult

        } yield statuses

      val is = result.value.map(OkResult)
    }
  }

  def publicationAnnotation(
    publicationStatus: PublicationStatus,
    summary: String
  ): OperationBuilder = {
    (apiOperation(s"${publicationStatus.entryName}PublicationRevision")
      summary summary
      parameters (
        pathParam[String]("id").required.description("dataset id"),
        queryParam[String]("publicationType").required
          .description(
            "this field must match the currently in-progress publication workflow"
          ),
        queryParam[String]("comments").optional
          .description("optional explanation"),
        queryParam[String]("embargoReleaseDate").optional
          .description(
            "release date for embargoed datasets, for example: 2020-03-17"
          )
    ))
  }

  post(
    "/:id/publication/request",
    operation(
      publicationAnnotation(
        PublicationStatus.Requested,
        "submit the dataset to publishers for publication or revision"
      )
    )
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, DatasetPublicationStatus] =
        for {
          secureContainer <- getSecureContainer
          publicationType <- paramT[PublicationType]("publicationType")
          comments <- optParamT[String]("comments")
          datasetId <- paramT[String]("id")
          embargoReleaseDate <- optParamT[LocalDate]("embargoReleaseDate")
            .recover {
              // TODO: remove once https://app.clickup.com/t/acke9r is deployed
              case _ => None
            }

          validated <- DataSetPublishingHelper
            .validatePublicationStatusRequest(
              secureContainer,
              datasetId,
              PublicationStatus.Requested,
              publicationType
            )(request, ec, system, jwtConfig)
            .coreErrorToActionResult

          embargoReleaseDate <- embargoReleaseDate match {
            case Some(releaseDate)
                if validated.publicationType == PublicationType.Embargo =>
              checkOrErrorT(
                releaseDate.isAfter(LocalDate.now.minusDays(1)) && releaseDate
                  .isBefore(LocalDate.now.plusYears(1).plusDays(1))
              )(
                BadRequest(
                  "Invalid date. Date must be between now and 1 year in the future."
                )
              ).map(_ => Some(releaseDate))
            case None if validated.publicationType == PublicationType.Embargo =>
              EitherT.leftT[Future, Option[LocalDate]](
                BadRequest("Missing embargoReleaseDate parameter")
              )
            case _ =>
              EitherT.rightT[Future, ActionResult](None)
          }

          contributors <- secureContainer.datasetManager
            .getContributors(validated.dataset)
            .map(_.map(ContributorDTO(_)))
            .coreErrorToActionResult

          _ <- DataSetPublishingHelper
            .gatherPublicationInfo(validated, contributors)
            .coreErrorToActionResult

          _ <- DataSetPublishingHelper
            .addPublisherTeam(secureContainer, validated.dataset)
            .coreErrorToActionResult

          response <- secureContainer.datasetPublicationStatusManager
            .create(
              dataset = validated.dataset,
              publicationStatus = validated.publicationStatus,
              publicationType = validated.publicationType,
              comments = comments,
              embargoReleaseDate = embargoReleaseDate
            )
            .coreErrorToActionResult

          _ <- if (validated.publicationType == PublicationType.Publication) {
            DataSetPublishingHelper.emailContributorsPublicationRequest(
              insecureContainer,
              contributors,
              validated.dataset,
              secureContainer.organization,
              secureContainer.user
            )
          } else {
            EitherT
              .rightT[Future, CoreError](List[SesMessageResult]())
              .coreErrorToActionResult
          }

        } yield response
      val is = result.value.map(CreatedResult)
    }
  }

  post(
    "/:id/publication/cancel",
    operation(
      publicationAnnotation(
        PublicationStatus.Cancelled,
        "cancel a request for publication or revision"
      )
    )
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, DatasetPublicationStatus] =
        for {
          secureContainer <- getSecureContainer

          publicationType <- paramT[PublicationType]("publicationType")
          comments <- optParamT[String]("comments")
          datasetId <- paramT[String]("id")

          validated <- DataSetPublishingHelper
            .validatePublicationStatusRequest(
              secureContainer,
              datasetId,
              PublicationStatus.Cancelled,
              publicationType
            )(request, ec, system, jwtConfig)
            .coreErrorToActionResult

          _ <- DataSetPublishingHelper
            .removePublisherTeam(secureContainer, validated.dataset)
            .coreErrorToActionResult

          _ <- secureContainer.packageManager
            .markFilesInPendingStateAsUploaded(dataset = validated.dataset)
            .coreErrorToActionResult

          response <- secureContainer.datasetPublicationStatusManager
            .create(
              dataset = validated.dataset,
              publicationStatus = validated.publicationStatus,
              publicationType = validated.publicationType,
              comments = comments,
              embargoReleaseDate = validated.embargoReleaseDate
            )
            .coreErrorToActionResult

        } yield response

      val is = result.value.map(CreatedResult)
    }
  }

  post(
    "/:id/publication/reject",
    operation(
      publicationAnnotation(
        PublicationStatus.Rejected,
        "reject a request to publish or revise a dataset"
      )
    )
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, DatasetPublicationStatus] =
        for {
          secureContainer <- getSecureContainer

          publicationType <- paramT[PublicationType]("publicationType")
          comments <- optParamT[String]("comments")
          datasetId <- paramT[String]("id")

          validated <- DataSetPublishingHelper
            .validatePublicationStatusRequest(
              secureContainer,
              datasetId,
              PublicationStatus.Rejected,
              publicationType
            )(request, ec, system, jwtConfig)
            .coreErrorToActionResult

          contributors <- secureContainer.datasetManager
            .getContributors(validated.dataset)
            .map(_.map(ContributorDTO(_)))
            .coreErrorToActionResult

          _ <- DataSetPublishingHelper
            .removePublisherTeam(secureContainer, validated.dataset)
            .coreErrorToActionResult

          response <- secureContainer.datasetPublicationStatusManager
            .create(
              dataset = validated.dataset,
              publicationStatus = validated.publicationStatus,
              publicationType = validated.publicationType,
              comments = comments,
              embargoReleaseDate = validated.embargoReleaseDate
            )
            .coreErrorToActionResult

          _ <- secureContainer.packageManager
            .markFilesInPendingStateAsUploaded(dataset = validated.dataset)
            .coreErrorToActionResult

          _ <- if (validated.publicationType == PublicationType.Publication) {
            DataSetPublishingHelper.emailContributorsDatasetRejected(
              insecureContainer,
              contributors,
              validated.dataset,
              secureContainer.user,
              validated.owner,
              secureContainer.organization
            )
          } else {
            EitherT
              .rightT[Future, CoreError](List[SesMessageResult]())
              .coreErrorToActionResult
          }

        } yield response

      val is = result.value.map(CreatedResult)
    }
  }

  post(
    "/:id/publication/accept",
    operation(
      publicationAnnotation(
        PublicationStatus.Accepted,
        "notifies the Discover service to extract and publish a dataset and knowledge graph, or revise the metadata of that graph"
      )
    )
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, DatasetPublicationStatus] =
        for {
          secureContainer <- getSecureContainer
          publicationType <- paramT[PublicationType]("publicationType")
          comments <- optParamT[String]("comments")
          datasetId <- paramT[String]("id")

          validated <- DataSetPublishingHelper
            .validatePublicationStatusRequest(
              secureContainer,
              datasetId,
              PublicationStatus.Accepted,
              publicationType
            )(request, ec, system, jwtConfig)
            .coreErrorToActionResult

          contributors <- secureContainer.datasetManager
            .getContributors(validated.dataset)
            .map(_.map(ContributorDTO(_)))
            .coreErrorToActionResult

          collections <- secureContainer.datasetManager
            .getCollections(validated.dataset)
            .map(_.map(CollectionDTO(_)))
            .coreErrorToActionResult

          externalPublications <- secureContainer.externalPublicationManager
            .get(validated.dataset)
            .coreErrorToActionResult

          currentPublicationStatus <- DataSetPublishingHelper
            .getPublishedStatus(
              publishClient,
              secureContainer.organization,
              validated.dataset,
              secureContainer.user
            )(ec, system, jwtConfig)
            .coreErrorToActionResult

          response <- validated.publicationType match {
            case PublicationType.Publication | PublicationType.Embargo =>
              for {
                publicationInfo <- DataSetPublishingHelper
                  .gatherPublicationInfo(validated, contributors)
                  .coreErrorToActionResult

                _ <- DataSetPublishingHelper
                  .sendPublishRequest(
                    secureContainer,
                    validated.dataset,
                    validated.owner,
                    publicationInfo.ownerOrcid,
                    publicationInfo.description,
                    publicationInfo.license,
                    contributors.toList,
                    validated.publicationType == PublicationType.Embargo,
                    modelServiceClient,
                    publishClient,
                    sendNotification,
                    validated.embargoReleaseDate,
                    collections,
                    externalPublications
                  )(ec, system, jwtConfig)
                  .coreErrorToActionResult

                response <- secureContainer.datasetPublicationStatusManager
                  .create(
                    dataset = validated.dataset,
                    publicationStatus = validated.publicationStatus,
                    publicationType = validated.publicationType,
                    comments = comments,
                    embargoReleaseDate = validated.embargoReleaseDate
                  )
                  .coreErrorToActionResult

                _ <- DataSetPublishingHelper.emailContributorsDatasetAccepted(
                  insecureContainer,
                  contributors,
                  validated.dataset,
                  currentPublicationStatus.publishedVersionCount + 1,
                  secureContainer.user,
                  validated.owner
                )

              } yield response
            case PublicationType.Revision =>
              for {
                publicationInfo <- DataSetPublishingHelper
                  .gatherPublicationInfo(validated, contributors)
                  .coreErrorToActionResult

                _ <- DataSetPublishingHelper
                  .sendReviseRequest(
                    secureContainer,
                    validated.dataset,
                    validated.owner,
                    publicationInfo.ownerOrcid,
                    publicationInfo.description,
                    publicationInfo.license,
                    contributors.toList,
                    publishClient,
                    datasetAssetClient,
                    sendNotification,
                    collections,
                    externalPublications
                  )(ec, system, jwtConfig)
                  .coreErrorToActionResult()

                _ <- secureContainer.datasetPublicationStatusManager
                  .create(
                    dataset = validated.dataset,
                    publicationStatus = validated.publicationStatus,
                    publicationType = validated.publicationType,
                    comments = None,
                    embargoReleaseDate = validated.embargoReleaseDate
                  )
                  .coreErrorToActionResult

                _ <- DataSetPublishingHelper.emailContributorsRevisionAccepted(
                  insecureContainer,
                  contributors,
                  validated.dataset,
                  validated.owner,
                  currentPublicationStatus.publishedDatasetId
                )

                // remove the publishing team for revisions since the process is complete
                _ <- DataSetPublishingHelper
                  .removePublisherTeam(secureContainer, validated.dataset)
                  .coreErrorToActionResult

                // add entries for both Accept and Complete, since the revise job is syncronous
                response <- secureContainer.datasetPublicationStatusManager
                  .create(
                    dataset = validated.dataset,
                    publicationStatus = PublicationStatus.Completed,
                    publicationType = validated.publicationType,
                    comments = comments,
                    embargoReleaseDate = validated.embargoReleaseDate
                  )
                  .coreErrorToActionResult
              } yield response
            case PublicationType.Removal =>
              for {

                _ <- DataSetPublishingHelper
                  .sendUnpublishRequest(
                    secureContainer.organization,
                    validated.dataset,
                    secureContainer.user,
                    publishClient,
                    sendNotification
                  )(ec, system, jwtConfig)
                  .coreErrorToActionResult

                _ <- secureContainer.datasetPublicationStatusManager
                  .create(
                    dataset = validated.dataset,
                    publicationStatus = validated.publicationStatus,
                    publicationType = validated.publicationType,
                    comments = None,
                    embargoReleaseDate = validated.embargoReleaseDate
                  )
                  .coreErrorToActionResult

                // remove the publishing team for withdrawals since the process is complete
                _ <- DataSetPublishingHelper
                  .removePublisherTeam(secureContainer, validated.dataset)
                  .coreErrorToActionResult

                // add entries for both Accept and Complete, since the unpublish job is syncronous
                response <- secureContainer.datasetPublicationStatusManager
                  .create(
                    dataset = validated.dataset,
                    publicationStatus = PublicationStatus.Completed,
                    publicationType = validated.publicationType,
                    comments = comments,
                    embargoReleaseDate = validated.embargoReleaseDate
                  )
                  .coreErrorToActionResult
              } yield response

            case PublicationType.Release =>
              for {
                _ <- DataSetPublishingHelper
                  .sendReleaseRequest(
                    secureContainer.organization,
                    validated.dataset,
                    secureContainer.user,
                    publishClient,
                    sendNotification
                  )(ec, system, jwtConfig)
                  .coreErrorToActionResult

                response <- secureContainer.datasetPublicationStatusManager
                  .create(
                    dataset = validated.dataset,
                    publicationStatus = PublicationStatus.Accepted,
                    publicationType = validated.publicationType,
                    comments = comments,
                    embargoReleaseDate = validated.embargoReleaseDate
                  )
                  .coreErrorToActionResult

                _ <- DataSetPublishingHelper
                  .emailContributorsEmbargoedDatasetReleasedAccepted(
                    insecureContainer,
                    contributors,
                    validated.dataset,
                    secureContainer.user,
                    validated.owner
                  )
              } yield response
          }
        } yield response

      val is = result.value.map(CreatedResult)
    }
  }

  val publishComplete: OperationBuilder = (apiOperation("publishComplete")
    summary "notify API that Discover has completed a publish job"
    parameters (pathParam[Int]("id").required.description("dataset id"),
    bodyParam[PublishCompleteRequest]("body")
      .description("status and error message from publish job")))

  put("/:id/publication/complete", operation(publishComplete)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Unit] =
        for {
          datasetId <- paramT[Int]("id")

          secureContainer <- getSecureContainer
          organization = secureContainer.organization
          _ <- checkOrErrorT(secureContainer.user.isSuperAdmin)(
            Forbidden("Must be superadmin to complete publish")
          )

          dataset <- secureContainer.datasetManager
            .get(datasetId)
            .coreErrorToActionResult

          owner <- secureContainer.datasetManager
            .getOwner(dataset)
            .coreErrorToActionResult

          body <- extractOrErrorT[PublishCompleteRequest](parsedBody)

          publicationStatus <- dataset.publicationStatusId
            .map(secureContainer.datasetPublicationStatusManager.get(_))
            .getOrElse({
              logger.error(
                s"Publish complete has been called on a dataset with a publication status of None"
              )
              EitherT.leftT[Future, DatasetPublicationStatus](
                PredicateError(
                  "Publish complete has been called on a dataset with a publication status of None"
                ): CoreError
              )
            })
            .coreErrorToActionResult

          _ = if (publicationStatus.publicationStatus != PublicationStatus.Accepted) {
            logger.error(
              s"Publish complete has been called on a dataset with a publication status of ${publicationStatus.publicationStatus}"
            )
          }

          _ <- checkOrErrorT(
            publicationStatus.publicationType in Seq(
              PublicationType.Publication,
              PublicationType.Embargo,
              PublicationType.Release
            )
          )(
            PredicateError(
              s"Publication type must be publication, embargo, or release, but was ${publicationStatus.publicationType}"
            ): CoreError
          ).coreErrorToActionResult

          _ <- secureContainer.datasetPublicationStatusManager
            .create(
              dataset = dataset,
              publicationStatus =
                if (body.success)
                  PublicationStatus.Completed
                else PublicationStatus.Failed,
              publicationType = publicationStatus.publicationType,
              comments = publicationStatus.comments,
              embargoReleaseDate = publicationStatus.embargoReleaseDate
            )
            .coreErrorToActionResult

          // Only remove the publisher team if the publish was successful.
          // In the case of failure, a subsequent success (via retry) or a
          // reject will remove it

          _ <- (if (body.success) {
                  DataSetPublishingHelper.removePublisherTeam(
                    secureContainer,
                    dataset
                  )
                } else {
                  EitherT.rightT[Future, CoreError](())
                }).coreErrorToActionResult

          contributors <- secureContainer.datasetManager
            .getContributors(dataset)
            .map(_.map(ContributorDTO(_)))
            .coreErrorToActionResult

          notification = if (body.success) {
            logger.info(s"Publish complete for dataset ${dataset.nodeId}")

            DiscoverPublishNotification(
              List(owner.id),
              MessageType.DatasetUpdate,
              "Dataset published to Discover.",
              dataset.id,
              body.publishedDatasetId,
              body.publishedVersionCount,
              body.lastPublishedDate,
              body.status,
              success = true,
              None
            )
          } else {
            logger.error(
              s"Publish failed for dataset ${dataset.nodeId}: ${body.error}"
            )

            DiscoverPublishNotification(
              List(owner.id),
              MessageType.DatasetUpdate,
              "Dataset publish failed.",
              dataset.id,
              body.publishedDatasetId,
              body.publishedVersionCount,
              body.lastPublishedDate,
              body.status,
              success = false,
              body.error
            )
          }
          _ <- sendNotification(notification).coreErrorToActionResult

          _ <- if (body.success) for {
            publishedDatasetId <- body.publishedDatasetId
              .toRight[ActionResult](
                InternalServerError("Missing published dataset ID")
              )
              .toEitherT[Future]

            //find all the files with a state of pending and mark them as Uploaded
            _ <- secureContainer.packageManager
              .markFilesInPendingStateAsUploaded(dataset = dataset)
              .coreErrorToActionResult

            _ <- publicationStatus.publicationType match {
              case PublicationType.Embargo =>
                DataSetPublishingHelper.emailContributorsDatasetEmbargoed(
                  insecureContainer,
                  contributors,
                  dataset,
                  owner,
                  publicationStatus.embargoReleaseDate.get
                )
              case PublicationType.Publication =>
                DataSetPublishingHelper.emailContributorsDatasetPublished(
                  insecureContainer,
                  contributors,
                  owner,
                  dataset,
                  publishedDatasetId
                )
              case PublicationType.Release =>
                DataSetPublishingHelper
                  .emailContributorsEmbargoedDatasetReleased(
                    insecureContainer,
                    contributors,
                    dataset,
                    owner,
                    publishedDatasetId
                  )
              case _ => EitherT.rightT[Future, ActionResult](())
            }

          } yield ()
          else EitherT.rightT[Future, ActionResult](())

        } yield ()

      val is = result.value.map(OkResult)
    }
  }

  post(
    "/:id/publication/release",
    operation(
      publicationAnnotation(
        PublicationStatus.Accepted,
        "internal use only: release an embargoed dataset to Discover"
      )
    )
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, DatasetPublicationStatus] =
        for {
          secureContainer <- getSecureContainer
          datasetId <- paramT[Int]("id")

          _ <- checkOrErrorT(isServiceClaim(request))(
            Forbidden("Internal use only")
          )

          dataset <- secureContainer.datasetManager
            .get(datasetId)
            .coreErrorToActionResult

          publicationLog <- secureContainer.datasetPublicationStatusManager
            .getLogByDataset(dataset.id)
            .coreErrorToActionResult

          _ <- DataSetPublishingHelper
            .validatePublicationStateTransition(
              dataset,
              publicationLog,
              PublicationStatus.Requested,
              PublicationType.Release
            )
            .toEitherT[Future]
            .coreErrorToActionResult

          _ <- DataSetPublishingHelper
            .sendReleaseRequest(
              secureContainer.organization,
              dataset,
              secureContainer.user,
              publishClient,
              sendNotification
            )(ec, system, jwtConfig)
            .coreErrorToActionResult

          _ <- secureContainer.datasetPublicationStatusManager
            .create(
              dataset = dataset,
              publicationStatus = PublicationStatus.Requested,
              publicationType = PublicationType.Release,
              comments = None,
              embargoReleaseDate = None // TODO: fill this in?
            )
            .coreErrorToActionResult

          response <- secureContainer.datasetPublicationStatusManager
            .create(
              dataset = dataset,
              publicationStatus = PublicationStatus.Accepted,
              publicationType = PublicationType.Release,
              comments = None,
              embargoReleaseDate = None // TODO: fill this in?
            )
            .coreErrorToActionResult

        } yield response

      val is = result.value.map(CreatedResult)
    }
  }

  val getPreview: OperationBuilder = (apiOperation[List[DatasetPreviewerDTO]](
    "getPreview"
  )
    summary "retrieve the list of user that have preview rights on the dataset"
    description "this endpoint is under active development and subject to change"
    parameter (
      pathParam[String]("id").description("data set id")
    ))

  get("/:id/publication/preview", operation(getPreview)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, List[DatasetPreviewerDTO]] =
        for {
          secureContainer <- getSecureContainer
          datasetId <- paramT[String]("id")

          dataset <- secureContainer.datasetManager
            .getByAnyId(datasetId)
            .coreErrorToActionResult

          _ <- secureContainer
            .authorizeDataset(Set(DatasetPermission.AddPeople))(dataset)
            .coreErrorToActionResult

          previewersAndUsers <- secureContainer.datasetPreviewManager
            .getPreviewers(dataset)
            .coreErrorToActionResult

        } yield
          previewersAndUsers.map {
            case (previewer, user) => DatasetPreviewerDTO(user, previewer)
          }.toList

      val is = result.value.map(OkResult)
    }
  }

  val postPreview: OperationBuilder = (
    apiOperation("postPreview")
      summary "Grant preview access to a user. This endpoint can either approve an access request, or grant access to a net-new user."
      description "this endpoint is under active development and subject to change"
      parameters (
        pathParam[String]("id").description("data set id"),
        bodyParam[GrantPreviewAccessRequest]("body")
          .description(
            "user id for which preview access to the dataset should be granted"
          )
    )
  )

  // TODO:
  // the PreviewAccessRequest will need to be expanded to include an organization ID once we open this up to
  // cross-organization previews

  post("/:id/publication/preview", operation(postPreview)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Unit] =
        for {
          secureContainer <- getSecureContainer
          datasetId <- paramT[String]("id")

          dataset <- secureContainer.datasetManager
            .getByAnyId(datasetId)
            .coreErrorToActionResult

          _ <- secureContainer
            .authorizeDataset(Set(DatasetPermission.AddPeople))(dataset)
            .coreErrorToActionResult

          grantPreviewAccessRequest <- extractOrErrorT[
            GrantPreviewAccessRequest
          ](parsedBody)

          user <- secureContainer.userManager
            .get(grantPreviewAccessRequest.userId)
            .coreErrorToActionResult

          _ <- secureContainer.datasetManager
            .canShareWithUser(user)
            .coreErrorToActionResult

          _ <- secureContainer.datasetPreviewManager
            .grantAccess(dataset, user)
            .coreErrorToActionResult

          discoverDatasetId <- DataSetPublishingHelper
            .getPublishedStatus(
              publishClient,
              secureContainer.organization,
              dataset,
              secureContainer.user
            )
            .map(_.publishedDatasetId)
            .coreErrorToActionResult

          _ <- DataSetPublishingHelper.emailPreviewerEmbargoAccessAccepted(
            insecureContainer,
            requestingUser = user,
            manager = secureContainer.user,
            secureContainer.organization,
            dataset,
            discoverDatasetId
          )

        } yield ()

      val is = result.value.map(OkResult)
    }
  }

  val deletePreview: OperationBuilder = (
    apiOperation("deletePreview")
      summary "Remove preview access to a user."
      description "this endpoint is under active development and subject to change"
      parameters (
        pathParam[String]("id").description("data set id"),
        bodyParam[RemovePreviewAccessRequest]("body")
          .description(
            "user id for which preview access to the dataset should be revoked"
          )
    )
  )

  delete("/:id/publication/preview", operation(deletePreview)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Unit] =
        for {
          secureContainer <- getSecureContainer
          datasetId <- paramT[String]("id")

          dataset <- secureContainer.datasetManager
            .getByAnyId(datasetId)
            .coreErrorToActionResult

          _ <- secureContainer
            .authorizeDataset(Set(DatasetPermission.AddPeople))(dataset)
            .coreErrorToActionResult

          removePreviewAccesRequest <- extractOrErrorT[
            RemovePreviewAccessRequest
          ](parsedBody)

          user <- secureContainer.userManager
            .get(removePreviewAccesRequest.userId)
            .coreErrorToActionResult

          removed <- secureContainer.datasetPreviewManager
            .removeAccess(dataset, user)
            .coreErrorToActionResult

          // Only send email to user if this was a pending request
          _ <- removed match {
            case Some(previewer)
                if previewer.embargoAccess == EmbargoAccess.Requested =>
              DataSetPublishingHelper
                .emailPreviewerEmbargoAccessDenied(
                  insecureContainer,
                  requestingUser = user,
                  manager = secureContainer.user,
                  secureContainer.organization,
                  dataset
                )
                .map(_ => ())

            case _ => EitherT.rightT[Future, ActionResult](())
          }

        } yield ()

      val is = result.value.map(OkResult)
    }
  }

  val requestPreview: OperationBuilder = (
    apiOperation("requestPreview")
      summary "Request preview access to a dataset for the current user."
      description "this endpoint is under active development and subject to change"
      parameters (
        bodyParam[PreviewAccessRequest]("body")
          .description("dataset node id for which access is requested")
        )
  )

  post("/publication/preview/request", operation(requestPreview)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Unit] =
        for {
          secureContainer <- getSecureContainer

          _ <- checkOrErrorT(secureContainer.user.isSuperAdmin)(
            Forbidden("Must be superadmin to request a preview")
          )

          previewAccessRequest <- extractOrErrorT[PreviewAccessRequest](
            parsedBody
          )

          dataset <- secureContainer.datasetManager
            .get(previewAccessRequest.datasetId)
            .coreErrorToActionResult

          datasetPublicationLog <- secureContainer.datasetPublicationStatusManager
            .getLogByDataset(dataset.id)
            .coreErrorToActionResult

          lastCompletedPublicationType = datasetPublicationLog
            .find(_.publicationStatus in PublicationStatus.Completed)
            .map(_.publicationType)

          isEmbargoed = lastCompletedPublicationType
            .map(t => t == PublicationType.Embargo)
            .getOrElse(false)

          _ <- checkOrErrorT(isEmbargoed)(
            BadRequest("Dataset must be under embargo")
          )

          user <- secureContainer.userManager
            .get(previewAccessRequest.userId)
            .coreErrorToActionResult

          // Check that the user signed the use agreement for the dataset
          _ <- (
            dataset.dataUseAgreementId,
            previewAccessRequest.dataUseAgreementId
          ) match {
            case (Some(datasetAgreement), Some(signedAgreement))
                if datasetAgreement == signedAgreement =>
              for {
                dataUseAgreement <- secureContainer.dataUseAgreementManager
                  .get(datasetAgreement)
                  .coreErrorToActionResult

                _ <- secureContainer.datasetPreviewManager
                  .requestAccess(dataset, user, Some(dataUseAgreement))
                  .coreErrorToActionResult
              } yield ()

            case (Some(datasetAgreement), _) =>
              EitherT
                .leftT[Future, Unit](
                  PredicateError(
                    s"Missing or invalid dataUseAgreementId - must sign data use agreement $datasetAgreement"
                  ): CoreError
                )
                .orBadRequest
            case _ =>
              secureContainer.datasetPreviewManager
                .requestAccess(dataset, user, None)
                .coreErrorToActionResult
          }

          owner <- secureContainer.datasetManager
            .getOwner(dataset)
            .coreErrorToActionResult

          managers <- secureContainer.datasetManager
            .getManagers(dataset)
            .coreErrorToActionResult

          _ <- DataSetPublishingHelper.emailManagersEmbargoAccessRequested(
            insecureContainer,
            user,
            owner,
            managers,
            secureContainer.organization,
            dataset
          )
        } yield ()

      val is = result.value.map(OkResult)
    }
  }

  val getDataUseAgreement
    : OperationBuilder = (apiOperation[Option[DataUseAgreement]](
    "getDataUseAgreement"
  )
    summary "get the data use agreement for this dataset"
    parameter (
      pathParam[String]("id").description("data set id")
    ))

  get("/:id/publication/data-use-agreement", operation(getDataUseAgreement)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Option[DataUseAgreementDTO]] =
        for {
          secureContainer <- getSecureContainer
          datasetId <- paramT[ExternalId]("id")

          dataset <- secureContainer.datasetManager
            .getByExternalIdWithMaxRole(datasetId)
            .map(_._1)
            .coreErrorToActionResult

          _ <- secureContainer
            .authorizeDataset(Set(DatasetPermission.ViewPeopleAndRoles))(
              dataset
            )
            .coreErrorToActionResult

          dataUseAgreement <- dataset.dataUseAgreementId
            .traverse(secureContainer.dataUseAgreementManager.get(_))
            .coreErrorToActionResult

        } yield dataUseAgreement.map(DataUseAgreementDTO(_))

      // Return 204 No Content if dataset does not have an agreement
      val is = result.value.map {
        case r @ Right(Some(_)) => OkResult(r)
        case r @ Right(None) => NoContentResult(r)
        case Left(errorResult) => errorResult
      }
    }
  }

  def handleGetDoiResponse(
    userId: String,
    datasetId: Int,
    response: GetLatestDoiResponse
  ): Either[CoreError, DoiDTO] = {
    response match {
      case GetLatestDoiResponse.OK(dto) => {
        dto.as[DoiDTO] match {
          case Right(decodedDoi) =>
            Right(decodedDoi)
          case Left(decodingFailure) =>
            Left(ParseError(decodingFailure))
        }
      }
      case GetLatestDoiResponse.NotFound(e) =>
        Left(domain.NotFound(e))
      case GetLatestDoiResponse.Forbidden(e) =>
        Left(DatasetRolePermissionError(userId, datasetId))
      case GetLatestDoiResponse.InternalServerError(e) =>
        Left(ServiceError(e))
      case GetLatestDoiResponse.Unauthorized =>
        Left(UnauthorizedError(s"DOI service authorization failed"))
    }
  }

  def handleCreateDoiResponse(
    userId: String,
    datasetId: Int,
    response: CreateDraftDoiResponse
  ): Either[CoreError, DoiDTO] = {
    response match {
      case CreateDraftDoiResponse.Created(dto) => {
        dto.as[DoiDTO] match {
          case Right(decodedDoi) =>
            Right(decodedDoi)
          case Left(decodingFailure) =>
            Left(ParseError(decodingFailure))
        }
      }
      case CreateDraftDoiResponse.BadRequest(e) =>
        Left(PredicateError(e))
      case CreateDraftDoiResponse.Forbidden(e) =>
        Left(DatasetRolePermissionError(userId, datasetId))
      case CreateDraftDoiResponse.InternalServerError(e) =>
        Left(ServiceError(e))
      case CreateDraftDoiResponse.Unauthorized =>
        Left(UnauthorizedError(s"DOI service authorization failed"))
    }
  }

  /**
    * The amount of time that presigned S3 URLs for banner assets should be
    * valid.
    */
  private val bannerTTL: Duration = 30.minutes

  val uploadBanner: OperationBuilder = (
    apiOperation[DatasetBannerDTO]("uploadBanner")
      summary "upload the banner image for a dataset"
      consumes "multipart/form-data"
      parameters (pathParam[String]("id").required.description("dataset id"),
      formParam[String]("banner").required
        .description("file to upload"))
  )

  // TODO: restrict to images
  put("/:id/banner", operation(uploadBanner)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, DatasetBannerDTO] = for {
        datasetId <- paramT[String]("id")

        secureContainer <- getSecureContainer

        dataset <- secureContainer.datasetManager
          .getByNodeId(datasetId)
          .coreErrorToActionResult

        _ <- secureContainer
          .authorizeDataset(
            Set(
              DatasetPermission.ShowSettingsPage,
              DatasetPermission.EditDatasetDescription
            )
          )(dataset)
          .coreErrorToActionResult

        _ <- secureContainer.datasetManager
          .assertNotLocked(dataset)
          .coreErrorToActionResult

        fileItem <- fileParams.get("banner").orBadRequest().toEitherT[Future]

        oldAsset <- secureContainer.datasetAssetsManager
          .getBanner(dataset)
          .coreErrorToActionResult

        bannerAsset <- secureContainer.datasetAssetsManager
          .createOrUpdateBanner(
            dataset,
            datasetAssetClient.bucket,
            fileItem.name,
            asset =>
              datasetAssetClient
                .uploadAsset(
                  asset,
                  fileItem.size,
                  fileItem.contentType,
                  fileItem.getInputStream
                )
                .map(_ => asset),
            asset => datasetAssetClient.deleteAsset(asset).map(_ => asset)
          )
          .coreErrorToActionResult

        _ <- secureContainer.changelogManager
          .logEvent(
            dataset,
            ChangelogEventDetail.UpdateBannerImage(
              oldBanner = oldAsset.map(_.s3Url),
              newBanner = Some(bannerAsset.s3Url)
            )
          )
          .coreErrorToActionResult

        _ <- secureContainer.datasetManager
          .touchUpdatedAtTimestamp(dataset)
          .coreErrorToActionResult

        bannerUrl <- datasetAssetClient
          .generatePresignedUrl(bannerAsset, bannerTTL)
          .leftMap[CoreError](e => ExceptionError(new Exception(e)))
          .toEitherT[Future]
          .coreErrorToActionResult

      } yield DatasetBannerDTO(Some(bannerUrl))

      val is = result.value.map(OkResult)
    }
  }

  val getBanner: OperationBuilder = (
    apiOperation[Unit]("getBanner")
      summary "get a presigned URL for the banner image of a dataset"
      parameters (pathParam[String]("id").required.description("dataset id"))
  )
  get("/:id/banner", operation(getBanner)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, DatasetBannerDTO] = for {
        datasetId <- paramT[String]("id")

        secureContainer <- getSecureContainer

        dataset <- secureContainer.datasetManager
          .getByNodeId(datasetId)
          .coreErrorToActionResult

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ViewFiles))(dataset)
          .coreErrorToActionResult

        bannerAsset <- secureContainer.datasetAssetsManager
          .getBanner(dataset)
          .coreErrorToActionResult

        // Generate a presigned URL for the banner S3 asset, if it exists
        bannerUrl <- bannerAsset
          .map(datasetAssetClient.generatePresignedUrl(_, bannerTTL))
          .sequence
          .leftMap[CoreError](e => ExceptionError(new Exception(e)))
          .toEitherT[Future]
          .coreErrorToActionResult

      } yield DatasetBannerDTO(bannerUrl)

      val is = result.value.map(OkResult)
    }
  }

  val updateReadme: OperationBuilder = (
    apiOperation[Unit]("putReadme")
      summary "update the README description for a dataset"
      parameters (pathParam[String]("id").required.description("dataset id"),
      bodyParam[DatasetReadmeDTO]("body").required
        .description("update dataset readme"))
  )

  put("/:id/readme", operation(updateReadme)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Unit] = for {
        datasetId <- paramT[String]("id")

        secureContainer <- getSecureContainer

        dataset <- secureContainer.datasetManager
          .getByNodeId(datasetId)
          .coreErrorToActionResult

        _ <- secureContainer
          .authorizeDataset(
            Set(
              DatasetPermission.ShowSettingsPage,
              DatasetPermission.EditDatasetDescription
            )
          )(dataset)
          .coreErrorToActionResult

        _ <- secureContainer.datasetManager
          .assertNotLocked(dataset)
          .coreErrorToActionResult

        oldReadmeAsset <- secureContainer.datasetAssetsManager
          .getReadme(dataset)
          .coreErrorToActionResult

        _ <- checkIfMatchTimestamp("readme", oldReadmeAsset.map(_.etag))

        oldReadme <- oldReadmeAsset
          .traverse(datasetAssetClient.downloadAsset(_))
          .leftMap[CoreError](e => ExceptionError(new Exception(e)))
          .toEitherT[Future]
          .coreErrorToActionResult

        body <- extractOrErrorT[DatasetReadmeDTO](parsedBody)

        _ <- secureContainer.datasetAssetsManager
          .createOrUpdateReadme(
            dataset,
            datasetAssetClient.bucket,
            "readme.md",
            asset =>
              datasetAssetClient.uploadAsset(
                asset,
                body.readme.getBytes("utf-8").length,
                Some("text/plain"),
                IOUtils.toInputStream(body.readme, "utf-8")
              )
          )
          .coreErrorToActionResult

        _ <- secureContainer.changelogManager
          .logEvent(
            dataset,
            ChangelogEventDetail.UpdateReadme(
              oldReadme = oldReadme,
              newReadme = Some(body.readme)
            )
          )
          .coreErrorToActionResult

        _ <- secureContainer.datasetManager
          .touchUpdatedAtTimestamp(dataset)
          .coreErrorToActionResult

        updatedDataset <- secureContainer.datasetManager
          .getByNodeId(datasetId)
          .coreErrorToActionResult

        // Refresh to get new ETag timestamp
        updatedReadme <- secureContainer.datasetAssetsManager
          .getReadme(updatedDataset)
          .coreErrorToActionResult

        _ <- setETagHeader(updatedReadme.map(_.etag))

      } yield ()

      val is = result.value.map(OkResult)
    }
  }

  val getReadme: OperationBuilder = (
    apiOperation[Unit]("getReadme")
      summary "get the README description for a dataset"
      parameters (pathParam[String]("id").required.description("dataset id"))
  )
  get("/:id/readme", operation(getReadme)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, DatasetReadmeDTO] = for {
        datasetId <- paramT[String]("id")

        secureContainer <- getSecureContainer

        dataset <- secureContainer.datasetManager
          .getByNodeId(datasetId)
          .coreErrorToActionResult

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ViewFiles))(dataset)
          .coreErrorToActionResult

        readmeAsset <- secureContainer.datasetAssetsManager
          .getReadme(dataset)
          .coreErrorToActionResult

        // If the readme has not been created, return the empty string
        readme <- readmeAsset
          .map(datasetAssetClient.downloadAsset(_))
          .getOrElse(Right(""))
          .leftMap[CoreError](e => ExceptionError(new Exception(e)))
          .toEitherT[Future]
          .coreErrorToActionResult

        _ <- setETagHeader(readmeAsset.map(_.etag))

      } yield DatasetReadmeDTO(readme)

      val is = result.value.map(OkResult)
    }
  }

  val getStatusLog: OperationBuilder = (
    apiOperation[PaginatedStatusLogEntries]("getStatusLog")
      summary "get the log of the status changes for the data set"
      parameters (pathParam[String]("id").description("data set id"),
      queryParam[Int]("limit").optional
        .description("max number of status change returned")
        .defaultValue(DatasetsDefaultLimit),
      queryParam[Int]("offset").optional
        .description("offset used for pagination of results")
        .defaultValue(DatasetsDefaultOffset))
  )

  get("/:id/status-log", operation(getStatusLog)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, PaginatedStatusLogEntries] =
        for {
          secureContainer <- getSecureContainer
          datasetId <- paramT[String]("id")
          dataset <- secureContainer.datasetManager
            .getByNodeId(datasetId)
            .orNotFound

          _ <- secureContainer
            .authorizeDataset(Set(DatasetPermission.ViewPeopleAndRoles))(
              dataset
            )
            .coreErrorToActionResult
          limit <- paramT[Int]("limit", default = DatasetsDefaultLimit)
          offset <- paramT[Int]("offset", default = DatasetsDefaultOffset)
          statusLogEntriesAndCount <- secureContainer.datasetManager
            .getStatusLog(dataset, limit, offset)
            .coreErrorToActionResult

          (statusLogEntries, logEntriesCount) = statusLogEntriesAndCount

        } yield
          PaginatedStatusLogEntries(
            limit,
            offset,
            logEntriesCount,
            entries = statusLogEntries.map {
              case (datasetStatusLogEntry, user) =>
                StatusLogEntryDTO(datasetStatusLogEntry, user)
            }.toList
          )

      override val is =
        result.value.map(OkResult(_))
    }
  }

  val getIgnoreFiles: OperationBuilder = (
    apiOperation[DatasetIgnoreFilesDTO]("getIgnoreFiles")
      summary "get a list of files to ignore when publishing for a dataset"
      parameters (pathParam[String]("id").required.description("dataset id"))
  )

  get("/:id/ignore-files", operation(getIgnoreFiles)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, DatasetIgnoreFilesDTO] =
        for {
          secureContainer <- getSecureContainer
          datasetId <- paramT[String]("id")
          dataset <- secureContainer.datasetManager
            .getByNodeId(datasetId)
            .coreErrorToActionResult

          _ <- secureContainer
            .authorizeDataset(Set(DatasetPermission.ViewFiles))(dataset)
            .coreErrorToActionResult

          ignoreFiles <- secureContainer.datasetManager
            .getIgnoreFiles(dataset)
            .coreErrorToActionResult
        } yield DatasetIgnoreFilesDTO(dataset.id, ignoreFiles)

      override val is = result.value.map(OkResult(_))
    }
  }

  val setIgnoreFiles: OperationBuilder = (
    apiOperation[DatasetIgnoreFileDTO]("setIgnoreFiles")
      summary "set the files to ignore when publishing for a dataset"
      parameters (
        pathParam[String]("id").description("dataset id"),
        bodyParam[Seq[DatasetIgnoreFileDTO]]("body").required
          .description(
            "List of file names, can be empty to clear current ignore filenames"
          )
    )
  )

  put("/:id/ignore-files", operation(setIgnoreFiles)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, DatasetIgnoreFilesDTO] =
        for {
          secureContainer <- getSecureContainer
          datasetId <- paramT[String]("id")
          dataset <- secureContainer.datasetManager
            .getByNodeId(datasetId)
            .coreErrorToActionResult
          ignoreFiles <- extractOrErrorT[Seq[DatasetIgnoreFileDTO]](parsedBody)
            .map(_.map(x => DatasetIgnoreFile(dataset.id, x.fileName)))

          _ <- secureContainer
            .authorizeDataset(
              Set(
                DatasetPermission.ShowSettingsPage,
                DatasetPermission.EditDatasetDescription
              )
            )(dataset)
            .coreErrorToActionResult

          updatedIgnoreFiles <- secureContainer.datasetManager
            .setIgnoreFiles(dataset, ignoreFiles)
            .coreErrorToActionResult

          _ <- secureContainer.changelogManager
            .logEvent(
              dataset,
              ChangelogEventDetail
                .UpdateIgnoreFiles(totalCount = updatedIgnoreFiles.length)
            )
            .coreErrorToActionResult

          _ <- secureContainer.datasetManager
            .touchUpdatedAtTimestamp(dataset)
            .coreErrorToActionResult

        } yield DatasetIgnoreFilesDTO(dataset.id, updatedIgnoreFiles)

      override val is = result.value.map(OkResult(_))
    }
  }

  val getOrganizationPublishedDatasets: OperationBuilder = (
    apiOperation[PaginatedPublishedDatasets]("getOrganizationPublishedDatasets")
      summary "get a paginated list of published datasets mapped from discover"
      parameters (
        queryParam[Int]("limit").optional
          .description("max number of datasets returned")
          .defaultValue(DatasetsDefaultLimit),
        queryParam[Int]("offset").optional
          .description("offset used for pagination of results")
          .defaultValue(DatasetsDefaultOffset),
        queryParam[String]("query").optional
          .description("parameter for the text search"),
        queryParam[String]("orderBy").optional
          .description(
            s"which data field to sort results by - values can be Name or UpdatedAt"
          )
          .allowableValues(AllowableOrderByValues)
          .defaultValue(DatasetManager.OrderByColumn.Name.entryName),
        queryParam[String]("orderDirection").optional
          .description(
            s"which direction to order the results by - value can be Desc or Asc"
          )
          .allowableValues(AllowableOrderByDirectionValues)
          .defaultValue(DatasetManager.OrderByDirection.Asc.entryName),
        queryParam[Boolean]("includeBannerUrl").optional
          .description(
            "If true, presigned banner image URLS will be returned with each dataset"
          )
          .defaultValue(false)
    )
  )
  get("/published/paginated", operation(getOrganizationPublishedDatasets)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, PaginatedPublishedDatasets] =
        for {
          secureContainer <- getSecureContainer
          organization = secureContainer.organization
          user = secureContainer.user
          storageServiceClient = secureContainer.storageManager
          traceId <- getTraceId(request)

          limit <- paramT[Int]("limit", default = DatasetsDefaultLimit)
          offset <- paramT[Int]("offset", default = DatasetsDefaultOffset)

          orderByDirection <- paramT[DatasetManager.OrderByDirection](
            "orderDirection",
            default = DatasetManager.OrderByDirection.Asc
          )

          orderBy <- paramT[DatasetManager.OrderByColumn](
            "orderBy",
            default = DatasetManager.OrderByColumn.Name
          )

          textSearch <- optParamT[String]("query")

          includeBannerUrl <- paramT[Boolean](
            "includeBannerUrl",
            default = false
          )

          // Search for published datasets in the organization. The user does
          // not need to have internal platform access to these.
          publishedDatasetsForOrganization <- DataSetPublishingHelper
            .getPublishedDatasetsForOrganization(
              searchClient,
              organization,
              Some(limit),
              Some(offset),
              (orderBy, orderByDirection),
              textSearch
            )
            .coreErrorToActionResult

          // Out of this list, get datasets that the user can access in the platform
          userDatasetsAndStatuses <- secureContainer.datasetManager
            .find(
              user = user,
              withRole = Role.Viewer,
              overrideRole = None,
              datasetIds = Some(
                publishedDatasetsForOrganization.datasets.toList
                  .flatMap(_.sourceDatasetId)
              )
            )
            .coreErrorToActionResult

          datasetDtos <- datasetDTOs(
            userDatasetsAndStatuses,
            includeBannerUrl = includeBannerUrl,
            includePublishedDataset = true
          )(
            asyncExecutor,
            secureContainer,
            storageServiceClient,
            datasetAssetClient,
            DataSetPublishingHelper.getPublishedDatasetsFromDiscover(
              publishClient
            ),
            system,
            jwtConfig
          ).map(_.map(dto => (dto.content.intId, dto)).toMap).orError

          // Aggregate all requested and granted embargo preview requests for
          // this user to merge with the list of published datasets.
          embargoPreviewAccess <- secureContainer.datasetPreviewManager
            .forUser(secureContainer.user)
            .map(_.map(p => (p.datasetId, p.embargoAccess)).toMap)
            .orError

          datasetsAndPublishedDatasets = publishedDatasetsForOrganization.datasets
            .map(publishedDataset => {
              DatasetAndPublishedDataset(
                publishedDataset.sourceDatasetId
                  .flatMap(datasetDtos.get(_)),
                publishedDataset,
                publishedDataset.sourceDatasetId
                  .flatMap(embargoPreviewAccess.get(_))
              )
            })
            .toList

          _ <- auditLogger
            .message()
            .append(
              "description",
              "Published dataset search results (paginated)"
            )
            .append("organization-id", organization.id)
            .append("organization-node-id", organization.nodeId)
            .append(
              "dataset-ids",
              datasetDtos.map(_._2.content.intId).toSeq: _*
            )
            .append(
              "dataset-node-ids",
              datasetDtos.map(_._2.content.id).toSeq: _*
            )
            .log(traceId)
            .toEitherT
            .coreErrorToActionResult
        } yield
          PaginatedPublishedDatasets(
            limit,
            offset,
            publishedDatasetsForOrganization.totalCount,
            datasetsAndPublishedDatasets
          )

      override val is = result.value.map(OkResult(_))
    }
  }

  val getChangelog: OperationBuilder = (
    apiOperation[ChangelogPage]("getChangelog")
      .summary("get dataset changelog")
      .parameters(
        pathParam[String]("id").description("data set id"),
        queryParam[Int]("limit").optional
          .description("max number event groups")
          .defaultValue(DatasetsDefaultLimit),
        queryParam[String]("cursor").optional
          .description("cursor to next page of event groups"),
        queryParam[ChangelogEventCategory]("category").optional
          .description("filter events by category")
          .allowableValues(ChangelogEventCategory.values.map(_.entryName)),
        queryParam[LocalDate]("startDate").optional
          .description("oldest event to return in the timeline"),
        queryParam[Int]("userId").optional
          .description("filter events by user")
      )
    )

  get("/:id/changelog/timeline", operation(getChangelog)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, ChangelogPage] =
        for {
          secureContainer <- getSecureContainer

          datasetId <- paramT[String]("id")
          limit <- paramT[Int]("limit", default = DatasetsDefaultLimit)
          cursor <- optParamT[ChangelogEventGroupCursor]("cursor")
          category <- optParamT[ChangelogEventCategory]("category")
          startDate <- optParamT[LocalDate]("startDate")
          userId <- optParamT[Int]("userId")

          dataset <- secureContainer.datasetManager
            .getByNodeId(datasetId)
            .orNotFound

          _ <- secureContainer
            .authorizeDataset(Set(DatasetPermission.ViewFiles))(dataset)
            .coreErrorToActionResult

          eventGroupsAndCursor <- secureContainer.changelogManager
            .getTimeline(
              dataset,
              limit = limit,
              cursor = cursor,
              category = category,
              startDate = startDate,
              userId = userId
            )
            .coreErrorToActionResult

          (eventGroups, cursor) = eventGroupsAndCursor

        } yield
          ChangelogPage(
            cursor.map(ChangelogEventGroupCursor.encodeBase64(_)),
            eventGroups.map(ChangelogEventGroupDTO.from _ tupled)
          )

      override val is = result.value.map(OkResult(_))
    }
  }

  implicit val timelineCursorParam: Param[ChangelogEventGroupCursor] =
    Param[String]
      .map(SerializedCursor(_))
      .emap(
        s =>
          ChangelogEventGroupCursor
            .decodeBase64(s)
            .leftMap(_ => BadRequest(Error("Invalid cursor")))
      )

  implicit val eventCursorParam: Param[ChangelogEventCursor] =
    Param[String]
      .map(SerializedCursor(_))
      .emap(
        s =>
          ChangelogEventCursor
            .decodeBase64(s)
            .leftMap(_ => BadRequest(Error("Invalid cursor")))
      )

  implicit val eventCategoryParam: Param[ChangelogEventCategory] =
    Param.enumParam(ChangelogEventCategory)

  val getChangelogEvents: OperationBuilder = (
    apiOperation[ChangelogEventPage]("getChangelogEvents")
      .summary("get events from changelog")
      .parameters(
        pathParam[String]("id").description("data set id"),
        queryParam[Int]("limit").optional
          .description("max number of events")
          .defaultValue(DatasetsDefaultLimit),
        queryParam[String]("cursor").required
          .description("cursor to next page of events")
      )
    )

  get("/:id/changelog/events", operation(getChangelogEvents)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, ChangelogEventPage] =
        for {
          secureContainer <- getSecureContainer

          datasetId <- paramT[String]("id")
          limit <- paramT[Int]("limit", default = DatasetsDefaultLimit)
          cursor <- paramT[ChangelogEventCursor]("cursor")

          dataset <- secureContainer.datasetManager
            .getByNodeId(datasetId)
            .orNotFound

          _ <- secureContainer
            .authorizeDataset(Set(DatasetPermission.ViewFiles))(dataset)
            .coreErrorToActionResult

          eventsAndCursor <- secureContainer.changelogManager
            .getEvents(dataset, limit = limit, cursor = cursor)
            .coreErrorToActionResult

          (events, cursor) = eventsAndCursor

        } yield
          ChangelogEventPage(
            cursor.map(ChangelogEventCursor.encodeBase64(_)),
            events
          )

      override val is = result.value.map(OkResult(_))
    }
  }

}
