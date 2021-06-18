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

package com.pennsieve.domain

import enumeratum.EnumEntry.Snakecase
import enumeratum.{ CirceEnum, Enum, EnumEntry }

sealed trait StorageAggregation extends EnumEntry with Snakecase

object StorageAggregation
    extends Enum[StorageAggregation]
    with CirceEnum[StorageAggregation] {

  val values = findValues

  case object spackages extends StorageAggregation
  case object sdatasets extends StorageAggregation
  case object susers extends StorageAggregation
  case object sorganizations extends StorageAggregation
}
