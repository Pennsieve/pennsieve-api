/*
 * Copyright 2021 University of Pennsylvania
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pennsieve.models

import enumeratum._
import enumeratum.EnumEntry._
import enumeratum.values.{ IntEnum, IntEnumEntry }

import scala.collection.immutable
import scala.language.experimental.macros

import scala.collection.SortedSet

sealed abstract class DBPermission(val value: Int)
    extends IntEnumEntry
    with EnumEntry
    with Product
    with Serializable
    with Ordered[DBPermission]
    with Snakecase {

  def compare(that: DBPermission): Int =
    this.value - that.value

  def toRole: Option[Role] = this match {
    case DBPermission.Owner => Some(Role.Owner)
    case DBPermission.Administer => Some(Role.Manager)
    case DBPermission.Delete => Some(Role.Editor)
    case DBPermission.NoPermission => None
    case _ => Some(Role.Viewer)
  }
}

object DBPermission
    extends IntEnum[DBPermission]
    with Enum[DBPermission]
    with CirceEnum[DBPermission] {

  override def findValues: immutable.IndexedSeq[DBPermission] =
    macro ValueEnumMacros.findIntValueEntriesImpl[DBPermission]

  val values: immutable.IndexedSeq[DBPermission] = findValues

  case object NoPermission extends DBPermission(0)
  case object Collaborate extends DBPermission(1)
  case object Read extends DBPermission(2)
  case object Write extends DBPermission(4)
  case object Delete extends DBPermission(8)
  case object Administer extends DBPermission(16)
  case object Owner extends DBPermission(32)

  // this SortedSet is in order from least permission to highest. See fromBitmap below
  val allPermission =
    SortedSet(Collaborate, Read, Write, Delete, Administer, Owner)

  val maxPermission: DBPermission = allPermission.max

  def fromRole(role: Option[Role]): DBPermission = role match {
    case Some(Role.Owner) => DBPermission.Owner
    case Some(Role.Manager) => DBPermission.Administer
    case Some(Role.Editor) => DBPermission.Delete
    case Some(Role.Viewer) => DBPermission.Read
    case None => DBPermission.NoPermission
  }
}
