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

package com.pennsieve.models

import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._
import io.circe.{ Decoder, Encoder }

final case class OrcidAuthorization(
  name: String,
  accessToken: String,
  expiresIn: Long,
  tokenType: String,
  orcid: String,
  scope: String,
  refreshToken: String
)

object OrcidAuthorization {

  implicit val customConfig: Configuration =
    Configuration.default.withSnakeCaseMemberNames.withDefaults
  implicit val snakyEncoder: Encoder[OrcidAuthorization] = deriveEncoder
  implicit val snakyDecoder: Decoder[OrcidAuthorization] = deriveDecoder
}
