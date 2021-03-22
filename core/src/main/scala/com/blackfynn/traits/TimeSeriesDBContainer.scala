// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.traits

import com.blackfynn.core.utilities.DataDBContainer
import com.blackfynn.traits.PostgresProfile.api._
import com.blackfynn.db.{
  ChannelGroupTable,
  TimeSeriesAnnotationTable,
  TimeSeriesLayerTable
}
import com.blackfynn.managers.{
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
