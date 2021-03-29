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

import java.util.Date

import enumeratum.EnumEntry.UpperSnakecase
import enumeratum._

import scala.collection.immutable.IndexedSeq

sealed trait PublishStatus extends EnumEntry with UpperSnakecase

object PublishStatus extends Enum[PublishStatus] with CirceEnum[PublishStatus] {
  val values: IndexedSeq[PublishStatus] = findValues

  case object NotPublished extends PublishStatus

  case object PublishInProgress extends PublishStatus
  case object PublishSucceeded extends PublishStatus
  case object PublishFailed extends PublishStatus

  case object EmbargoInProgress extends PublishStatus
  case object EmbargoSucceeded extends PublishStatus
  case object EmbargoFailed extends PublishStatus

  case object ReleaseInProgress extends PublishStatus
  case object ReleaseFailed extends PublishStatus
  // Note: there is no `ReleaseSucceeded` state. A successful release becomes
  // `PublishSucceeded`.

  case object Unpublished extends PublishStatus
}
