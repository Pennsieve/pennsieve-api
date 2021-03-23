// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

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
