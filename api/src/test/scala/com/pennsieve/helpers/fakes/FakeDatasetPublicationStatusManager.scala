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
import com.pennsieve.db.{ ChangelogEventMapper, DatasetPublicationStatusMapper }
import com.pennsieve.domain.CoreError
import com.pennsieve.managers.DatasetPublicationStatusManager
import com.pennsieve.models.{
  Dataset,
  DatasetPublicationStatus,
  Organization,
  PublicationStatus,
  PublicationType,
  User
}
import com.pennsieve.traits.PostgresProfile.api.Database

import java.time.LocalDate
import scala.concurrent.{ ExecutionContext, Future }

class FakeDatasetPublicationStatusManager(
  val state: InMemoryState,
  organization: Organization,
  val actor: User
) extends DatasetPublicationStatusManager {

  def db: Database =
    sys.error(
      "FakeDatasetPublicationStatusManager: a method not yet stubbed by " +
        "your test tried to use the database. Override the method on this fake."
    )

  override lazy val datasetPublicationStatusMapper
    : DatasetPublicationStatusMapper =
    new DatasetPublicationStatusMapper(organization)
  override lazy val changelogEventMapper: ChangelogEventMapper =
    new ChangelogEventMapper(organization)

  private def statusesForDataset(
    datasetId: Int
  ): Seq[DatasetPublicationStatus] =
    state.datasetPublicationStatuses
      .collect {
        case ((orgId, _), s)
            if orgId == organization.id && s.datasetId == datasetId =>
          s
      }
      .toSeq
      .sortBy(_.id)

  override def getLatestByDataset(
    datasetId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Option[DatasetPublicationStatus]] =
    EitherT.rightT(statusesForDataset(datasetId).reverse.headOption)

  override def getLogByDataset(
    datasetId: Int,
    sortAscending: Boolean = false
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[DatasetPublicationStatus]] = {
    val ordered = statusesForDataset(datasetId)
    EitherT.rightT(if (sortAscending) ordered else ordered.reverse)
  }

  override def create(
    dataset: Dataset,
    publicationStatus: PublicationStatus,
    publicationType: PublicationType,
    comments: Option[String] = None,
    embargoReleaseDate: Option[LocalDate] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DatasetPublicationStatus] = {
    val id = state.newId()
    val row = DatasetPublicationStatus(
      datasetId = dataset.id,
      publicationStatus = publicationStatus,
      publicationType = publicationType,
      createdBy = if (actor.id == 0) None else Some(actor.id),
      comments = comments,
      embargoReleaseDate = embargoReleaseDate,
      id = id
    )
    state.datasetPublicationStatuses.put((organization.id, id), row)
    EitherT.rightT(row)
  }
}
