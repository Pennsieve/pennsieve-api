// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.dtos

import com.pennsieve.models.{ Channel, Package }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.{ Decoder, Encoder }

case class ChannelDTO(
  content: WrappedChannel,
  properties: List[ModelPropertiesDTO]
)

object ChannelDTO {

  implicit val encoder: Encoder[ChannelDTO] = deriveEncoder[ChannelDTO]
  implicit val decoder: Decoder[ChannelDTO] = deriveDecoder[ChannelDTO]

  def apply(channel: Channel, `package`: Package): ChannelDTO =
    ChannelDTO(
      WrappedChannel(channel, `package`),
      ModelPropertiesDTO.fromModelProperties(channel.properties)
    )

  def apply(channels: List[Channel], `package`: Package): List[ChannelDTO] =
    channels.map(ChannelDTO(_, `package`))
}
