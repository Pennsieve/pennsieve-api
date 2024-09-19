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

package com.pennsieve.dtos

import java.time.ZonedDateTime

import com.pennsieve.models.SubscriptionStatus.{
  ConfirmedSubscription => Confirmed,
  PendingSubscription => Pending
}
import com.pennsieve.models.{ Feature, OrganizationCustomization, Subscription }

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
  customization: Option[OrganizationCustomization],
  customTermsOfService: Option[CustomTermsOfServiceDTO],
  intId: Int
)
