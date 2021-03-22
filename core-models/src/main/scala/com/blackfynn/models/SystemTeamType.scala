package com.pennsieve.models

import enumeratum.{ CirceEnum, Enum, EnumEntry }
import enumeratum.EnumEntry.Snakecase

import scala.collection.immutable

sealed trait SystemTeamType extends EnumEntry with Snakecase

object SystemTeamType
    extends Enum[SystemTeamType]
    with CirceEnum[SystemTeamType] {

  val values: immutable.IndexedSeq[SystemTeamType] = findValues

  case object Publishers extends SystemTeamType
}
