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
