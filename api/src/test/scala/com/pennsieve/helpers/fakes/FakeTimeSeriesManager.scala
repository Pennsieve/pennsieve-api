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
import com.pennsieve.domain.CoreError
import com.pennsieve.managers.TimeSeriesManager
import com.pennsieve.models.{
  Channel,
  ModelProperty,
  NodeCodes,
  Organization,
  Package
}
import com.pennsieve.traits.PostgresProfile.api.Database

import scala.concurrent.{ ExecutionContext, Future }

/** In-memory fake of `TimeSeriesManager`. Channel-bearing tests can override
  * `getChannels`; default returns empty list. */
class FakeTimeSeriesManager(
  state: InMemoryState,
  org: Organization,
  phantomDb: Database
) extends TimeSeriesManager(phantomDb, org) {

  override def getChannels(
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[Channel]] =
    EitherT.rightT(
      state.channels
        .collect {
          case ((orgId, _), c)
              if orgId == org.id && c.packageId == `package`.id =>
            c
        }
        .toList
        .sortBy(_.id)
    )

  override def createChannel(
    `package`: Package,
    name: String,
    start: Long,
    end: Long,
    unit: String,
    rate: Double,
    `type`: String,
    group: Option[String],
    lastAnnotation: Long,
    spikeDuration: Option[Long] = None,
    properties: List[ModelProperty] = Nil
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Channel] = {
    val id = state.newId()
    val ch = Channel(
      nodeId = NodeCodes.generateId(NodeCodes.channelCode),
      packageId = `package`.id,
      name = name.trim,
      start = start,
      end = end,
      unit = unit,
      rate = rate,
      `type` = `type`,
      group = group,
      lastAnnotation = lastAnnotation,
      spikeDuration = spikeDuration,
      properties = properties,
      id = id
    )
    state.channels.put((org.id, id), ch)
    EitherT.rightT(ch)
  }
}
