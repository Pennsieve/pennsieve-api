// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.models

import enumeratum._
import enumeratum.EnumEntry._

sealed trait FileProcessingState extends EnumEntry with Snakecase

object FileProcessingState
    extends Enum[FileProcessingState]
    with CirceEnum[FileProcessingState] {
  val values = findValues

  case object Processed extends FileProcessingState
  case object Unprocessed extends FileProcessingState
  case object NotProcessable extends FileProcessingState
}
