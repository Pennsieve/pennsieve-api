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

package com.pennsieve.traits

import com.pennsieve.core.utilities.DataDBContainer
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.db.{
  ChannelGroupTable,
  TimeSeriesAnnotationTable,
  TimeSeriesLayerTable
}
import com.pennsieve.managers.{
  ChannelGroupManager,
  TimeSeriesAnnotationManager,
  TimeSeriesLayerManager
}

trait TimeSeriesDBContainer { self: DataDBContainer =>

  lazy val channelGroupTableQuery: TableQuery[ChannelGroupTable] =
    new TableQuery(new ChannelGroupTable(_))
  lazy val channelGroupManager: ChannelGroupManager =
    new ChannelGroupManager(dataDB, channelGroupTableQuery)
  lazy val timeSeriesAnnotationTableQuery = new TableQuery(
    new TimeSeriesAnnotationTable(_)
  )
  lazy val layerTableQuery = new TableQuery(new TimeSeriesLayerTable(_))
  lazy val layerManager: TimeSeriesLayerManager = new TimeSeriesLayerManager(
    dataDB,
    layerTableQuery,
    timeSeriesAnnotationTableQuery
  )

  lazy val timeSeriesAnnotationManager: TimeSeriesAnnotationManager =
    new TimeSeriesAnnotationManager(db = dataDB)
}
