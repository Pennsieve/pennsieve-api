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

import scala.collection.immutable

import scala.collection.SortedSet

sealed trait Permission
    extends Product
    with Serializable
    with Ordered[Permission]
    with EnumEntry
    with Snakecase {

  import com.pennsieve.models.Permission.{
    Administer,
    Collaborate,
    Delete,
    Owner,
    Read,
    Write
  }

  def toBitmap: Int = {
    this match {
      case Collaborate => 1
      case Read => 2
      case Write => 4
      case Delete => 8
      case Administer => 16
      case Owner => 32
    }
  }

  def compare(that: Permission): Int =
    this.toBitmap - that.toBitmap

}

object Permission extends Enum[Permission] with CirceEnum[Permission] {

  val values: immutable.IndexedSeq[Permission] = findValues

  case object Collaborate extends Permission
  case object Read extends Permission
  case object Write extends Permission
  case object Delete extends Permission
  case object Administer extends Permission
  case object Owner extends Permission

  // this SortedSet is in order from least permission to highest. See fromBitmap below
  val allPermission =
    SortedSet(Collaborate, Read, Write, Delete, Administer, Owner)

  def fromBitmap(value: Int): Option[Permission] = {
    value match {
      case 1 => Some(Collaborate)
      case 2 => Some(Read)
      case 4 => Some(Write)
      case 8 => Some(Delete)
      case 16 => Some(Administer)
      case 32 => Some(Owner)
    }
  }

  def hasPermission(bitmap: Int, permission: Permission): Boolean = {
    (bitmap & permission.toBitmap) == permission.toBitmap
  }

}
