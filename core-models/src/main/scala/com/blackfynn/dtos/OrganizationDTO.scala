// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.dtos

import java.time.ZonedDateTime

import com.pennsieve.models.SubscriptionStatus.{
  ConfirmedSubscription => Confirmed,
  PendingSubscription => Pending
}
import com.pennsieve.models.{ Feature, Subscription }

sealed trait SubscriptionDTO
case class PendingSubscription(`type`: Option[String] = None)
    extends SubscriptionDTO
case class ConfirmedSubscription(
  date: ZonedDateTime,
  `type`: Option[String] = None
) extends SubscriptionDTO

object SubscriptionDTO {
  def apply(subscription: Subscription): SubscriptionDTO = {
    subscription.status match {
      case Pending => PendingSubscription(subscription.`type`)
      case Confirmed =>
        ConfirmedSubscription(subscription.createdAt, subscription.`type`)
    }
  }
}

case class OrganizationDTO(
  id: String,
  name: String,
  slug: String,
  subscriptionState: SubscriptionDTO,
  encryptionKeyId: String,
  terms: Option[String],
  features: Set[Feature],
  storage: Option[Long],
  customTermsOfService: Option[CustomTermsOfServiceDTO],
  intId: Int
)
