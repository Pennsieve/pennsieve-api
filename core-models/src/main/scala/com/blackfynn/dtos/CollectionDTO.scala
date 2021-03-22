// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.dtos

import com.blackfynn.models.Collection
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.{ Decoder, Encoder }

final case class CollectionDTO(id: Int, name: String) {}

object CollectionDTO {

  implicit val encoder: Encoder[CollectionDTO] =
    deriveEncoder[CollectionDTO]
  implicit val decoder: Decoder[CollectionDTO] =
    deriveDecoder[CollectionDTO]

  def apply(collection: Collection): CollectionDTO = {

    CollectionDTO(id = collection.id, name = collection.name)

  }
}
