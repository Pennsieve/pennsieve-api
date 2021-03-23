// Copyright (c) 2020 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.models

import enumeratum.{ CirceEnum, Enum, EnumEntry }
import enumeratum.EnumEntry.Snakecase

import scala.collection.immutable

sealed trait PublicationType extends EnumEntry with Snakecase

object PublicationType
    extends Enum[PublicationType]
    with CirceEnum[PublicationType] {

  val values: immutable.IndexedSeq[PublicationType] = findValues

  case object Revision extends PublicationType
  case object Publication extends PublicationType
  case object Removal extends PublicationType
  case object Embargo extends PublicationType
  case object Release extends PublicationType
}
