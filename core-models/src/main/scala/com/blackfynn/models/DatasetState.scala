// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.models

import enumeratum._

import scala.collection.immutable

sealed trait DatasetState extends EnumEntry with EnumEntry.UpperSnakecase

object DatasetState extends Enum[DatasetState] with CirceEnum[DatasetState] {

  val values: immutable.IndexedSeq[DatasetState] = findValues

  case object READY extends DatasetState
  case object DELETING extends DatasetState

}
