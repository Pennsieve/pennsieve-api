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

import scala.collection.immutable

sealed trait PackageState extends EnumEntry with EnumEntry.UpperSnakecase {

  import PackageState._

  /**
    * Serialize new failure states to ERROR. The frontend only understands
    * ERROR.
    *
    * TODO: remove this once the frontend can handle UPLOAD_FAILED and
    * PROCESSING_FAILED.
    */
  def asDTO: PackageState =
    this match {
      case UPLOAD_FAILED | PROCESSING_FAILED => ERROR
      case state => state
    }
}

sealed trait PackageUploadState extends PackageState

sealed trait PackageProcessingState extends PackageState

object PackageState extends Enum[PackageState] with CirceEnum[PackageState] {

  val values: immutable.IndexedSeq[PackageState] = findValues

  // Backwards-compatible deserialization of legacy error state
  // TODO: database migration and remove this entirely once JSS
  // only sets ERROR
  override lazy val namesToValuesMap: Map[String, PackageState] =
    values.map(v => v.entryName -> v).toMap + (
      "FAILED" -> PackageState.ERROR
    )

  case object UNAVAILABLE extends PackageState with PackageUploadState
  case object UPLOADED extends PackageState with PackageUploadState
  case object DELETING extends PackageState with PackageUploadState
  case object INFECTED extends PackageState with PackageUploadState
  case object UPLOAD_FAILED extends PackageState with PackageUploadState

  case object PROCESSING extends PackageState with PackageProcessingState
  case object READY extends PackageState with PackageProcessingState
  case object PROCESSING_FAILED extends PackageState with PackageProcessingState

  /**
    * Legacy error state. Confusingly used for both upload and processing
    * failures in different scenarios.
    */
  @deprecated("Use UPLOAD_FAILED or PROCESSING_FAILED")
  case object ERROR
      extends PackageState
      with PackageUploadState
      with PackageProcessingState
}
