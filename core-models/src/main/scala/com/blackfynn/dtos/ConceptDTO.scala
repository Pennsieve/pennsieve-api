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

import java.time.OffsetDateTime
import java.util.UUID

import com.pennsieve.concepts.types._
import com.pennsieve.models.NodeId
import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.java8.time._
import io.circe.shapes._

case class ConceptDTO(
  name: String,
  displayName: String,
  description: String,
  createdBy: NodeId,
  updatedBy: NodeId,
  locked: Boolean,
  id: UUID,
  count: Int,
  createdAt: OffsetDateTime,
  updatedAt: OffsetDateTime
)

object ConceptDTO {
  implicit val encoder: Encoder[ConceptDTO] =
    deriveEncoder[ConceptDTO]
  implicit val decoder: Decoder[ConceptDTO] =
    deriveDecoder[ConceptDTO]
}

case class ConceptInstanceDTO(
  id: UUID,
  `type`: String,
  values: List[InstanceDatumDTO],
  createdAt: OffsetDateTime,
  updatedAt: OffsetDateTime,
  createdBy: NodeId,
  updatedBy: NodeId
)

object ConceptInstanceDTO {
  implicit val encoder: Encoder[ConceptInstanceDTO] =
    deriveEncoder[ConceptInstanceDTO]
  implicit val decoder: Decoder[ConceptInstanceDTO] =
    deriveDecoder[ConceptInstanceDTO]
}

case class InstanceDatumDTO(
  name: String,
  displayName: String,
  value: InstanceValue, //this can be more than just String
  locked: Boolean,
  required: Boolean,
  default: Boolean,
  conceptTitle: Boolean
)

object InstanceDatumDTO {
  implicit val encoder: Encoder[InstanceDatumDTO] =
    deriveEncoder[InstanceDatumDTO]
  implicit val decoder: Decoder[InstanceDatumDTO] =
    deriveDecoder[InstanceDatumDTO]
}
