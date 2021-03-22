// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.models

import enumeratum._
import enumeratum.EnumEntry._

import scala.collection.immutable

import scala.collection.SortedSet

sealed trait Role extends EnumEntry with Snakecase with Ordered[Role] {

  def weight: Int = Role.ordering.indexOf(this)

  def compare(that: Role): Int =
    this.weight - that.weight

  def toPermission: DBPermission = this match {
    case Role.Owner => DBPermission.Owner
    case Role.BlindReviewer => DBPermission.BlindReviewer
    case _ => DBPermission.Delete
  }
}
object Role extends Enum[Role] with CirceEnum[Role] {

  val values: immutable.IndexedSeq[Role] = findValues

  case object BlindReviewer extends Role
  case object Viewer extends Role
  case object Editor extends Role
  case object Manager extends Role
  case object Owner extends Role

  val ordering = List(BlindReviewer, Viewer, Editor, Manager, Owner)

  val maxRole = Some(ordering.last)
}
