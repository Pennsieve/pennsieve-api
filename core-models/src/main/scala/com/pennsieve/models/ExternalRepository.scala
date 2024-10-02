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

import java.time.ZonedDateTime
import scala.collection.immutable

case class SyncSetting(setting: String, value: Boolean)
object SyncSetting {
  implicit val encoder: Encoder[SyncSetting] = deriveEncoder[SyncSetting]
  implicit val decoder: Decoder[SyncSetting] = deriveDecoder[SyncSetting]
}

sealed trait ExternalRepositoryType extends EnumEntry with Snakecase

object ExternalRepositoryType
    extends Enum[ExternalRepositoryType]
    with CirceEnum[ExternalRepositoryType] {
  val values: immutable.IndexedSeq[ExternalRepositoryType] = findValues

  case object Unknown extends ExternalRepositoryType
  case object Publishing extends ExternalRepositoryType
  case object AppStore extends ExternalRepositoryType
}

sealed trait ExternalRepositoryStatus extends EnumEntry with Snakecase

object ExternalRepositoryStatus
    extends Enum[ExternalRepositoryStatus]
    with CirceEnum[ExternalRepositoryStatus] {
  val values: immutable.IndexedSeq[ExternalRepositoryStatus] = findValues

  case object Unknown extends ExternalRepositoryStatus
  case object Enabled extends ExternalRepositoryStatus
  case object Disabled extends ExternalRepositoryStatus
  case object Suspended extends ExternalRepositoryStatus
}

case class ExternalRepository(
  id: Int = 0,
  origin: String,
  `type`: ExternalRepositoryType,
  url: String,
  organizationId: Int,
  userId: Int,
  datasetId: Option[Int] = None,
  applicationId: Option[Int] = None,
  status: ExternalRepositoryStatus,
  autoProcess: Boolean = false,
  synchronize: List[SyncSetting] = List.empty,
  createdAt: ZonedDateTime = ZonedDateTime.now(),
  updatedAt: ZonedDateTime = ZonedDateTime.now()
)

object ExternalRepository {
  implicit val encoder: Encoder[ExternalRepository] =
    deriveEncoder[ExternalRepository]
  implicit val decoder: Decoder[ExternalRepository] =
    deriveDecoder[ExternalRepository]

  val tupled = (this.apply _).tupled
}
