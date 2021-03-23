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

package com.pennsieve.db

import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.models.{ Subscription, SubscriptionStatus }
import java.time.ZonedDateTime

final class SubscriptionsTable(tag: Tag)
    extends Table[Subscription](tag, Some("pennsieve"), "subscriptions") {

  def organizationId = column[Int]("organization_id")
  def status = column[SubscriptionStatus]("status")
  def `type` = column[Option[String]]("type")
  def createdAt =
    column[ZonedDateTime]("created_at", O.AutoInc) // set by the database on insert
  def updatedAt =
    column[ZonedDateTime]("updated_at", O.AutoInc) // set by the database on update

  def acceptedBy = column[Option[String]]("accepted_by")
  def acceptedForOrganization =
    column[Option[String]]("accepted_for_organization")
  def acceptedByUser = column[Option[Int]]("accepted_by_user")

  def * =
    (
      organizationId,
      status,
      `type`,
      acceptedBy,
      acceptedForOrganization,
      acceptedByUser,
      createdAt,
      updatedAt
    ).mapTo[Subscription]
}

object SubscriptionsMapper extends TableQuery(new SubscriptionsTable(_))
