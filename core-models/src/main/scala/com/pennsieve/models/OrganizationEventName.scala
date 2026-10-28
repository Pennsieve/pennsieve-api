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
  * See docs/changelog-events.md Part 2 for the full design (pennsieve-api#405).
  *
  * This is the vocabulary for `organization_events` -- a parallel capability
  * to the dataset-scoped `ChangelogEventName`/`ChangelogEventDetail`, not an
  * extension of it. Implemented: Parts 1+2 (org/team administration, and
  * workspace-scoped notice of a personal dataset grant) and Part 4
  * (workspace-wide publishing-lifecycle broadcasts). Part 3 (Publishers-
  * gated workspace-wide visibility) remains deferred -- its v1 scope was
  * absorbed into Part 4's plain org-membership gate, so no Publishers-team
  * gate or wildcard-subscription mechanism is needed yet; see the doc's
  * Part 3 scope note.
  */
sealed trait OrganizationEventName extends EnumEntry with UpperSnakecase {
  val category: OrganizationEventCategory
}

object OrganizationEventName
    extends Enum[OrganizationEventName]
    with CirceEnum[OrganizationEventName] {

  val values: immutable.IndexedSeq[OrganizationEventName] = findValues

  import OrganizationEventCategory._

  // Part 1: organization/team administration

  case object ADD_ORGANIZATION_USER extends OrganizationEventName {
    val category = ORGANIZATION
  }
  case object ADD_EXTERNAL_ORGANIZATION_USER extends OrganizationEventName {
    val category = ORGANIZATION
  }
  case object REMOVE_ORGANIZATION_USER extends OrganizationEventName {
    val category = ORGANIZATION
  }
  case object UPDATE_ORGANIZATION_PERMISSION extends OrganizationEventName {
    val category = ORGANIZATION
  }
  case object ADD_TEAM_USER extends OrganizationEventName {
    val category = ORGANIZATION
  }
  case object REMOVE_TEAM_USER extends OrganizationEventName {
    val category = ORGANIZATION
  }
  case object CREATE_TEAM extends OrganizationEventName {
    val category = ORGANIZATION
  }
  case object UPDATE_TEAM extends OrganizationEventName {
    val category = ORGANIZATION
  }
  case object DELETE_TEAM extends OrganizationEventName {
    val category = ORGANIZATION
  }

  // Part 2: workspace-scoped notice of a personal dataset grant

  case object ADDED_TO_DATASET extends OrganizationEventName {
    val category = ORGANIZATION
  }
  case object REMOVED_FROM_DATASET extends OrganizationEventName {
    val category = ORGANIZATION
  }
  case object DATASET_OWNERSHIP_TRANSFERRED extends OrganizationEventName {
    val category = ORGANIZATION
  }

  // Part 4: workspace-wide publishing-lifecycle broadcasts

  case object DATASET_PUBLISHED extends OrganizationEventName {
    val category = PUBLISHING
  }
  case object DATASET_EMBARGOED extends OrganizationEventName {
    val category = PUBLISHING
  }
  case object DATASET_EMBARGO_RELEASED extends OrganizationEventName {
    val category = PUBLISHING
  }
  case object DATASET_UNPUBLISHED extends OrganizationEventName {
    val category = PUBLISHING
  }
  case object DATASET_REVISED extends OrganizationEventName {
    val category = PUBLISHING
  }
}
