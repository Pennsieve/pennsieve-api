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

import enumeratum.EnumEntry.UpperSnakecase
import enumeratum._

sealed trait IntegrationTarget extends EnumEntry with UpperSnakecase

object IntegrationTarget
    extends Enum[IntegrationTarget]
    with CirceEnum[IntegrationTarget] {
  val values: IndexedSeq[IntegrationTarget] = findValues

  case object Package extends IntegrationTarget
  case object Packages extends IntegrationTarget
  case object Record extends IntegrationTarget
  case object Records extends IntegrationTarget
  case object Dataset extends IntegrationTarget

}
