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

import cats.data.EitherT
import com.pennsieve.db.DataCanvasMapper
import com.pennsieve.domain.{ CoreError, NotFound }
import com.pennsieve.models.{ DataCanvas, User }
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._

import scala.concurrent.{ ExecutionContext, Future }

object DataCanvasManager {}

class DataCanvasManager(
  val db: Database,
  val actor: User,
  val datacanvasMapper: DataCanvasMapper
) {
  import DataCanvasManager._

  def isLocked(
    dataCanvas: DataCanvas
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Boolean] =
    db.run(datacanvasMapper.isLocked(dataCanvas, actor)).toEitherT

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
}
