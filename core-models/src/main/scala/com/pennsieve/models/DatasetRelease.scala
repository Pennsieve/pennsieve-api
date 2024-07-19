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

import java.time.ZonedDateTime

final case class DatasetRelease(
  id: Int,
  datasetId: Int,
  origin: String,
  url: String,
  label: Option[String],
  marker: Option[String],
  releaseDate: Option[ZonedDateTime],
  properties: List[ModelProperty] = List.empty,
  tags: List[String] = List.empty,
  createdAt: ZonedDateTime = ZonedDateTime.now(),
  updatedAt: ZonedDateTime = ZonedDateTime.now()
)

object DatasetRelease {
  implicit val encoder: Encoder[DatasetRelease] = deriveEncoder[DatasetRelease]
  implicit val decoder: Decoder[DatasetRelease] = deriveDecoder[DatasetRelease]

  val tupled = (this.apply _).tupled
}
