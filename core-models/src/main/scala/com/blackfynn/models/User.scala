// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.models

import java.time.ZonedDateTime
import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.java8.time._

final case class User(
  nodeId: String,
  email: String,
  firstName: String,
  middleInitial: Option[String],
  lastName: String,
  degree: Option[Degree],
  password: String,
  credential: String = "",
  color: String = "",
  url: String = "",
  authyId: Int = 0,
  isSuperAdmin: Boolean = false,
  preferredOrganizationId: Option[Int] = None,
  status: Boolean = true,
  orcidAuthorization: Option[OrcidAuthorization] = None,
  updatedAt: ZonedDateTime = ZonedDateTime.now(),
  createdAt: ZonedDateTime = ZonedDateTime.now(),
  id: Int = 0
) {
  def hasTwoFactorConfigured: Boolean = authyId != 0
  def fullName: String = s"$firstName $lastName".trim
}

object User {
  def serviceUser(): User =
    User(
      nodeId = "",
      email = "pennsieve.main@gmail.com",
      firstName = "Pennsieve",
      middleInitial = None,
      lastName = "Pennsieve",
      degree = None,
      password = "",
      isSuperAdmin = true
    )

  implicit val encoder: Encoder[User] = deriveEncoder[User]
  implicit val decoder: Decoder[User] = deriveDecoder[User]

  /*
   * This is required by slick when using a companion object on a case
   * class that defines a database table
   */
  val tupled = (this.apply _).tupled
}
