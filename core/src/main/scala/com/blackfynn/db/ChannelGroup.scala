/**
  * *   Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.
  */
package com.blackfynn.db

import scala.collection.SortedSet
import com.blackfynn.traits.PostgresProfile.api._

object ChannelGroup {

  def apply(id: Int, channels: List[String]) =
    new ChannelGroup(id, channels.to[SortedSet])

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
