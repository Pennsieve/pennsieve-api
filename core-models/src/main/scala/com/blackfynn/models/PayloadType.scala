// Copyright (c) 2019 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.models

import enumeratum.{ CirceEnum, Enum, EnumEntry }
import enumeratum.EnumEntry.Snakecase

import scala.collection.immutable

sealed trait PayloadType extends EnumEntry with Snakecase

object PayloadType extends Enum[PayloadType] with CirceEnum[PayloadType] {

  val values: immutable.IndexedSeq[PayloadType] = findValues

  case object Upload extends PayloadType
  case object Append extends PayloadType
  case object Workflow extends PayloadType
  case object Export extends PayloadType
}
