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
import io.circe.generic.extras.semiauto.{ deriveDecoder, deriveEncoder }
import java.util.UUID
import scala.collection.immutable

sealed trait ChangelogEventName extends EnumEntry with UpperSnakecase {
  val category: ChangelogEventCategory
}

object ChangelogEventName
    extends Enum[ChangelogEventName]
    with CirceEnum[ChangelogEventName] {

  val values: immutable.IndexedSeq[ChangelogEventName] = findValues

  import ChangelogEventCategory._

  // DATASET

  case object CREATE_DATASET extends ChangelogEventName {
    val category = DATASET
  }
  case object UPDATE_METADATA extends ChangelogEventName {
    val category = DATASET
  }
  case object UPDATE_NAME extends ChangelogEventName {
    val category = DATASET
  }
  case object UPDATE_DESCRIPTION extends ChangelogEventName {
    val category = DATASET
  }
  case object UPDATE_LICENSE extends ChangelogEventName {
    val category = DATASET
  }
  case object ADD_TAG extends ChangelogEventName {
    val category = DATASET
  }
  case object REMOVE_TAG extends ChangelogEventName {
    val category = DATASET
  }
  case object UPDATE_README extends ChangelogEventName {
    val category = DATASET
  }
  case object UPDATE_BANNER_IMAGE extends ChangelogEventName {
    val category = DATASET
  }
  case object ADD_COLLECTION extends ChangelogEventName {
    val category = DATASET
  }
  case object REMOVE_COLLECTION extends ChangelogEventName {
    val category = DATASET
  }
  case object ADD_CONTRIBUTOR extends ChangelogEventName {
    val category = DATASET
  }
  case object REMOVE_CONTRIBUTOR extends ChangelogEventName {
    val category = DATASET
  }
  case object ADD_EXTERNAL_PUBLICATION extends ChangelogEventName {
    val category = DATASET
  }
  case object REMOVE_EXTERNAL_PUBLICATION extends ChangelogEventName {
    val category = DATASET
  }
  case object UPDATE_IGNORE_FILES extends ChangelogEventName {
    val category = DATASET
  }
  case object UPDATE_STATUS extends ChangelogEventName {
    val category = DATASET
  }

  // PERMISSIONS

  case object UPDATE_PERMISSION extends ChangelogEventName {
    val category = PERMISSIONS
  }
  case object UPDATE_OWNER extends ChangelogEventName {
    val category = PERMISSIONS
  }

  // PACKAGES

  case object CREATE_PACKAGE extends ChangelogEventName {
    val category = PACKAGES
  }
  case object RENAME_PACKAGE extends ChangelogEventName {
    val category = PACKAGES
  }
  case object MOVE_PACKAGE extends ChangelogEventName {
    val category = PACKAGES
  }
  case object DELETE_PACKAGE extends ChangelogEventName {
    val category = PACKAGES
  }

  // MODELS, RECORDS

  case object CREATE_MODEL extends ChangelogEventName {
    val category = MODELS_AND_RECORDS
  }
  case object UPDATE_MODEL extends ChangelogEventName {
    val category = MODELS_AND_RECORDS
  }
  case object DELETE_MODEL extends ChangelogEventName {
    val category = MODELS_AND_RECORDS
  }

  case object CREATE_MODEL_PROPERTY extends ChangelogEventName {
    val category = MODELS_AND_RECORDS
  }
  case object UPDATE_MODEL_PROPERTY extends ChangelogEventName {
    val category = MODELS_AND_RECORDS
  }
  case object DELETE_MODEL_PROPERTY extends ChangelogEventName {
    val category = MODELS_AND_RECORDS
  }

  case object CREATE_RECORD extends ChangelogEventName {
    val category = MODELS_AND_RECORDS
  }
  case object UPDATE_RECORD extends ChangelogEventName {
    val category = MODELS_AND_RECORDS
  }
  case object DELETE_RECORD extends ChangelogEventName {
    val category = MODELS_AND_RECORDS
  }

  // PUBLISHING

  case object REQUEST_PUBLICATION extends ChangelogEventName {
    val category = PUBLISHING
  }
  case object ACCEPT_PUBLICATION extends ChangelogEventName {
    val category = PUBLISHING
  }
  case object REJECT_PUBLICATION extends ChangelogEventName {
    val category = PUBLISHING
  }
  case object CANCEL_PUBLICATION extends ChangelogEventName {
    val category = PUBLISHING
  }

  case object REQUEST_EMBARGO extends ChangelogEventName {
    val category = PUBLISHING
  }
  case object ACCEPT_EMBARGO extends ChangelogEventName {
    val category = PUBLISHING
  }
  case object REJECT_EMBARGO extends ChangelogEventName {
    val category = PUBLISHING
  }
  case object CANCEL_EMBARGO extends ChangelogEventName {
    val category = PUBLISHING
  }
  case object RELEASE_EMBARGO extends ChangelogEventName {
    val category = PUBLISHING
  }

  case object REQUEST_REMOVAL extends ChangelogEventName {
    val category = PUBLISHING
  }
  case object ACCEPT_REMOVAL extends ChangelogEventName {
    val category = PUBLISHING
  }
  case object REJECT_REMOVAL extends ChangelogEventName {
    val category = PUBLISHING
  }
  case object CANCEL_REMOVAL extends ChangelogEventName {
    val category = PUBLISHING
  }

  case object REQUEST_REVISION extends ChangelogEventName {
    val category = PUBLISHING
  }
  case object ACCEPT_REVISION extends ChangelogEventName {
    val category = PUBLISHING
  }
  case object REJECT_REVISION extends ChangelogEventName {
    val category = PUBLISHING
  }
  case object CANCEL_REVISION extends ChangelogEventName {
    val category = PUBLISHING
  }
}
