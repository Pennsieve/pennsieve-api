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

sealed trait Icon extends EnumEntry

object Icon extends Enum[Icon] with CirceEnum[Icon] {
  val values = findValues

  case object AdobeIllustrator extends Icon
  case object ClinicalImageBrain extends Icon
  case object Code extends Icon
  case object Docker extends Icon
  case object Excel extends Icon
  case object Flow extends Icon
  case object Generic extends Icon
  case object GenericData extends Icon
  case object Genomics extends Icon
  case object GenomicsVariant extends Icon
  case object HDF extends Icon
  case object Image extends Icon
  case object JSON extends Icon
  case object Matlab extends Icon
  case object Microscope extends Icon
  case object Model extends Icon
  case object Notebook extends Icon
  case object NWB extends Icon
  case object PDF extends Icon
  case object PowerPoint extends Icon
  case object RData extends Icon
  case object Tabular extends Icon
  case object Text extends Icon
  case object Timeseries extends Icon
  case object Video extends Icon
  case object Word extends Icon
  case object XML extends Icon
  case object Zip extends Icon

}
