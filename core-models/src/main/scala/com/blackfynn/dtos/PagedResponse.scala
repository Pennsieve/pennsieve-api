package com.blackfynn.dtos

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

case class PagedResponse[T](
  limit: Long,
  offset: Long,
  results: Seq[T],
  totalCount: Option[Long] = None
)

object PagedResponse {
  implicit def encoder[T: Encoder]: Encoder[PagedResponse[T]] =
    deriveEncoder[PagedResponse[T]]
  implicit def decoder[T: Decoder]: Decoder[PagedResponse[T]] =
    deriveDecoder[PagedResponse[T]]
}
