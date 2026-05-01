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

package com.pennsieve.helpers.fakes

import cats.data.EitherT
import com.pennsieve.db.AllDataCanvasesViewMapper
import com.pennsieve.domain.{ CoreError, NotFound }
import com.pennsieve.managers.AllDataCanvasesViewManager
import com.pennsieve.models.{ DataCanvas, User }
import com.pennsieve.traits.PostgresProfile.api.Database

import scala.concurrent.{ ExecutionContext, Future }

class FakeAllDataCanvasesViewManager(
  state: InMemoryState,
  val actor: User,
  val allDataCanvasesViewMapper: AllDataCanvasesViewMapper
) extends AllDataCanvasesViewManager {

  def db: Database =
    sys.error(
      "FakeAllDataCanvasesViewManager: a method not yet stubbed by your test " +
        "tried to use the database. Override the method on this fake."
    )

  override def get(
    nodeId: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Int, DataCanvas)] = {
    val match0 = state.dataCanvases
      .collectFirst { case ((orgId, _), c) if c.nodeId == nodeId => orgId -> c }
    match0 match {
      case Some(pair) => EitherT.rightT(pair)
      case None => EitherT.leftT(NotFound(nodeId): CoreError)
    }
  }

  override def getForOrganization(
    orgId: Int,
    isPublic: Boolean = false
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[(Int, DataCanvas)]] =
    EitherT.rightT(state.dataCanvases.collect {
      case ((o, _), c) if o == orgId && c.isPublic == isPublic =>
        o -> c
    }.toSeq)
}
