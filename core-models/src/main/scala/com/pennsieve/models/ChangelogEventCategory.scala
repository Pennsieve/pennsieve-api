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
import scala.collection.immutable

/**
  * Data type used to filter changelog event types. Each changelog event type
  * belongs to one broad event category.
  */
sealed trait ChangelogEventCategory extends EnumEntry with UpperSnakecase {
  self =>

  /**
    * Get all events types which belong to this category.
    */
  def eventTypes: List[ChangelogEventName] =
    ChangelogEventName.values
      .filter((eventType: ChangelogEventName) => eventType.category == self)
      .toList
}

object ChangelogEventCategory
    extends Enum[ChangelogEventCategory]
    with CirceEnum[ChangelogEventCategory] {

  val values: immutable.IndexedSeq[ChangelogEventCategory] = findValues

  case object DATASET extends ChangelogEventCategory
  case object PERMISSIONS extends ChangelogEventCategory
  case object PACKAGES extends ChangelogEventCategory
  case object MODELS_AND_RECORDS extends ChangelogEventCategory
  case object PUBLISHING extends ChangelogEventCategory
  case object CUSTOM extends ChangelogEventCategory
}
