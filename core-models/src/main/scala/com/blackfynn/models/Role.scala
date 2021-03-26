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

sealed trait Role extends EnumEntry with Snakecase with Ordered[Role] {

  def weight: Int = Role.ordering.indexOf(this)

  def compare(that: Role): Int =
    this.weight - that.weight

  def toPermission: DBPermission = this match {
    case Role.Owner => DBPermission.Owner
    case _ => DBPermission.Delete
  }
}
object Role extends Enum[Role] with CirceEnum[Role] {

  val values: immutable.IndexedSeq[Role] = findValues

  case object Viewer extends Role
  case object Editor extends Role
  case object Manager extends Role
  case object Owner extends Role

  val ordering = List(Viewer, Editor, Manager, Owner)

  val maxRole = Some(ordering.last)
}
