// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.models

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
