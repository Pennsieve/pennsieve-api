// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.dtos

import java.time.ZonedDateTime

import com.pennsieve.models.{ Channel, Package }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.{ Decoder, Encoder }

case class WrappedChannel(
  id: String,
  packageId: String,
  name: String,
  start: Long,
  end: Long,
  unit: String,
  rate: Double,
  channelType: String,
  group: Option[String],
  lastAnnotation: Long,
  spikeDuration: Option[Long],
  createdAt: ZonedDateTime
)

object WrappedChannel {
  import io.circe.java8.time._

  implicit val encoder: Encoder[WrappedChannel] = deriveEncoder[WrappedChannel]
  implicit val decoder: Decoder[WrappedChannel] = deriveDecoder[WrappedChannel]

  def apply(channel: Channel, `package`: Package): WrappedChannel =
    WrappedChannel(
      id = channel.nodeId,
      packageId = `package`.nodeId,
      name = channel.name,
      start = channel.start,
      end = channel.end,
      unit = channel.unit,
      rate = channel.rate,
      channelType = channel.`type`,
      group = channel.group,
      lastAnnotation = channel.lastAnnotation,
      spikeDuration = channel.spikeDuration,
      createdAt = channel.createdAt
    )
}
