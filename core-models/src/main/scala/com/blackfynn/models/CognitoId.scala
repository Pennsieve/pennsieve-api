// Copyright (c) [2018] - [2021] Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.models

import io.circe.{ Decoder, Encoder }

import java.util.UUID

final case class CognitoId(value: UUID) extends AnyVal {
  override def toString: String = value.toString
}

object CognitoId {
  implicit val cognitoIdEncoder: Encoder[CognitoId] =
    Encoder[UUID].contramap(_.value)

  implicit val cognitoIdDecoder: Decoder[CognitoId] =
    Decoder.decodeUUID.map(CognitoId(_))

  def randomId() = CognitoId(UUID.randomUUID())

}
