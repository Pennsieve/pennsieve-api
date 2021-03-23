package com.pennsieve.db

import com.pennsieve.traits.PostgresProfile.api._
import com.github.tminglei.slickpg.Range
import slick.dbio.Effect
import slick.sql.{ FixedSqlAction, FixedSqlStreamingAction }

case class TimeSeriesUnitRange(
  range: Range[Long],
  count: Int,
  channel: String,
  tsIndex: String, // S3 location
  tsBlob: String, // S3 location
  id: Int = 0
) {
  def min = range.start
  def max = range.end
}

/**
  * Note: this code is unused and only added for the Blackfynn -> Pennsieve migration
  */
class TimeSeriesUnitRangeTable(tag: Tag)
    extends Table[TimeSeriesUnitRange](tag, Some("timeseries"), "unit_ranges") {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def range = column[Range[Long]]("range")
  def count = column[Int]("count")
  def channel = column[String]("channel")
  def tsIndex = column[String]("tsindex")
  def tsBlob = column[String]("tsblob")

  def * =
    (range, count, channel, tsIndex, tsBlob, id).mapTo[TimeSeriesUnitRange]
}

object TimeSeriesUnitRangeMapper
    extends TableQuery(new TimeSeriesUnitRangeTable(_)) {}
