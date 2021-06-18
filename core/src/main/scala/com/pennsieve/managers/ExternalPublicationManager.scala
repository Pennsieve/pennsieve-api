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

import cats.data._
import cats.implicits._
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.db._
import com.pennsieve.domain._
import com.pennsieve.models._
import com.pennsieve.traits.PostgresProfile.api._
import scala.concurrent.{ ExecutionContext, Future }

class ExternalPublicationManager(
  val db: Database,
  val organization: Organization
) {
  val externalPublicationMapper = new ExternalPublicationMapper(organization)

  def get(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[ExternalPublication]] =
    db.run(externalPublicationMapper.get(dataset).sortBy(_.createdAt).result)
      .toEitherT

  def createOrUpdate(
    dataset: Dataset,
    doi: Doi,
    relationshipType: RelationshipType
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, ExternalPublication] =
    db.run(
        externalPublicationMapper.createOrUpdate(dataset, doi, relationshipType)
      )
      .toEitherT

  def delete(
    dataset: Dataset,
    doi: Doi,
    relationshipType: RelationshipType
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] =
    db.run(externalPublicationMapper.delete(dataset, doi, relationshipType))
      .toEitherT
      .subflatMap {
        case 0 =>
          Left(
            NotFound(
              s"External publication $doi with relationship '$relationshipType' for dataset ${dataset.id}"
            )
          )
        case _ => Right(())
      }
}
