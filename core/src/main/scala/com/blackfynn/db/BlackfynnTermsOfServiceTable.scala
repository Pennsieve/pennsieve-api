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
