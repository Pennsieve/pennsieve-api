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

sealed trait FileProcessingState extends EnumEntry with Snakecase

object FileProcessingState
    extends Enum[FileProcessingState]
    with CirceEnum[FileProcessingState] {
  val values = findValues

  case object Processed extends FileProcessingState
  case object Unprocessed extends FileProcessingState
  case object NotProcessable extends FileProcessingState
}
