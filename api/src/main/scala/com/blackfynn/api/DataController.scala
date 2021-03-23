// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.api

import akka.stream.Materializer
import cats.data.EitherT
import cats.implicits._
import com.pennsieve.audit.middleware.{ Auditor, TraceId }
import com.pennsieve.auth.middleware.DatasetPermission
import com.pennsieve.aws.queue.SQSClient
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.core.utilities.checkOrErrorT
import com.pennsieve.domain.StorageAggregation.spackages
import com.pennsieve.domain.{ CoreError, NameCheckError }
import com.pennsieve.dtos.Builders.packageDTO
import com.pennsieve.dtos.{ ModelPropertyRO, PackageDTO }
import com.pennsieve.helpers.APIContainers.{
  InsecureAPIContainer,
  SecureAPIContainer,
  SecureContainerBuilderType
}
import com.pennsieve.helpers.ResultHandlers.OkResult
import com.pennsieve.helpers.either.EitherTErrorHandler.implicits._
import com.pennsieve.models.PackageState.DELETING
import com.pennsieve.models.{
  ChangelogEventDetail,
  ModelProperty,
  Package,
  User
}
import io.circe.syntax._
import org.json4s._
import org.scalatra.swagger._
import org.scalatra.{
  ActionResult,
  AsyncResult,
  FutureSupport,
  NotFound,
  ScalatraServlet
}

import scala.concurrent.{ ExecutionContext, Future }

sealed trait BaseFailure {
  def id: String
  def error: String
}

case class MoveRequest(things: List[String], destination: Option[String])

case class DeleteRequest(things: List[String])

case class UpdatePropertiesRequest(properties: List[ModelPropertyRO])

case class Failure(id: String, error: String) extends BaseFailure

case class MoveFailure(
  id: String,
  error: String,
  name: String,
  generatedName: String
) extends BaseFailure

case class MoveResponse(
  success: List[String],
  failures: List[BaseFailure],
  destination: Option[String]
)

case class DeleteResponse(success: List[String], failures: List[BaseFailure])

class DataController(
  val insecureContainer: InsecureAPIContainer,
  val secureContainerBuilder: SecureContainerBuilderType,
  materializer: Materializer,
  asyncExecutor: ExecutionContext,
  auditLogger: Auditor,
  sqsClient: SQSClient
)(implicit
  val swagger: Swagger
) extends ScalatraServlet
    with AuthenticatedController {

  override protected implicit def executor: ExecutionContext = asyncExecutor

  override val swaggerTag = "Data"

  val moveOperation = (apiOperation[MoveResponse]("moveItems")
    summary "moves files or packages into a destination package"
    parameters (
      bodyParam[MoveRequest]("moveRequest").description(
        "list of ids of files and packages to move, as well as their destination"
      )
    ))

  def makeFailure(id: String, error: String): BaseFailure = Failure(id, error)
  def makeMoveFailure(
    id: String,
    name: String,
    error: CoreError
  ): BaseFailure = {
    error match {
      case NameCheckError(recommendation, message) =>
        MoveFailure(id, message, name, recommendation)
      case other => Failure(id, error.getMessage)
    }

  }

  def moveItems(
    traceId: TraceId,
    items: List[String],
    destination: Option[Package],
    user: User
  )(implicit
    container: SecureAPIContainer
  ): EitherT[Future, ActionResult, (List[BaseFailure], List[String])] = {
    def moveItem(
      traceId: TraceId,
      item: String,
      destination: Option[Package]
    ): EitherT[Future, BaseFailure, String] = {
      for {
        result <- container.packageManager
          .getPackageAndDatasetByNodeId(item)
          .leftMap(ge => makeFailure(item, "cannot retrieve package"))
        (pkg, dataset) = result

        parent <- container.packageManager
          .getParent(pkg)
          .leftMap(ge => makeFailure(item, "cannot retrieve parent package"))

        _ <- container
          .authorizeDataset(Set(DatasetPermission.EditFiles))(dataset)
          .leftMap(ge => makeFailure(item, "no permissions on dataset"))

        _ <- auditLogger
          .message()
          .append("dataset-id", dataset.id)
          .append("dataset-node-id", dataset.nodeId)
          .append("src-package-id", pkg.id)
          .append("src-package-node-id", pkg.nodeId)
          .append(
            "dest-package-id",
            destination.map(_.id.toString).getOrElse("")
          )
          .append(
            "dest-package-node-id",
            destination.map(_.nodeId).getOrElse("")
          )
          .log(traceId)
          .toEitherT
          .leftMap(error => Failure(item, error.getMessage))

        destinationNodeId = destination.map(_.nodeId)
        destinationId = destination.map(_.id)

        _ <- checkOrErrorT(Some(pkg.nodeId) != destinationNodeId)(
          makeFailure(item, "cannot move object into itself")
        )

        _ <- checkOrErrorT(pkg.parentId != destinationId)(
          makeFailure(item, "cannot move object where it already is")
        )

        descendants <- EitherT.liftF(container.packageManager.descendants(pkg))

        _ <- checkOrErrorT(!descendants.contains(destinationId.getOrElse(-1)))(
          makeFailure(item, "cannot move object into one of its descendants")
        )

        _ <- container.packageManager
          .checkName(
            pkg.name,
            parent = destination,
            dataset = dataset,
            pkg.`type`
          )
          .leftMap(e => makeMoveFailure(item, pkg.name, e))

        storageMap <- container.storageManager
          .getStorage(spackages, List(pkg.id))
          .leftMap(ge => makeFailure(item, ge.getMessage))
        storage = storageMap.get(pkg.id).flatten.getOrElse(0L)

        // Decrement storage of ancestors before moving
        _ <- container.storageManager
          .incrementStorage(spackages, -storage, pkg.id)
          .leftMap(ge => makeFailure(item, ge.getMessage))

        update <- container.packageManager
          .update(pkg.copy(parentId = destinationId))
          .leftMap(ge => makeFailure(item, ge.getMessage))

        // Then increment the storage of the new ancestors
        _ <- container.storageManager
          .incrementStorage(spackages, storage, pkg.id)
          .leftMap(ge => makeFailure(item, ge.getMessage))

        _ <- container.changelogManager
          .logEvent(
            dataset,
            ChangelogEventDetail.MovePackage(
              pkg = pkg,
              oldParent = parent,
              newParent = destination
            )
          )
          .leftMap(ge => makeFailure(item, ge.getMessage))

        _ <- container.datasetManager
          .touchUpdatedAtTimestamp(dataset)
          .leftMap(ge => makeFailure(item, ge.getMessage))

      } yield update.nodeId
    }

    val failAndSuccess: Future[(List[BaseFailure], List[String])] =
      Future
        .sequence(items.map(moveItem(traceId, _, destination).value))
        .map(_.separate)

    EitherT.liftF(failAndSuccess): EitherT[
      Future,
      ActionResult,
      (List[BaseFailure], List[String])
    ]
  }

  post("/move", operation(moveOperation)) {
    val moveRequest = parsedBody.extract[MoveRequest]

    new AsyncResult {
      val moveResponse = for {
        secureContainer <- getSecureContainer
        traceId <- getTraceId(request)
        user = secureContainer.user
        destPackageAndDataset <- moveRequest.destination
          .traverse(
            destId =>
              secureContainer.packageManager
                .getPackageAndDatasetByNodeId(destId)
          )
          .orNotFound

        _ <- destPackageAndDataset.traverse {
          case (pkg, dataset) => {
            for {
              _ <- secureContainer.authorizeDataset(
                Set(DatasetPermission.EditFiles)
              )(dataset)

              _ <- auditLogger
                .message()
                .append("move-target", moveRequest.things: _*)
                .append("dataset-id", dataset.id)
                .append("dataset-node-id", dataset.nodeId)
                .append("package-id", pkg.id)
                .append("package-node-id", pkg.nodeId)
                .log(traceId)
                .toEitherT
            } yield (pkg, dataset)
          }
        }.coreErrorToActionResult

        _ <- destPackageAndDataset.traverse {
          case (_, dataset) =>
            secureContainer.datasetManager.assertNotLocked(dataset)
        }.coreErrorToActionResult

        results <- moveItems(
          traceId,
          moveRequest.things,
          destPackageAndDataset.map(_._1),
          user
        )(secureContainer)

        _ <- destPackageAndDataset.traverse {
          case (_, dataset) =>
            secureContainer.datasetManager
              .touchUpdatedAtTimestamp(dataset)
        }.coreErrorToActionResult

      } yield {
        MoveResponse(results._2, results._1, moveRequest.destination)
      }

      val is = moveResponse.value.map(OkResult)
    }
  }

  def deleteItems(
    traceId: TraceId,
    items: Seq[String],
    user: User
  )(implicit
    secureContainer: SecureAPIContainer
  ): List[EitherT[Future, Failure, String]] = {
    def deleteItem(item: String): EitherT[Future, Failure, String] = {
      for {
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(item)
          .leftMap(error => Failure(item, error.getMessage))
        (pkg, dataset) = packageAndDataset

        parent <- secureContainer.packageManager
          .getParent(pkg)
          .leftMap(error => Failure(item, error.getMessage))

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.CreateDeleteFiles))(dataset)
          .leftMap(error => Failure(item, error.getMessage))

        _ <- secureContainer.datasetManager
          .assertNotLocked(dataset)
          .leftMap(error => Failure(item, error.getMessage))

        _ <- auditLogger
          .message()
          .append("move-target", item)
          .append("dataset-id", dataset.id)
          .append("dataset-node-id", dataset.nodeId)
          .append("package-id", pkg.id)
          .append("package-node-id", pkg.nodeId)
          .log(traceId)
          .toEitherT
          .leftMap(error => Failure(item, error.getMessage))

        deleteMessage <- secureContainer.packageManager
          .delete(traceId, pkg)(secureContainer.storageManager)
          .leftMap(error => Failure(item, error.getMessage))

        _ <- secureContainer.changelogManager
          .logEvent(dataset, ChangelogEventDetail.DeletePackage(pkg, parent))
          .leftMap(error => Failure(item, error.getMessage))

        _ <- secureContainer.datasetManager
          .touchUpdatedAtTimestamp(dataset)
          .leftMap(error => Failure(item, error.getMessage))

        _ <- sqsClient
          .send(insecureContainer.sqs_queue, deleteMessage.asJson.noSpaces)
          .leftMap(error => Failure(item, error.getMessage))

      } yield item
    }

    items.toList.map(deleteItem)
  }

  val deleteOperation = (apiOperation[DeleteResponse]("deleteItems")
    summary "deletes items"
    parameter bodyParam[DeleteRequest]("itemIds")
      .description("items to delete"))

  post("/delete", operation(deleteOperation)) {
    val deleteRequest = parsedBody.extract[DeleteRequest]

    new AsyncResult {
      val result: EitherT[Future, ActionResult, DeleteResponse] = for {
        secureContainer <- getSecureContainer
        traceId <- getTraceId(request)
        user = secureContainer.user

        results = deleteItems(traceId, deleteRequest.things, user)(
          secureContainer
        )
        failsAndSuccesses <- EitherT.liftF(
          Future.sequence(results.map(_.value))
        )

      } yield {
        val (fails, successes) = failsAndSuccesses.separate

        DeleteResponse(successes, fails)
      }

      override val is = result.value.map(OkResult)
    }
  }

  val updatePropertiesOperation = (apiOperation[Option[PackageDTO]](
    "updateProperties"
  )
    summary "updates the properties on a node"
    parameters (
      pathParam[String]("id")
        .description("identifies the object you want to add properties for"),
      bodyParam[UpdatePropertiesRequest]("body").description("the properties")
  ))

  put("/:id/properties", operation(updatePropertiesOperation)) {
    val propertiesRequest = parsedBody.extract[UpdatePropertiesRequest]
    val updatedProps =
      ModelPropertyRO.fromRequestObject(propertiesRequest.properties)

    new AsyncResult {
      val result = for {
        itemId <- paramT[String]("id")
        secureContainer <- getSecureContainer
        traceId <- getTraceId(request)
        result <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(itemId)
          .orForbidden
        (pkg, dataset) = result

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.ManageGraphSchema))(dataset)
          .coreErrorToActionResult

        _ <- secureContainer.datasetManager
          .assertNotLocked(dataset)
          .coreErrorToActionResult
        _ <- checkOrErrorT(pkg.state != DELETING)(
          NotFound(s"$itemId not found")
        )
        newProperties = ModelProperty.merge(pkg.attributes, updatedProps)
        _ <- secureContainer.packageManager
          .update(pkg.copy(attributes = newProperties))
          .orError

        _ <- secureContainer.datasetManager
          .touchUpdatedAtTimestamp(dataset)
          .coreErrorToActionResult

        _ <- auditLogger
          .message()
          .append("target", itemId)
          .append("dataset-id", dataset.id)
          .append("dataset-node-id", dataset.nodeId)
          .append("package-id", pkg.id)
          .append("package-node-id", pkg.nodeId)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult

      } yield
        packageDTO(pkg, dataset, false, false)(asyncExecutor, secureContainer)

      override val is = result.value.map(OkResult)
    }
  }
}
