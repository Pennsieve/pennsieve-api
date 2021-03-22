/**
  * *   Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.
  */
package com.blackfynn.db

import com.blackfynn.timeseries.AnnotationData
import com.github.tminglei.slickpg.Range
import com.blackfynn.traits.PostgresProfile.api._
import scala.collection.SortedSet

case class DBTimeSeriesAnnotation(
  id: Int,
  timeSeriesId: String,
  channelGroupId: Int,
  layerId: Int,
  name: String,
  label: String,
  description: Option[String],
  userNodeId: Option[String],
  range: Range[Long],
  data: Option[AnnotationData],
  linkedPackage: Option[String]
) {

  def toTimeSeriesAnnotation(channelGroup: ChannelGroup): TimeSeriesAnnotation =
    TimeSeriesAnnotation(
      id = id,
      timeSeriesId = timeSeriesId,
      channelIds = channelGroup.channels,
      layerId = layerId,
      name = name,
      label = label,
      description = description,
      userId = userNodeId,
      start = range.start.getOrElse(0),
      end = range.end.getOrElse(0),
      data = data,
      linkedPackage = linkedPackage
    )
}

case class TimeSeriesAnnotation(
  id: Int,
  timeSeriesId: String,
  channelIds: SortedSet[String],
  layerId: Int,
  name: String,
  label: String,
  description: Option[String],
  userId: Option[String],
  start: Long,
  end: Long,
  data: Option[AnnotationData],
  linkedPackage: Option[String]
) {

  def toDBTimeSeriesAnnotation(channelGroup: ChannelGroup) =
    DBTimeSeriesAnnotation(
      id = id,
      timeSeriesId = timeSeriesId,
      channelGroupId = channelGroup.id,
      layerId = layerId,
      name = name,
      label = label,
      description = description,
      userNodeId = userId,
      range = Range[Long](start, end),
      data = data,
      linkedPackage = linkedPackage
    )
}

class TimeSeriesAnnotationTable(tag: Tag)
    extends Table[DBTimeSeriesAnnotation](
      tag,
      Some("timeseries"),
      "annotations"
    ) {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def timeSeriesId = column[String]("time_series_id")
  def channelGroupId = column[Int]("channel_group_id")
  def layerId = column[Int]("layer_id")
  def name = column[String]("name")
  def label = column[String]("label")
  def description = column[Option[String]]("description")
  def userNodeId = column[Option[String]]("user_id")
  def range = column[Range[Long]]("range")
  def data = column[Option[AnnotationData]]("data")
  def linkedPackage = column[Option[String]]("linked_package")

  def createColumns =
    (
      timeSeriesId,
      channelGroupId,
      layerId,
      name,
      label,
      description,
      userNodeId,
      range,
      data,
      linkedPackage
    )

  def * =
    (
      id,
      timeSeriesId,
      channelGroupId,
      layerId,
      name,
      label,
      description,
      userNodeId,
      range,
      data,
      linkedPackage
    ).mapTo[DBTimeSeriesAnnotation]
}
