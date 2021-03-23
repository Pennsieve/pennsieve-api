/**
  * *   Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.
  */
package com.pennsieve.db

import com.pennsieve.traits.PostgresProfile.api._

object TimeSeriesLayer {

  val defaultLayerName = "Default"
}

case class TimeSeriesLayer(
  id: Int,
  timeSeriesId: String,
  name: String,
  description: Option[String],
  color: Option[String]
)

class TimeSeriesLayerTable(tag: Tag)
    extends Table[TimeSeriesLayer](tag, Some("timeseries"), "layers") {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def timeSeriesId = column[String]("time_series_id")
  def name = column[String]("name")
  def description = column[Option[String]]("description")
  def color = column[Option[String]]("color")

  def * =
    (id, timeSeriesId, name, description, color) <> ((TimeSeriesLayer.apply _).tupled, TimeSeriesLayer.unapply)
}
