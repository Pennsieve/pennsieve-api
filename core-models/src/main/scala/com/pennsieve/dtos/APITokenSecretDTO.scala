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

import com.pennsieve.models.Token

// TODO: move to models
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
