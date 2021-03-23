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

// Copyright (c) 2019 Pennsieve, Inc. All Rights Reserved.

package com.pennsieve.db

import com.pennsieve.dtos.PennsieveTermsOfServiceDTO
import com.pennsieve.traits.PostgresProfile.api._
import java.time.{ ZoneOffset, ZonedDateTime }

import scala.concurrent.ExecutionContext

case class PennsieveTermsOfService(
  userId: Int,
  acceptedVersion: ZonedDateTime,
  acceptedDate: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC)
) {
  def toDTO: PennsieveTermsOfServiceDTO =
    PennsieveTermsOfServiceDTO(acceptedVersion)
}

final class PennsieveTermsOfServiceTable(tag: Tag)
    extends Table[PennsieveTermsOfService](
      tag,
      Some("pennsieve"),
      "pennsieve_terms_of_service"
    ) {

  // set by the database
  def userId = column[Int]("user_id", O.PrimaryKey)
  def acceptedVersion = column[ZonedDateTime]("accepted_version")
  def acceptedDate = column[ZonedDateTime]("accepted_date")

  def * =
    (userId, acceptedVersion, acceptedDate).mapTo[PennsieveTermsOfService]
}

object PennsieveTermsOfServiceMapper
    extends TableQuery(new PennsieveTermsOfServiceTable(_)) {

  def get(userId: Int) =
    this
      .filter(_.userId === userId)
      .result
      .headOption

  def getAll(userIds: Seq[Int]) =
    this
      .filter(_.userId inSetBind userIds)
      .result
}
