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

import scala.collection.immutable.IndexedSeq

sealed trait License extends EnumEntry

object License extends Enum[License] with CirceEnum[License] {
  val values: IndexedSeq[License] = findValues

  case object `Apache 2.0` extends License
  case object `Apache License 2.0` extends License
  case object `BSD 2-Clause "Simplified" License` extends License
  case object `BSD 3-Clause "New" or "Revised" License` extends License
  case object `Boost Software License 1.0` extends License
  case object `Community Data License Agreement – Permissive` extends License
  case object `Community Data License Agreement – Sharing` extends License
  case object `Creative Commons Zero 1.0 Universal` extends License
  case object `Creative Commons Attribution` extends License
  case object `Creative Commons Attribution - ShareAlike` extends License
  case object `Creative Commons Attribution - NonCommercial-ShareAlike`
      extends License
  case object `Eclipse Public License 2.0` extends License
  case object `GNU Affero General Public License v3.0` extends License
  case object `GNU General Public License v2.0` extends License
  case object `GNU General Public License v3.0` extends License
  case object `GNU Lesser General Public License` extends License
  case object `GNU Lesser General Public License v2.1` extends License
  case object `GNU Lesser General Public License v3.0` extends License
  case object `MIT` extends License
  case object `MIT License` extends License
  case object `Mozilla Public License 2.0` extends License
  case object `Open Data Commons Open Database` extends License
  case object `Open Data Commons Attribution` extends License
  case object `Open Data Commons Public Domain Dedication and License`
      extends License
  case object `The Unlicense` extends License

  val licenseUri: Map[License, String] = Map(
    License.`Apache 2.0` -> "https://spdx.org/licenses/Apache-2.0.json",
    License.`Apache License 2.0` -> "https://spdx.org/licenses/Apache-2.0.html",
    License.`BSD 2-Clause "Simplified" License` -> "https://spdx.org/licenses/BSD-2-Clause.html",
    License.`BSD 3-Clause "New" or "Revised" License` -> "https://spdx.org/licenses/BSD-3-Clause.html",
    License.`Boost Software License 1.0` -> "https://spdx.org/licenses/BSL-1.0.html",
    License.`Community Data License Agreement – Permissive` -> "https://spdx.org/licenses/CDLA-Permissive-1.0.json",
    License.`Community Data License Agreement – Sharing` -> "https://spdx.org/licenses/CDLA-Sharing-1.0.json",
    License.`Creative Commons Zero 1.0 Universal` -> "https://spdx.org/licenses/CC0-1.0.json",
    License.`Creative Commons Attribution` -> "https://spdx.org/licenses/CC-BY-4.0.json",
    License.`Creative Commons Attribution - ShareAlike` -> "https://spdx.org/licenses/CC-BY-SA-4.0.json",
    License.`Creative Commons Attribution - NonCommercial-ShareAlike` -> "https://spdx.org/licenses/CC-BY-NC-SA-4.0.json",
    License.`Eclipse Public License 2.0` -> "https://spdx.org/licenses/EPL-2.0.html",
    License.`GNU Affero General Public License v3.0` -> "https://spdx.org/licenses/AGPL-3.0-only.html",
    License.`GNU General Public License v2.0` -> "https://spdx.org/licenses/GPL-2.0-only.html",
    License.`GNU General Public License v3.0` -> "https://spdx.org/licenses/GPL-3.0-only.json",
    License.`GNU Lesser General Public License` -> "https://spdx.org/licenses/LGPL-3.0-only.json",
    License.`GNU Lesser General Public License v2.1` -> "https://spdx.org/licenses/LGPL-2.1-only.html",
    License.`GNU Lesser General Public License v3.0` -> "https://spdx.org/licenses/LGPL-3.0-only.json",
    License.`MIT` -> "https://spdx.org/licenses/MIT.json",
    License.`MIT License` -> "https://spdx.org/licenses/MIT.html",
    License.`Mozilla Public License 2.0` -> "https://spdx.org/licenses/MPL-2.0.json",
    License.`Open Data Commons Open Database` -> "https://spdx.org/licenses/ODbL-1.0.json",
    License.`Open Data Commons Attribution` -> "https://spdx.org/licenses/ODC-By-1.0.json",
    License.`Open Data Commons Public Domain Dedication and License` -> "https://spdx.org/licenses/PDDL-1.0.json",
    License.`The Unlicense` -> "https://spdx.org/licenses/Unlicense.html"
  )
}
