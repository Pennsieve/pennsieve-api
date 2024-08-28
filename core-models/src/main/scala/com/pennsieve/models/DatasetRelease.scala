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
import enumeratum._
import enumeratum.EnumEntry._

import scala.collection.immutable

import java.time.ZonedDateTime

sealed trait DatasetReleaseStatus extends EnumEntry with Snakecase

object DatasetReleaseStatus
    extends Enum[DatasetReleaseStatus]
    with CirceEnum[DatasetReleaseStatus] {
  val values: immutable.IndexedSeq[DatasetReleaseStatus] = findValues

  case object Created extends DatasetReleaseStatus
  case object Deleted extends DatasetReleaseStatus
  case object Edited extends DatasetReleaseStatus
  case object Prereleased extends DatasetReleaseStatus
  case object Published extends DatasetReleaseStatus
  case object Released extends DatasetReleaseStatus
  case object Unpublished extends DatasetReleaseStatus
}

sealed trait DatasetReleasePublishingStatus extends EnumEntry with Snakecase

object DatasetReleasePublishingStatus
    extends Enum[DatasetReleasePublishingStatus]
    with CirceEnum[DatasetReleasePublishingStatus] {
  val values: immutable.IndexedSeq[DatasetReleasePublishingStatus] = findValues

  case object Initial extends DatasetReleasePublishingStatus
  case object Ready extends DatasetReleasePublishingStatus
  case object NotReady extends DatasetReleasePublishingStatus
  case object InProgress extends DatasetReleasePublishingStatus
  case object Succeeded extends DatasetReleasePublishingStatus
  case object Failed extends DatasetReleasePublishingStatus
}

final case class DatasetRelease(
  id: Int = 0,
  datasetId: Int,
  origin: String,
  url: String,
  label: Option[String] = None,
  marker: Option[String] = None,
  releaseDate: Option[ZonedDateTime] = None,
  properties: List[ModelProperty] = List.empty,
  tags: List[String] = List.empty,
  createdAt: ZonedDateTime = ZonedDateTime.now(),
  updatedAt: ZonedDateTime = ZonedDateTime.now(),
  releaseStatus: DatasetReleaseStatus = DatasetReleaseStatus.Created,
  publishingStatus: DatasetReleasePublishingStatus =
    DatasetReleasePublishingStatus.Initial
)

object DatasetRelease {
  implicit val encoder: Encoder[DatasetRelease] = deriveEncoder[DatasetRelease]
  implicit val decoder: Decoder[DatasetRelease] = deriveDecoder[DatasetRelease]

  val tupled = (this.apply _).tupled
}
