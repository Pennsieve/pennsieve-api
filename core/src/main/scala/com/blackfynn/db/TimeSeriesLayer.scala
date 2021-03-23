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
