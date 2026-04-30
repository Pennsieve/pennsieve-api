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

package com.pennsieve.helpers.fakes

import cats.data.EitherT
import com.pennsieve.db.DatasetsMapper
import com.pennsieve.domain.{ CoreError, PredicateError }
import com.pennsieve.managers.DatasetPreviewManager
import com.pennsieve.models.{
  DataUseAgreement,
  Dataset,
  DatasetPreviewer,
  EmbargoAccess,
  Organization,
  User
}
import com.pennsieve.traits.PostgresProfile.api.Database

import scala.concurrent.{ ExecutionContext, Future }

class FakeDatasetPreviewManager(val state: InMemoryState, org: Organization)
    extends DatasetPreviewManager {

  def db: Database =
    sys.error(
      "FakeDatasetPreviewManager: a method not yet stubbed by your test " +
        "tried to use the database. Override the method on this fake."
    )

  override lazy val datasetsMapper: DatasetsMapper =
    new DatasetsMapper(org)

  override def getPreviewers(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[(DatasetPreviewer, User)]] = {
    val rows = state.datasetPreviewers
      .collect {
        case ((orgId, dsId, uid), p) if orgId == org.id && dsId == dataset.id =>
          state.users.get(uid).map(u => (p, u))
      }
      .flatten
      .toSeq
    EitherT.rightT(rows)
  }

  override def forUser(
    user: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[DatasetPreviewer]] = {
    val rows = state.datasetPreviewers.collect {
      case ((orgId, _, uid), p) if orgId == org.id && uid == user.id => p
    }.toSeq
    EitherT.rightT(rows)
  }

  override def grantAccess(
    dataset: Dataset,
    user: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] = {
    val key = (org.id, dataset.id, user.id)
    val existing = state.datasetPreviewers.get(key)
    val agreementId = existing.flatMap(_.dataUseAgreementId)
    state.datasetPreviewers
      .put(
        key,
        DatasetPreviewer(
          dataset.id,
          user.id,
          EmbargoAccess.Granted,
          agreementId
        )
      )
    EitherT.rightT(())
  }

  override def requestAccess(
    dataset: Dataset,
    user: User,
    agreement: Option[DataUseAgreement]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] = {
    val key = (org.id, dataset.id, user.id)
    val current = state.datasetPreviewers.get(key)
    val canPreview = current.exists(_.embargoAccess == EmbargoAccess.Granted)
    if (canPreview)
      EitherT.leftT(
        PredicateError("Access has already been granted"): CoreError
      )
    else {
      state.datasetPreviewers.put(
        key,
        DatasetPreviewer(
          dataset.id,
          user.id,
          EmbargoAccess.Requested,
          agreement.map(_.id)
        )
      )
      EitherT.rightT(())
    }
  }

  override def removeAccess(
    dataset: Dataset,
    user: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Option[DatasetPreviewer]] = {
    val key = (org.id, dataset.id, user.id)
    val removed = state.datasetPreviewers.remove(key)
    EitherT.rightT(removed)
  }
}
