// Copyright (c) 2020 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.models

import enumeratum.{ CirceEnum, Enum, EnumEntry }
import enumeratum.EnumEntry.Snakecase

import scala.collection.immutable

sealed abstract class Degree(override val entryName: String) extends EnumEntry

object Degree extends Enum[Degree] with CirceEnum[Degree] {

  val values: immutable.IndexedSeq[Degree] = findValues

  case object PhD extends Degree("Ph.D.")
  case object MD extends Degree("M.D.")
  case object MS extends Degree("M.S.")
  case object BS extends Degree("B.S.")
  case object PharmD extends Degree("Pharm.D.")
  case object DVM extends Degree("D.V.M.")
  case object DO extends Degree("D.O.")

}
