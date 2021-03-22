// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.models

import java.time.ZonedDateTime

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.java8.time._

final case class UserInvite(
  nodeId: String,
  organizationId: Int,
  email: String,
  firstName: String,
  lastName: String,
  permission: DBPermission,
  cognitoId: CognitoId,
  validUntil: ZonedDateTime = ZonedDateTime.now(),
  createdAt: ZonedDateTime = ZonedDateTime.now(),
  updatedAt: ZonedDateTime = ZonedDateTime.now(),
  id: Int = 0
)

object UserInvite {
  implicit val encoder: Encoder[UserInvite] = deriveEncoder[UserInvite]
  implicit val decoder: Decoder[UserInvite] = deriveDecoder[UserInvite]

  /*
   * This is required by slick when using a companion object on a case
   * class that defines a database table
   */
  val tupled = (this.apply _).tupled
}
