// Copyright (c) 2020 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.managers

import cats.data._
import cats.implicits._
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.db._
import com.pennsieve.models._
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.domain.{ CoreError, NotFound }
import com.rms.miu.slickcats.DBIOInstances._
import slick.dbio.DBIO
import slick.lifted.ColumnOrdered

import scala.concurrent.{ ExecutionContext, Future }
import java.time.LocalDate

class DatasetPublicationStatusManager(
  val db: Database,
  val actor: User,
  val datasetPublicationStatusMapper: DatasetPublicationStatusMapper,
  val changelogEventMapper: ChangelogEventMapper
) {

  def get(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DatasetPublicationStatus] = {
    db.run(datasetPublicationStatusMapper.get(id).result.headOption)
      .whenNone(NotFound(s"DatasetPublicationStatus ($id)"))
  }

  def getPublicationStatus(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Option[DatasetPublicationStatus]] = {
    db.run(datasetPublicationStatusMapper.get(id).result.headOption).toEitherT
  }

  def create(
    dataset: Dataset,
    publicationStatus: PublicationStatus,
    publicationType: PublicationType,
    comments: Option[String] = None,
    embargoReleaseDate: Option[LocalDate] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DatasetPublicationStatus] = {

    val row = DatasetPublicationStatus(
      dataset.id,
      publicationStatus,
      publicationType,
      if (actor.id == 0) None else Some(actor.id),
      comments,
      embargoReleaseDate
    )

    val query = for {

      status <- (datasetPublicationStatusMapper returning datasetPublicationStatusMapper) += row

      _ <- ChangelogEventDetail
        .fromPublicationStatus(status)
        .traverse(changelogEventMapper.logEvent(dataset, _, actor))
    } yield status

    db.run(query.transactionally).toEitherT

  }

  def getLogByDataset(
    datasetId: Int,
    sortAscending: Boolean = false
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[DatasetPublicationStatus]] = {
    db.run(
        datasetPublicationStatusMapper
          .getByDataset(datasetId, sortAscending)
          .result
      )
      .toEitherT
  }

}
