// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.models

import enumeratum._
import java.time.ZonedDateTime
import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.java8.time._

final case class Subscription(
  organizationId: Int,
  status: SubscriptionStatus,
  `type`: Option[String] = None,
  acceptedBy: Option[String] = None,
  acceptedForOrganization: Option[String] = None,
  acceptedByUser: Option[Int] = None,
  createdAt: ZonedDateTime = ZonedDateTime.now(),
  updatedAt: ZonedDateTime = ZonedDateTime.now()
)

object Subscription {
  implicit val encoder: Encoder[Subscription] = deriveEncoder[Subscription]
  implicit val decoder: Decoder[Subscription] = deriveDecoder[Subscription]

  /*
   * This is required by slick when using a companion object on a case
   * class that defines a database table
   */
  val tupled = (this.apply _).tupled
}

sealed trait SubscriptionStatus extends EnumEntry

object SubscriptionStatus
    extends Enum[SubscriptionStatus]
    with CirceEnum[SubscriptionStatus] {
  val values = findValues

  case object PendingSubscription extends SubscriptionStatus
  case object ConfirmedSubscription extends SubscriptionStatus
}
