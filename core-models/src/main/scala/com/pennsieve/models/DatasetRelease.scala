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

import enumeratum._
import enumeratum.EnumEntry.Snakecase
import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

import java.time.ZonedDateTime
import scala.collection.immutable.IndexedSeq

sealed trait ReleaseOrigin extends EnumEntry with Snakecase

object ReleaseOrigin extends Enum[ReleaseOrigin] with CirceEnum[ReleaseOrigin] {
  val values: IndexedSeq[ReleaseOrigin] = findValues

  case object Github extends ReleaseOrigin
}

case class DatasetRelease(
  id: Int,
  datasetId: Int,
  origin: ReleaseOrigin,
  url: String,
  label: String,
  marker: Option[String],
  releaseDate: Option[ZonedDateTime],
  properties: Option[List[NameValueProperty]],
  tags: Option[List[String]],
  createdAt: ZonedDateTime = ZonedDateTime.now,
  updatedAt: ZonedDateTime = ZonedDateTime.now
)

object DatasetRelease {
  implicit val encoder: Encoder[DatasetRelease] =
    deriveEncoder[DatasetRelease]
  implicit val decoder: Decoder[DatasetRelease] =
    deriveDecoder[DatasetRelease]

  val tupled = (this.apply _).tupled
}
