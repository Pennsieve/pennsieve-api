// Copyright (c) 2020 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.models

import enumeratum.{ CirceEnum, Enum, EnumEntry }
import enumeratum.EnumEntry.Snakecase

import scala.collection.immutable

sealed trait EmbargoAccess extends EnumEntry with Snakecase

object EmbargoAccess extends Enum[EmbargoAccess] with CirceEnum[EmbargoAccess] {

  val values: immutable.IndexedSeq[EmbargoAccess] = findValues

  case object Requested extends EmbargoAccess
  case object Granted extends EmbargoAccess
  @deprecated(
    "Should not be used. Embargo access request refusal is no longer supported."
  )
  case object Refused extends EmbargoAccess
}
