package com.pennsieve.dtos

import java.time.ZonedDateTime

import com.pennsieve.models.Token

case class APITokenDTO(
  name: String,
  key: String,
  lastUsed: Option[ZonedDateTime]
)

object APITokenDTO {
  def apply(token: Token): APITokenDTO =
    new APITokenDTO(token.name, token.token, token.lastUsed)

  def apply(tokens: List[Token]): List[APITokenDTO] =
    tokens.map((token: Token) => APITokenDTO(token))
}
