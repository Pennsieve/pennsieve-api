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
