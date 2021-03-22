// Copyright (c) 2019 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.db

import com.blackfynn.dtos.CustomTermsOfServiceDTO
import com.blackfynn.traits.PostgresProfile.api._
import java.time.{ ZoneOffset, ZonedDateTime }

import scala.concurrent.ExecutionContext

case class CustomTermsOfService(
  userId: Int,
  organizationId: Int,
  acceptedVersion: ZonedDateTime,
  acceptedDate: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC)
) {
  def toDTO(organizationNodeId: String): CustomTermsOfServiceDTO =
    CustomTermsOfServiceDTO(acceptedVersion, organizationNodeId)
}

final class CustomTermsOfServiceTable(tag: Tag)
    extends Table[CustomTermsOfService](
      tag,
      Some("pennsieve"),
      "custom_terms_of_service"
    ) {

  // set by the database
  def userId = column[Int]("user_id", O.PrimaryKey)
  def organizationId = column[Int]("organization_id", O.PrimaryKey)

  def acceptedVersion = column[ZonedDateTime]("accepted_version")
  def acceptedDate = column[ZonedDateTime]("accepted_date")

  def * =
    (userId, organizationId, acceptedVersion, acceptedDate)
      .mapTo[CustomTermsOfService]
}

object CustomTermsOfServiceMapper
    extends TableQuery(new CustomTermsOfServiceTable(_)) {

  def get(
    userId: Int,
    organizationId: Int
  ): DBIO[Option[CustomTermsOfService]] =
    CustomTermsOfServiceMapper
      .filter(_.userId === userId)
      .filter(_.organizationId === organizationId)
      .result
      .headOption

  def getAll(userId: Int): DBIO[Seq[CustomTermsOfService]] =
    CustomTermsOfServiceMapper.filter(_.userId === userId).result

  def getAllUsers(userIds: Seq[Int], organizationId: Int) =
    CustomTermsOfServiceMapper
      .filter(
        row =>
          row.userId.inSetBind(userIds) && row.organizationId === organizationId
      )
      .result

}
