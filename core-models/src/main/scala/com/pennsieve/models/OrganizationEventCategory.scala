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
  * Data type used to filter organization event types. Each organization
  * event type belongs to one broad event category.
  *
  * See docs/changelog-events.md Part 2 ("Model layer") for why this has
  * two categories (unlike a flat vocabulary) -- ORGANIZATION for org/team
  * administration and workspace-scoped dataset-grant notices, PUBLISHING
  * for workspace-wide publish-lifecycle broadcasts.
  */
sealed trait OrganizationEventCategory extends EnumEntry with UpperSnakecase {
  self =>

  /**
    * Get all events types which belong to this category.
    */
  def eventTypes: List[OrganizationEventName] =
    OrganizationEventName.values
      .filter((eventType: OrganizationEventName) => eventType.category == self)
      .toList
}

object OrganizationEventCategory
    extends Enum[OrganizationEventCategory]
    with CirceEnum[OrganizationEventCategory] {

  val values: immutable.IndexedSeq[OrganizationEventCategory] = findValues

  case object ORGANIZATION extends OrganizationEventCategory
  case object PUBLISHING extends OrganizationEventCategory
}
