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

sealed trait EmbargoAccess extends EnumEntry with Snakecase

object EmbargoAccess extends Enum[EmbargoAccess] with CirceEnum[EmbargoAccess] {

  val values: immutable.IndexedSeq[EmbargoAccess] = findValues

  case object Requested extends EmbargoAccess
  case object Granted extends EmbargoAccess
  @deprecated(
    "Should not be used. Embargo access request refusal is no longer supported.",
    "166-27f7fae"
  )
  case object Refused extends EmbargoAccess
}
