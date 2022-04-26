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
