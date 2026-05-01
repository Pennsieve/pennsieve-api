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

import com.pennsieve.db.{
  TimeSeriesAnnotationTable,
  TimeSeriesLayer,
  TimeSeriesLayerTable
}
import com.pennsieve.managers.TimeSeriesLayerManager
import com.pennsieve.traits.PostgresProfile.api._

import scala.concurrent.{ ExecutionContext, Future }

class FakeTimeSeriesLayerManager(
  state: InMemoryState,
  phantomDb: Database,
  layerTQ: TableQuery[TimeSeriesLayerTable],
  annTQ: TableQuery[TimeSeriesAnnotationTable]
) extends TimeSeriesLayerManager(phantomDb, layerTQ, annTQ) {

  override def create(
    timeSeriesId: String,
    name: String,
    description: Option[String] = None,
    color: Option[String] = None
  ): Future[TimeSeriesLayer] = {
    val id = state.newId()
    val layer = TimeSeriesLayer(id, timeSeriesId, name, description, color)
    state.timeSeriesLayers.put(id, layer)
    Future.successful(layer)
  }

  override def update(
    layer: TimeSeriesLayer
  )(implicit
    executionContext: ExecutionContext
  ): Future[TimeSeriesLayer] = {
    state.timeSeriesLayers.put(layer.id, layer)
    Future.successful(layer)
  }

  override def delete(
    layerId: Int
  )(implicit
    executionContext: ExecutionContext
  ): Future[Unit] = {
    // Cascade-delete annotations on this layer.
    state.timeSeriesAnnotations
      .collect { case (k, a) if a.layerId == layerId => k }
      .foreach(state.timeSeriesAnnotations.remove)
    state.timeSeriesLayers.remove(layerId)
    Future.successful(())
  }

  override def delete(timeSeriesId: String): Future[Int] = {
    val toRemove = state.timeSeriesLayers.values
      .filter(_.timeSeriesId == timeSeriesId)
      .toList
    toRemove.foreach(l => state.timeSeriesLayers.remove(l.id))
    Future.successful(toRemove.size)
  }

  override def getBy(id: Int): Future[Option[TimeSeriesLayer]] =
    Future.successful(state.timeSeriesLayers.get(id))

  override def getExistingColors(timeSeriesId: String): Future[Set[String]] =
    Future.successful(
      state.timeSeriesLayers.values
        .filter(_.timeSeriesId == timeSeriesId)
        .flatMap(_.color)
        .toSet
    )

  override def findBy(
    timeSeriesId: String,
    limit: Long,
    offset: Long
  ): Future[Seq[TimeSeriesLayer]] = {
    val all = state.timeSeriesLayers.values
      .filter(_.timeSeriesId == timeSeriesId)
      .toSeq
      .sortBy(_.id)
      .drop(offset.toInt)
      .take(limit.toInt)
    Future.successful(all)
  }

  override def getLayerIds(
    timeSeriesId: String
  )(implicit
    executionContext: ExecutionContext
  ): Future[Set[Int]] =
    Future.successful(
      state.timeSeriesLayers.values
        .filter(_.timeSeriesId == timeSeriesId)
        .map(_.id)
        .toSet
    )
}
