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

package com.pennsieve.managers

import cats.implicits._
import cats.data.EitherT
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.core.utilities.checkOrErrorT
import com.pennsieve.db._
import com.pennsieve.domain.{
  CoreError,
  ExceptionError,
  NotFound,
  PredicateError
}
import com.pennsieve.models._
import com.pennsieve.traits.PostgresProfile.api._

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
