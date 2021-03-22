/**
  * *   Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.
  */
package com.blackfynn.db

import com.blackfynn.traits.PostgresProfile.api._
import com.github.tminglei.slickpg.Range
import slick.dbio.Effect
import slick.sql.{ FixedSqlAction, FixedSqlStreamingAction }

case class TimeSeriesRange(
  range: Range[Long],
  sampleRate: Double,
  channel: String,
  file: String,
  id: Int = 0
) {
  def min = range.start
  def max = range.end
}

class TimeSeriesRangeTable(tag: Tag)
    extends Table[TimeSeriesRange](tag, Some("timeseries"), "ranges") {

  def range = column[Range[Long]]("range")
  def sampleRate = column[Double]("rate")
  def channel = column[String]("channel")
  def file = column[String]("location")
  def followsGap = column[Boolean]("follows_gap")
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)

  def * =
    ((range, sampleRate, channel, file, id), followsGap) <> ({
      case (values, _) => TimeSeriesRange.tupled(values)
    }, { timeSeriesRange: TimeSeriesRange =>
      TimeSeriesRange.unapply(timeSeriesRange).map(values => (values, false))
    })
}

object TimeSeriesRangeMapper extends TableQuery(new TimeSeriesRangeTable(_)) {

  def lookup(
    start: Long,
    end: Long,
    channel: String
  ): FixedSqlStreamingAction[Seq[TimeSeriesRange], TimeSeriesRange, Effect.Read] = {
    val rng = Range[Long](start, end)
    this.filter(r => r.channel === channel && r.range @& rng).result
  }

  def addRangeLookup(
    range: Range[Long],
    sampleRate: Double,
    channel: String,
    file: String
  ): DBIO[TimeSeriesRange] = {
    this returning this += TimeSeriesRange(range, sampleRate, channel, file)
  }

  def updateRangeLookup(
    range: TimeSeriesRange
  ): FixedSqlAction[Int, NoStream, Effect.Write] =
    this.filter(_.id === range.id).update(range)

  def get(
    channelId: String
  ): FixedSqlStreamingAction[Seq[TimeSeriesRange], TimeSeriesRange, Effect.Read] =
    this.filter(_.channel === channelId).result

  def deleteRangeLookups(
    channelId: String
  ): FixedSqlAction[Int, NoStream, Effect.Write] =
    this.filter(_.channel === channelId).delete

}
