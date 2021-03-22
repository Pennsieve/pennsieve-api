// Copyright (c) 2019 Pennsieve, Inc. All Rights Reserved.

package com.blackfynn.managers

import cats.data.EitherT
import com.blackfynn.core.utilities.FutureEitherHelpers.implicits._
import com.blackfynn.db.{
  PennsieveTermsOfService,
  PennsieveTermsOfServiceMapper
}
import com.blackfynn.traits.PostgresProfile.api._
import java.time.ZonedDateTime

import com.blackfynn.domain.CoreError

import scala.concurrent.{ ExecutionContext, Future }

class PennsieveTermsOfServiceManager(db: Database) {
  def get(
    userId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Option[PennsieveTermsOfService]] =
    db.run(PennsieveTermsOfServiceMapper.get(userId)).toEitherT

  def getUserMap(
    userIds: Seq[Int]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Map[Int, PennsieveTermsOfService]] =
    db.run(PennsieveTermsOfServiceMapper.getAll(userIds))
      .map(_.map(tos => tos.userId -> tos).toMap)
      .toEitherT

  def setNewVersion(
    userId: Int,
    version: ZonedDateTime
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, PennsieveTermsOfService] = {
    val newTerms =
      PennsieveTermsOfService(userId = userId, acceptedVersion = version)
    db.run(
        PennsieveTermsOfServiceMapper
          .insertOrUpdate(newTerms)
      )
      .map(_ => newTerms)
      .toEitherT
  }
}
