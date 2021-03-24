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

sealed trait PayloadType extends EnumEntry with Snakecase

object PayloadType extends Enum[PayloadType] with CirceEnum[PayloadType] {

  val values: immutable.IndexedSeq[PayloadType] = findValues

  case object Upload extends PayloadType
  case object Append extends PayloadType
  case object Workflow extends PayloadType
  case object Export extends PayloadType
}
