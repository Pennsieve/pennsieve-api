package com.pennsieve.models

import enumeratum.{ CirceEnum, Enum, EnumEntry }

import scala.collection.immutable.IndexedSeq

sealed trait License extends EnumEntry

object License extends Enum[License] with CirceEnum[License] {
  val values: IndexedSeq[License] = findValues

  case object `Apache 2.0` extends License
  case object `Community Data License Agreement – Permissive` extends License
  case object `Community Data License Agreement – Sharing` extends License
  case object `Creative Commons Zero 1.0 Universal` extends License
  case object `Creative Commons Attribution` extends License
  case object `Creative Commons Attribution - ShareAlike` extends License
  case object `GNU General Public License v3.0` extends License
  case object `GNU Lesser General Public License` extends License
  case object `MIT` extends License
  case object `Mozilla Public License 2.0` extends License
  case object `Open Data Commons Open Database` extends License
  case object `Open Data Commons Attribution` extends License
  case object `Open Data Commons Public Domain Dedication and License`
      extends License

  val licenseUri: Map[License, String] = Map(
    License.`Apache 2.0` -> "https://spdx.org/licenses/Apache-2.0.json",
    License.`Community Data License Agreement – Permissive` -> "https://spdx.org/licenses/CDLA-Permissive-1.0.json",
    License.`Community Data License Agreement – Sharing` -> "https://spdx.org/licenses/CDLA-Sharing-1.0.json",
    License.`Creative Commons Zero 1.0 Universal` -> "https://spdx.org/licenses/CC0-1.0.json",
    License.`Creative Commons Attribution` -> "https://spdx.org/licenses/CC-BY-4.0.json",
    License.`Creative Commons Attribution - ShareAlike` -> "https://spdx.org/licenses/CC-BY-SA-4.0.json",
    License.`GNU General Public License v3.0` -> "https://spdx.org/licenses/GPL-3.0-only.json",
    License.`GNU Lesser General Public License` -> "https://spdx.org/licenses/LGPL-3.0-only.json",
    License.`MIT` -> "https://spdx.org/licenses/MIT.json",
    License.`Mozilla Public License 2.0` -> "https://spdx.org/licenses/MPL-2.0.json",
    License.`Open Data Commons Open Database` -> "https://spdx.org/licenses/ODbL-1.0.json",
    License.`Open Data Commons Attribution` -> "https://spdx.org/licenses/ODC-By-1.0.json",
    License.`Open Data Commons Public Domain Dedication and License` -> "https://spdx.org/licenses/PDDL-1.0.json"
  )
}
