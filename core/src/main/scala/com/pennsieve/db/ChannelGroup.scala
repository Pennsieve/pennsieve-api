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

import scala.collection.SortedSet
import scala.collection.compat._
import com.pennsieve.traits.PostgresProfile.api._

object ChannelGroup {

  def apply(id: Int, channels: List[String]) =
    new ChannelGroup(id, channels.to(SortedSet))

  def toDBTuple(channelGroup: ChannelGroup) =
    Some((channelGroup.id, channelGroup.channels.toList))

  def fromDBTuple(tuple: (Int, List[String])) = ChannelGroup(tuple._1, tuple._2)
}

case class ChannelGroup(id: Int, channels: SortedSet[String])

class ChannelGroupTable(tag: Tag)
    extends Table[ChannelGroup](tag, Some("timeseries"), "channel_groups") {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def channelIds = column[List[String]]("channels")

  def * = (id, channelIds) <> (ChannelGroup.fromDBTuple, ChannelGroup.toDBTuple)
}
