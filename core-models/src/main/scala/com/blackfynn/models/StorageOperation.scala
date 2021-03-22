package com.blackfynn.models

import enumeratum._

import scala.collection.immutable

sealed trait StorageOperation extends EnumEntry

object StorageOperation
    extends Enum[StorageOperation]
    with CirceEnum[StorageOperation] {

  val values: immutable.IndexedSeq[StorageOperation] = findValues

  case object Increment extends StorageOperation
  case object Decrement extends StorageOperation

}
