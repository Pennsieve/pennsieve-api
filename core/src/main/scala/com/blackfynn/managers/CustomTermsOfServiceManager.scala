// Copyright (c) 2019 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.managers

import java.time.{ ZoneOffset, ZonedDateTime }

import cats.implicits._
import cats.data.EitherT
import com.blackfynn.core.utilities.FutureEitherHelpers.implicits._
import com.blackfynn.db.{ CustomTermsOfService, CustomTermsOfServiceMapper }
import com.blackfynn.domain.{ CoreError, InvalidDateVersion }
import com.blackfynn.traits.PostgresProfile.api._
import com.blackfynn.models.DateVersion
import com.blackfynn.models.DateVersion._

import scala.concurrent.{ ExecutionContext, Future }

class CustomTermsOfServiceManager(db: Database) {
  def get(
    userId: Int,
    organizationId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Option[CustomTermsOfService]] =
    db.run(CustomTermsOfServiceMapper.get(userId, organizationId)).toEitherT

  def getAll(
    userId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[CustomTermsOfService]] =
    db.run(CustomTermsOfServiceMapper.getAll(userId)).toEitherT

  def getUserMap(
    userIds: Seq[Int],
    organizationId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Map[Int, Seq[CustomTermsOfService]]] =
    db.run(CustomTermsOfServiceMapper.getAllUsers(userIds, organizationId))
      .map(_.groupBy(_.userId))
      .toEitherT

  def accept(
    userId: Int,
    organizationId: Int,
    acceptedVersion: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, CustomTermsOfService] =
    for {
      validAcceptedVersion <- DateVersion
        .from(acceptedVersion)
        .leftMap(_ => InvalidDateVersion(acceptedVersion): CoreError)
        .toEitherT[Future]
      acceptedVersionAsDate = validAcceptedVersion.toZonedDateTime
      acceptedCustomToS = CustomTermsOfService(
        userId = userId,
        organizationId = organizationId,
        acceptedVersion = acceptedVersionAsDate,
        acceptedDate = ZonedDateTime.now(ZoneOffset.UTC)
      )
      _ <- db
        .run(CustomTermsOfServiceMapper.insertOrUpdate(acceptedCustomToS))
        .toEitherT
    } yield acceptedCustomToS
}
