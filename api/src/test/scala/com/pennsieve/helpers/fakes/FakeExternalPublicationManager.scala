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
import com.pennsieve.domain.{ CoreError, NotFound }
import com.pennsieve.managers.ExternalPublicationManager
import com.pennsieve.models.{
  Dataset,
  Doi,
  ExternalPublication,
  Organization,
  RelationshipType
}
import com.pennsieve.traits.PostgresProfile.api.Database

import scala.concurrent.{ ExecutionContext, Future }

class FakeExternalPublicationManager(
  val state: InMemoryState,
  override val organization: Organization
) extends ExternalPublicationManager {

  def db: Database =
    sys.error(
      "FakeExternalPublicationManager: a method not yet stubbed by your test " +
        "tried to use the database. Override the method on this fake."
    )

  private def key(
    dataset: Dataset,
    doi: Doi,
    rel: RelationshipType
  ): (Int, Int, String) =
    (organization.id, dataset.id, s"${doi.value}|${rel.entryName}")

  override def get(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[ExternalPublication]] =
    EitherT.rightT(
      state.externalPublications
        .collect {
          case ((orgId, dsId, _), p)
              if orgId == organization.id && dsId == dataset.id =>
            p
        }
        .toSeq
        .sortBy(_.createdAt.toInstant)
    )

  override def createOrUpdate(
    dataset: Dataset,
    doi: Doi,
    relationshipType: RelationshipType
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, ExternalPublication] = {
    val k = key(dataset, doi, relationshipType)
    val now = InMemoryState.now()
    val updated = state.externalPublications.get(k) match {
      case Some(existing) => existing.copy(updatedAt = now)
      case None =>
        ExternalPublication(
          datasetId = dataset.id,
          doi = doi,
          relationshipType = relationshipType,
          createdAt = now,
          updatedAt = now
        )
    }
    state.externalPublications.put(k, updated)
    EitherT.rightT(updated)
  }

  override def delete(
    dataset: Dataset,
    doi: Doi,
    relationshipType: RelationshipType
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] = {
    val k = key(dataset, doi, relationshipType)
    state.externalPublications.remove(k) match {
      case Some(_) => EitherT.rightT(())
      case None =>
        EitherT.leftT(
          NotFound(
            s"External publication $doi with relationship '$relationshipType' for dataset ${dataset.id}"
          )
        )
    }
  }
}
