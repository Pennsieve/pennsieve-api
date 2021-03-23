// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.domain

import enumeratum.EnumEntry.Snakecase
import enumeratum.{ CirceEnum, Enum, EnumEntry }

sealed trait StorageAggregation extends EnumEntry with Snakecase

object StorageAggregation
    extends Enum[StorageAggregation]
    with CirceEnum[StorageAggregation] {

  val values = findValues

  case object spackages extends StorageAggregation
  case object sdatasets extends StorageAggregation
  case object susers extends StorageAggregation
  case object sorganizations extends StorageAggregation
}
