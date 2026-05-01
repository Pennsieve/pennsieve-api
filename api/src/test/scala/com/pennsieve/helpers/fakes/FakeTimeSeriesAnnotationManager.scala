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

import akka.NotUsed
import akka.stream.scaladsl.Source
import cats.data.EitherT
import com.github.tminglei.slickpg.Range
import com.pennsieve.db.{
  ChannelGroup,
  DBTimeSeriesAnnotation,
  TimeSeriesAnnotation
}
import com.pennsieve.domain.CoreError
import com.pennsieve.managers.{ TimeSeriesAnnotationManager, TimeSeriesManager }
import com.pennsieve.models.Package
import com.pennsieve.timeseries.{
  AnnotationAggregateWindowResult,
  AnnotationData,
  WindowAggregator
}
import com.pennsieve.traits.PostgresProfile.api.Database

import scala.collection.SortedSet
import scala.concurrent.{ ExecutionContext, Future }

/** In-memory fake of `TimeSeriesAnnotationManager`. Annotations live in a
  * `(id -> DBTimeSeriesAnnotation)` map; channel groups are derived
  * on-the-fly from a separate `(channelGroupId -> SortedSet[String])` map. */
class FakeTimeSeriesAnnotationManager(state: InMemoryState, phantomDb: Database)
    extends TimeSeriesAnnotationManager(phantomDb) {

  // Helper to materialize a TimeSeriesAnnotation from our state.
  private def toAnnotation(db: DBTimeSeriesAnnotation): TimeSeriesAnnotation = {
    val cg = state.timeSeriesChannelGroups
      .getOrElse(db.channelGroupId, SortedSet.empty[String])
    db.toTimeSeriesAnnotation(ChannelGroup(db.channelGroupId, cg))
  }

  /** Returns a stable channel-group id for the given set: identity-mapped
    * by the set itself. */
  private def channelGroupIdFor(channels: SortedSet[String]): Int = {
    state.timeSeriesChannelGroups.find {
      case (_, set) => set == channels
    } match {
      case Some((id, _)) => id
      case None =>
        val id = state.newId()
        state.timeSeriesChannelGroups.put(id, channels)
        id
    }
  }

  override def create(
    `package`: Package,
    layerId: Int,
    name: String,
    label: String,
    description: Option[String],
    userNodeId: String,
    range: Range[Long],
    channelIds: SortedSet[String],
    data: Option[AnnotationData],
    linkedPackage: Option[String] = None
  )(
    timeSeriesManager: TimeSeriesManager
  )(implicit
    executionContext: ExecutionContext
  ): EitherT[Future, CoreError, TimeSeriesAnnotation] =
    for {
      _ <- timeSeriesManager.getChannelsByNodeIds(`package`, channelIds)
    } yield {
      val cgId = channelGroupIdFor(channelIds)
      val id = state.newId()
      val db = DBTimeSeriesAnnotation(
        id = id,
        timeSeriesId = `package`.nodeId,
        channelGroupId = cgId,
        layerId = layerId,
        name = name,
        label = label,
        description = description,
        userNodeId = Some(userNodeId),
        range = range,
        data = data,
        linkedPackage = linkedPackage
      )
      state.timeSeriesAnnotations.put(id, db)
      toAnnotation(db)
    }

  override def update(
    `package`: Package,
    annotation: TimeSeriesAnnotation
  )(
    timeSeriesManager: TimeSeriesManager
  )(implicit
    executionContext: ExecutionContext
  ): EitherT[Future, CoreError, TimeSeriesAnnotation] =
    for {
      _ <- timeSeriesManager.getChannelsByNodeIds(
        `package`,
        annotation.channelIds
      )
    } yield {
      val cgId = channelGroupIdFor(annotation.channelIds)
      val db = annotation.toDBTimeSeriesAnnotation(
        ChannelGroup(cgId, annotation.channelIds)
      )
      state.timeSeriesAnnotations.put(annotation.id, db)
      annotation
    }

  override def delete(
    id: Int
  )(implicit
    executionContext: ExecutionContext
  ): Future[Unit] = {
    state.timeSeriesAnnotations.remove(id)
    Future.successful(())
  }

  override def delete(timeSeriesId: String): Future[Int] = {
    val toRemove = state.timeSeriesAnnotations.values
      .filter(_.timeSeriesId == timeSeriesId)
      .map(_.id)
      .toList
    toRemove.foreach(state.timeSeriesAnnotations.remove)
    Future.successful(toRemove.size)
  }

  override def delete(
    timeSeriesId: String,
    layerId: Int,
    ids: Set[Int]
  ): Future[Int] = {
    val toRemove = state.timeSeriesAnnotations.values
      .filter(
        a =>
          a.timeSeriesId == timeSeriesId &&
            a.layerId == layerId &&
            ids.contains(a.id)
      )
      .map(_.id)
      .toList
    toRemove.foreach(state.timeSeriesAnnotations.remove)
    Future.successful(toRemove.size)
  }

  override def getBy(
    id: Int
  )(implicit
    executionContext: ExecutionContext
  ): Future[Option[TimeSeriesAnnotation]] =
    Future.successful(state.timeSeriesAnnotations.get(id).map(toAnnotation))

  override def findBy(
    channelIds: SortedSet[String]
  )(implicit
    executionContext: ExecutionContext
  ): Future[Seq[TimeSeriesAnnotation]] =
    Future.successful(
      state.timeSeriesAnnotations.values
        .filter { a =>
          val groupChannels = state.timeSeriesChannelGroups
            .getOrElse(a.channelGroupId, SortedSet.empty[String])
          groupChannels.intersect(channelIds).nonEmpty
        }
        .toSeq
        .sortBy(_.range.start.getOrElse(0L))
        .map(toAnnotation)
    )

  override def findBy(
    channelIds: SortedSet[String],
    qStart: Long,
    qEnd: Long,
    layerId: Int,
    limit: Long,
    offset: Long
  )(implicit
    executionContext: ExecutionContext
  ): Future[Seq[TimeSeriesAnnotation]] =
    Future.successful(
      annotationsInRange(channelIds, qStart, qEnd, Some(layerId), None)
        .drop(offset.toInt)
        .take(limit.toInt)
    )

  override def findBy(
    channelIds: SortedSet[String],
    qStart: Long,
    qEnd: Long,
    layerName: String,
    limit: Long,
    offset: Long
  )(implicit
    executionContext: ExecutionContext
  ): Future[Seq[TimeSeriesAnnotation]] =
    Future.successful(
      annotationsInRange(channelIds, qStart, qEnd, None, Some(layerName))
        .drop(offset.toInt)
        .take(limit.toInt)
    )

  override def findBy(
    timeSeriesId: String
  )(implicit
    executionContext: ExecutionContext
  ): Future[Seq[TimeSeriesAnnotation]] =
    Future.successful(
      state.timeSeriesAnnotations.values
        .filter(_.timeSeriesId == timeSeriesId)
        .toSeq
        .sortBy(_.range.start.getOrElse(0L))
        .map(toAnnotation)
    )

  override def hasAnnotations(tsPackageId: String): Future[Boolean] =
    Future.successful(
      state.timeSeriesAnnotations.values
        .exists(_.timeSeriesId == tsPackageId)
    )

  /** Window aggregation paths produce streams. The unit test suite uses these
    * via the controller; we keep the fake's stream behavior empty by default
    * and let tests that exercise this path opt out (they're marked pending in
    * the migration). */
  override def chunkWindowAggregate[A, F](
    frameStart: Long,
    frameEnd: Long,
    periodLength: Long,
    channelIds: SortedSet[String],
    layerId: Int,
    windowAggregator: WindowAggregator[A, F]
  ): Source[AnnotationAggregateWindowResult[F], NotUsed] =
    Source.empty

  override def windowAggregate[A, F](
    frameStart: Long,
    frameEnd: Long,
    periodLength: Long,
    channelIds: SortedSet[String],
    layerId: Int,
    windowAggregator: WindowAggregator[A, F]
  ): Source[AnnotationAggregateWindowResult[F], NotUsed] =
    Source.empty

  private def annotationsInRange(
    channelIds: SortedSet[String],
    qStart: Long,
    qEnd: Long,
    layerId: Option[Int],
    layerName: Option[String]
  ): Seq[TimeSeriesAnnotation] = {
    val targetLayerIds: Option[Set[Int]] = (layerId, layerName) match {
      case (Some(lid), _) => Some(Set(lid))
      case (_, Some(name)) =>
        Some(
          state.timeSeriesLayers.values.filter(_.name == name).map(_.id).toSet
        )
      case _ => None
    }
    state.timeSeriesAnnotations.values
      .filter { a =>
        val groupChannels = state.timeSeriesChannelGroups
          .getOrElse(a.channelGroupId, SortedSet.empty[String])
        val rangeStart = a.range.start.getOrElse(0L)
        val rangeEnd = a.range.end.getOrElse(0L)
        // Postgres @& is "ranges overlap".
        val overlap = rangeStart < qEnd && rangeEnd > qStart
        val matchesLayer = targetLayerIds.forall(_.contains(a.layerId))
        groupChannels.intersect(channelIds).nonEmpty && overlap && matchesLayer
      }
      .toSeq
      .sortBy(_.range.start.getOrElse(0L))
      .map(toAnnotation)
  }
}
