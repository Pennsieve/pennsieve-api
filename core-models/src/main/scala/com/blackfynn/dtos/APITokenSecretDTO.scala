// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.dtos

import java.time.ZonedDateTime

import com.pennsieve.models.Token

case class Secret(plaintext: String) extends AnyVal

case class APITokenSecretDTO(
  name: String,
  key: String,
  secret: String,
  lastUsed: Option[ZonedDateTime]
)

object APITokenSecretDTO {
  def apply(token: Token, secret: Secret): APITokenSecretDTO =
    new APITokenSecretDTO(
      token.name,
      token.token,
      secret.plaintext,
      token.lastUsed
    )

  def apply(token_secret: (Token, Secret)): APITokenSecretDTO =
    APITokenSecretDTO(token_secret._1, token_secret._2)
}
