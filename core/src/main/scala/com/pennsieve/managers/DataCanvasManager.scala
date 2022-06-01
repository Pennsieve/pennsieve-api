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

package com.pennsieve.managers

import cats.data._
import cats.implicits._
import com.pennsieve.core.utilities.{ checkOrErrorT, FutureEitherHelpers }
import com.pennsieve.db.{ DataCanvasMapper, DataCanvasPackageMapper }
import com.pennsieve.domain.{
  CoreError,
  NotFound,
  PredicateError,
  ServiceError,
  SqlError
}
import com.pennsieve.models.{
  DataCanvas,
  DataCanvasPackage,
  NodeCodes,
  Organization,
  User
}
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits.{
  FutureEitherT,
  _
}
import com.pennsieve.messages.BackgroundJob
import org.postgresql.util.PSQLException

import scala.concurrent.{ ExecutionContext, Future }
import slick.dbio.DBIO

object DataCanvasManager {
  val maxNameLength = 256
}

class DataCanvasManager(
  val db: Database,
  val actor: User,
  val datacanvasMapper: DataCanvasMapper
) {
  import DataCanvasManager._

  val organization: Organization = datacanvasMapper.organization

  val dataCanvasPackageMapper = new DataCanvasPackageMapper(organization)

  val datasetStatusManager: DatasetStatusManager =
    new DatasetStatusManager(db, organization)

  def isLocked(
    dataCanvas: DataCanvas
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Boolean] =
    db.run(datacanvasMapper.isLocked(dataCanvas, actor)).toEitherT

  def create(
    name: String,
    description: String,
    role: Option[String] = None,
    statusId: Option[Int] = None,
    permissionBit: Option[Int] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DataCanvas] = {
    val nodeId = NodeCodes.generateId(NodeCodes.dataCanvasCode)

    for {
      _ <- FutureEitherHelpers.assert(name.trim.nonEmpty)(
        PredicateError("data-canvas name must not be empty")
      )

      nameExists <- nameExists(name.trim).toEitherT
      _ <- FutureEitherHelpers.assert(!nameExists)(
        PredicateError("data-canvas name must be unique")
      )

      _ <- checkOrErrorT(name.trim.length < maxNameLength)(
        PredicateError(
          s"dataset name must be less than ${maxNameLength} characters"
        )
      )

      createdDataCanvas = for {
        dataCanvasStatus <- statusId
          .map(datasetStatusManager.getById(_))
          .getOrElse(datasetStatusManager.getDefaultStatus)

        dataCanvas <- (datacanvasMapper returning datacanvasMapper) += DataCanvas(
          nodeId = nodeId,
          name = name,
          description = description,
          role = role,
          statusId = dataCanvasStatus.id,
          permissionBit = permissionBit.getOrElse(0)
        )
      } yield dataCanvas

      dataCanvas <- db.run(createdDataCanvas.transactionally).toEitherT

      // TODO: link datacanvas_user

    } yield dataCanvas

  }

  def getAll(
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[DataCanvas]] =
    db.run(datacanvasMapper.result).toEitherT

  def getById(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DataCanvas] =
    db.run(
        datacanvasMapper
          .filter(_.id === id)
          .result
          .headOption
      )
      .whenNone(NotFound(id.toString))

  def update(
    dataCanvas: DataCanvas,
    checkforDuplicateNames: Boolean = true
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DataCanvas] = {
    for {
      _ <- FutureEitherHelpers.assert(dataCanvas.name.trim.nonEmpty)(
        PredicateError("data-canvas name must not be empty")
      )

      currentDataCanvas <- db
        .run(
          datacanvasMapper
            .filter(_.name === dataCanvas.name)
            .result
            .headOption
        )
        .map(_.getOrElse(dataCanvas))
        .toEitherT

      _ <- FutureEitherHelpers.assert(
        (currentDataCanvas.id == dataCanvas.id) || !checkforDuplicateNames
      )(ServiceError(s"name is already taken: ${dataCanvas.name}"))

      query = for {
        _ <- datacanvasMapper
          .filter(_.id === dataCanvas.id)
          .update(dataCanvas)
      } yield ()

      _ <- db
        .run(query.transactionally)
        .toEitherT[CoreError] {
          case e: PSQLException
              if (e.getMessage() == "ERROR: value too long for type character varying(255)") =>
            PredicateError(
              s"data-canvas name must be less than ${maxNameLength} characters"
            ): CoreError
        }

      updatedDataCanvas <- getById(dataCanvas.id)

    } yield updatedDataCanvas
  }

  def delete(
    dataCanvas: DataCanvas
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Boolean] = {
    for {
      _ <- FutureEitherHelpers.assert(dataCanvas.name.trim.nonEmpty)(
        PredicateError("dataset name must not be empty")
      )
      query = for {
        _ <- datacanvasMapper
          .filter(_.id === dataCanvas.id)
          .delete
      } yield ()

      _ <- db
        .run(query.transactionally)
        .toEitherT[CoreError] {
          case e: PSQLException =>
            SqlError(e.getMessage()): CoreError
        }

    } yield true
  }

  def attachPackage(
    dataCanvasId: Int,
    datasetId: Int,
    packageId: Int,
    organizationId: Int = organization.id
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DataCanvasPackage] = {
    for {
      _ <- getById(dataCanvasId)

      attachedDataCanvasPackage = for {
        dataCanvasPackage <- (dataCanvasPackageMapper returning dataCanvasPackageMapper) += DataCanvasPackage(
          dataCanvasId = dataCanvasId,
          datasetId = datasetId,
          packageId = packageId,
          organizationId = organizationId
        )
      } yield dataCanvasPackage

      dataCanvasPackage <- db
        .run(attachedDataCanvasPackage.transactionally)
        .toEitherT

    } yield dataCanvasPackage
  }

  def nameExists(name: String): Future[Boolean] =
    db.run(datacanvasMapper.nameExists(name))

}
