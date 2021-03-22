// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.models

import java.util.UUID

import io.circe.generic.extras.semiauto.{
  deriveUnwrappedDecoder,
  deriveUnwrappedEncoder
}
import io.circe.{ Decoder, Encoder }

case class JobId(value: UUID) extends AnyVal {
  override def toString: String = value.toString
}

object JobId {
  implicit val encoder: Encoder[JobId] = deriveUnwrappedEncoder
  implicit val decoder: Decoder[JobId] = deriveUnwrappedDecoder
}
