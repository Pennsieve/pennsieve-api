// Copyright (c) 2020 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.models

import enumeratum.{ CirceEnum, Enum, EnumEntry }
import enumeratum.EnumEntry.Uppercase

import scala.collection.immutable

sealed trait FileState extends EnumEntry with Uppercase

object FileState extends Enum[FileState] with CirceEnum[FileState] {

  val values: immutable.IndexedSeq[FileState] = findValues

  case object SCANNING extends FileState
  case object PENDING extends FileState
  case object UPLOADED extends FileState
}
