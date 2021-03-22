// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.db

import com.blackfynn.traits.PostgresProfile.api._
import com.blackfynn.models.{ Subscription, SubscriptionStatus }
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
