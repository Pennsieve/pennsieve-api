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
import com.pennsieve.domain.{ CoreError, NotFound, PredicateError }
import com.pennsieve.managers.TimeSeriesManager
import com.pennsieve.models.{
  Channel,
  ModelProperty,
  NodeCodes,
  Organization,
  Package
}
import com.pennsieve.traits.PostgresProfile.api.Database

import scala.collection.SortedSet
import scala.concurrent.{ ExecutionContext, Future }

/** In-memory fake of `TimeSeriesManager`. Reads/writes `state.channels`. */
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
    EitherT.rightT(channelsForPackage(`package`.id))

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
    // Match Postgres-side timestamps without named zones — JSON encode
    // round-trip drops the zone, so equality checks would fail otherwise.
    val now = InMemoryState.now()
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
      createdAt = now,
      id = id
    )
    state.channels.put((org.id, id), ch)
    EitherT.rightT(ch)
  }

  override def updateChannel(
    channel: Channel,
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Channel] =
    state.channels.get((org.id, channel.id)) match {
      case Some(existing) if existing.packageId == `package`.id =>
        state.channels.put((org.id, channel.id), channel)
        EitherT.rightT(channel)
      case _ =>
        EitherT.leftT(NotFound(s"Channel (${channel.id})"): CoreError)
    }

  override def updateChannels(
    channels: List[Channel],
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[Channel]] = {
    channels.foreach(c => state.channels.put((org.id, c.id), c))
    EitherT.rightT(channels)
  }

  override def deleteChannel(
    channel: Channel,
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] = {
    state.channels.remove((org.id, channel.id))
    EitherT.rightT(1)
  }

  override def getChannel(
    id: Int,
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Channel] =
    state.channels.get((org.id, id)) match {
      case Some(c) if c.packageId == `package`.id => EitherT.rightT(c)
      case _ =>
        EitherT.leftT(
          NotFound(s"Channel ($id) in Package ${`package`.id}"): CoreError
        )
    }

  override def getChannelByNodeId(
    nodeId: String,
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Channel] =
    state.channels.values
      .find(c => c.nodeId == nodeId && c.packageId == `package`.id) match {
      case Some(c) => EitherT.rightT(c)
      case None => EitherT.leftT(NotFound(nodeId): CoreError)
    }

  override def getChannelsByNodeIds(
    `package`: Package,
    channelIds: SortedSet[String]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[Channel]] = {
    val found = state.channels.values
      .filter(c => c.packageId == `package`.id && channelIds.contains(c.nodeId))
      .toList
      .sortBy(_.id)
    val missing = channelIds.diff(found.map(_.nodeId).toSet)
    if (missing.nonEmpty)
      EitherT.leftT(
        PredicateError(
          s"Given channels are not part of this TimeSeries: ${missing.take(5).mkString("\n", "\n", "\n")}..."
        ): CoreError
      )
    else EitherT.rightT(found)
  }

  private def channelsForPackage(packageId: Int): List[Channel] =
    state.channels
      .collect {
        case ((orgId, _), c) if orgId == org.id && c.packageId == packageId => c
      }
      .toList
      .sortBy(_.id)
}
