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
