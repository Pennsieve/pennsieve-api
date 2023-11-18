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
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.{ Decoder, Encoder }

import java.time.ZonedDateTime

case class NameValueProperty(name: String, value: String)
object NameValueProperty {
  implicit val encoder: Encoder[NameValueProperty] =
    deriveEncoder[NameValueProperty]
  implicit val decoder: Decoder[NameValueProperty] =
    deriveDecoder[NameValueProperty]
}

sealed trait ReferenceType extends EnumEntry with Snakecase

object ReferenceType extends Enum[ReferenceType] with CirceEnum[ReferenceType] {
  val values: IndexedSeq[ReferenceType] = findValues

  case object Pennsieve extends ReferenceType
  case object DOI extends ReferenceType
}

case class DatasetReference(
  id: Int,
  datasetId: Int,
  referenceOrder: Int,
  referenceType: ReferenceType,
  referenceId: String,
  properties: Option[List[NameValueProperty]],
  tags: Option[List[String]],
  createdAt: ZonedDateTime = ZonedDateTime.now,
  updatedAt: ZonedDateTime = ZonedDateTime.now
)

object DatasetReference {
  implicit val encoder: Encoder[DatasetReference] =
    deriveEncoder[DatasetReference]
  implicit val decoder: Decoder[DatasetReference] =
    deriveDecoder[DatasetReference]

  val tupled = (this.apply _).tupled
}
