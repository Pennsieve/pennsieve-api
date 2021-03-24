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

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

case class PublishedExternalPublication(
  doi: Doi,
  relationshipType: Option[RelationshipType] = None
)

object PublishedExternalPublication {

  def apply(
    externalPublication: ExternalPublication
  ): PublishedExternalPublication =
    PublishedExternalPublication(
      doi = externalPublication.doi,
      relationshipType = Some(externalPublication.relationshipType)
    )

  implicit val decoder: Decoder[PublishedExternalPublication] =
    deriveDecoder[PublishedExternalPublication]
  implicit val encoder: Encoder[PublishedExternalPublication] =
    deriveEncoder[PublishedExternalPublication]
}
