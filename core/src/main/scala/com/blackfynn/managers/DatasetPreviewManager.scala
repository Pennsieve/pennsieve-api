// Copyright (c) 2020 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.managers

import cats.implicits._
import cats.data.EitherT
import com.blackfynn.core.utilities.FutureEitherHelpers.implicits._
import com.blackfynn.core.utilities.checkOrErrorT
import com.blackfynn.db._
import com.blackfynn.domain.{
  CoreError,
  ExceptionError,
  NotFound,
  PredicateError
}
import com.blackfynn.models._
import com.blackfynn.traits.PostgresProfile.api._

import scala.concurrent.{ ExecutionContext, Future }

class DatasetPreviewManager(
  val db: Database,
  val datasetsMapper: DatasetsMapper
) {

  val organization: Organization = datasetsMapper.organization

  val previewer: DatasetPreviewerMapper = new DatasetPreviewerMapper(
    organization
  )

  def getPreviewers(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[(DatasetPreviewer, User)]] =
    db.run(previewer.getPreviewers(dataset).result).toEitherT

  def forUser(
    user: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[DatasetPreviewer]] =
    db.run(previewer.filter(_.userId === user.id).result).toEitherT

  def grantAccess(
    dataset: Dataset,
    user: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] = {

    val query = for {
      maybePreviewer <- previewer
        .getByDatasetIdAndUserId(dataset.id, user.id)

      _ <- maybePreviewer match {
        case Some(p) =>
          previewer
            .filter(_.userId === user.id)
            .filter(_.datasetId === dataset.id)
            .map(_.embargoAccess)
            .update(EmbargoAccess.Granted)

        case None =>
          previewer +=
            DatasetPreviewer(dataset.id, user.id, EmbargoAccess.Granted, None)
      }

    } yield ()

    db.run(query.transactionally).toEitherT
  }

  /**
    * Request access to an embargoed dataset.
    */
  def requestAccess(
    dataset: Dataset,
    user: User,
    agreement: Option[DataUseAgreement]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] = {

    val query = for {

      canPreview <- previewer.canPreview(
        datasetId = dataset.id,
        userId = user.id
      )

      _ <- assert(!canPreview)(
        PredicateError("Access has already been granted")
      )

      _ <- previewer.insertOrUpdate(
        DatasetPreviewer(
          dataset.id,
          user.id,
          EmbargoAccess.Requested,
          agreement.map(_.id)
        )
      )

    } yield ()

    db.run(query.transactionally).toEitherT
  }

  def removeAccess(
    dataset: Dataset,
    user: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Option[DatasetPreviewer]] = {
    val query = for {
      toRemove <- previewer.getByDatasetIdAndUserId(
        datasetId = dataset.id,
        userId = user.id
      )
      _ <- previewer.removeAccess(dataset.id, user.id)
    } yield toRemove

    db.run(query.transactionally).toEitherT
  }
}
