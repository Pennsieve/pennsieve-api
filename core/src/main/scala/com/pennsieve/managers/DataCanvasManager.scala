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
import com.pennsieve.db.DataCanvasMapper
import com.pennsieve.domain.{ CoreError, NotFound, PredicateError }
import com.pennsieve.models.{ DataCanvas, NodeCodes, Organization, User }
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._

import scala.concurrent.{ ExecutionContext, Future }
import slick.dbio.DBIO

object DataCanvasManager {}

class DataCanvasManager(
  val db: Database,
  val actor: User,
  val datacanvasMapper: DataCanvasMapper
) {
  import DataCanvasManager._

  val organization: Organization = datacanvasMapper.organization

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

      _ <- checkOrErrorT(name.trim.length < 256)(
        PredicateError("dataset name must be less than 255 characters")
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

  def nameExists(name: String): Future[Boolean] =
    db.run(datacanvasMapper.nameExists(name))

}
