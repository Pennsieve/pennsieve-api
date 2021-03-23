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

package com.pennsieve.db

import com.pennsieve.traits.PostgresProfile.api._
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
