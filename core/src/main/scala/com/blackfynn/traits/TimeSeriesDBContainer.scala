// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

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
