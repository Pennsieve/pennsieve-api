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
import com.pennsieve.core.utilities.FutureEitherHelpers
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.db._
import com.pennsieve.models._
import com.pennsieve.traits.PostgresProfile.api._
import java.util.UUID

import com.pennsieve.domain.{
  CoreError,
  ExceptionError,
  NotFound,
  PredicateError,
  ServiceError
}

import java.text.Normalizer
import scala.concurrent.{ ExecutionContext, Future }
import slick.dbio.DBIO

class DatasetStatusManager(val db: Database, val organization: Organization) {

  val datasetStatusMapper: DatasetStatusMapper = new DatasetStatusMapper(
    organization
  )
  val datasetMapper: DatasetsMapper = new DatasetsMapper(organization)

  val datasetStatusColors: Map[String, String] = Map(
    "Grey" -> "#71747C", // No Status
    "Purple" -> "#6D48CE",
    "Blue" -> "#2760FF", // Work in Progress
    "Teal" -> "#08B3AF",
    "Green" -> "#17BB62", // Completed
    "Brown" -> "#835800",
    "Yellow" -> "#FFB000", // In Review
    "Orange" -> "#F67325",
    "Red" -> "#E94B4B",
    "Pink" -> "#DE38B3"
  )

  val defaultColor: String = datasetStatusColors.values.head

  // This value needs to match the `dataset_status_name_length_check` and
  // `dataset_status_display_name_length_check` constraints on the dataset
  // status table.
  val displayNameMaxLength: Int = 60

  def validateColor(color: String): Either[CoreError, String] =
    if (datasetStatusColors.values.find(_ == color).isDefined)
      Right(color)
    else
      Left(
        PredicateError(
          s"""'$color' is not a valid color - must be one of ${datasetStatusColors.values
            .mkString(", ")}"""
        )
      )

  def validateDisplayName(displayName: String): Either[CoreError, String] =
    if (displayName.length > 0 && displayName.length <= displayNameMaxLength)
      Right(displayName)
    else
      Left(
        PredicateError(
          s"'$displayName' is too long - must be less than $displayNameMaxLength characters"
        )
      )

  /**
    * Convert a display name to an UPPER_SNAKECASE slug
    *
    * Adapted from https://gist.github.com/sam/5213151
    */
  def slugify(displayName: String): String =
    Normalizer
      .normalize(displayName, Normalizer.Form.NFD)
      .replaceAll("[^\\w\\s-]", "")
      .replace('-', ' ')
      .trim
      .replaceAll("\\s+", "_")
      .toUpperCase

  /**
    * Compute whether any datasets are currently using this status.
    */
  implicit class DatasetStatusInUseQueryEnrichment[C[_]](
    q: Query[DatasetStatusTable, DatasetStatus, C]
  ) {
    def joinWithUsage: Query[
      (DatasetStatusTable, Rep[DatasetStatusInUse]),
      (DatasetStatus, DatasetStatusInUse),
      C
    ] =
      q.joinLeft(datasetMapper.groupBy(_.statusId).map {
          case (statusId, datasets) => (statusId, datasets.length > 0)
        })
        .on {
          case (status, (statusId, _)) => status.id === statusId
        }
        .map {
          case (status, using) =>
            (
              status,
              using.map(_._2).ifNull(false).asColumnOf[DatasetStatusInUse]
            )
        }
  }

  def get(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DatasetStatus] =
    db.run(datasetStatusMapper.filter(_.id === id).result.headOption)
      .whenNone(NotFound(s"Dataset status"): CoreError)

  def getAll(
    implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[DatasetStatus]] =
    db.run(datasetStatusMapper.sortBy(_.id).result).toEitherT

  def getAllWithUsage(
    implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[(DatasetStatus, DatasetStatusInUse)]] =
    db.run(datasetStatusMapper.joinWithUsage.sortBy(_._1.id).result).toEitherT

  def getById(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): DBIO[DatasetStatus] =
    datasetStatusMapper.filter(_.id === id).result.headOption.flatMap {
      case Some(status) => DBIO.successful(status)
      case None => DBIO.failed(NotFound(s"Dataset status $id"))
    }

  def getByName(
    name: String
  )(implicit
    ec: ExecutionContext
  ): DBIO[DatasetStatus] =
    datasetStatusMapper.filter(_.name === name).result.headOption.flatMap {
      case Some(status) => DBIO.successful(status)
      case None => DBIO.failed(NotFound(s"Dataset status $name"))
    }

  /**
    * The "default" status is considered to be the first status in
    * database. This default is used for newly created datasets. If a status
    * option is deleted, and datasets are still using that option, the datasets
    * are assigned the default status.
    *
    * Status options are currently ordered by their ID. We will probably need to
    * add a `order` column to allow explicit re-ordering of status options.
    */
  def getDefaultStatus(
    implicit
    ec: ExecutionContext
  ): DBIO[DatasetStatus] =
    datasetStatusMapper.sortBy(_.id).take(1).result.headOption.flatMap {
      case Some(defaultStatus) => DBIO.successful(defaultStatus)
      case None => DBIO.failed(NotFound("No default dataset status found"))
    }

  /**
    * Reset the organization's status options to the Pennsieve defaults.  This
    * is currently only used for testing, but an endpoint will be added to reset
    * to the default.
    *
    * TODO: Instead of deleting and replacing, this should merge the existing
    * status options with the defaults using the `originalName` field.
    */
  def resetDefaultStatusOptions(
    implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[DatasetStatus]] = {
    val query = for {
      _ <- datasetStatusMapper.delete
      status <- datasetStatusMapper.returning(datasetStatusMapper) ++= List(
        DatasetStatus(
          name = DefaultDatasetStatus.NoStatus.entryName,
          displayName = "No Status",
          color = "#71747C",
          originalName = Some(DefaultDatasetStatus.NoStatus)
        ),
        DatasetStatus(
          name = DefaultDatasetStatus.WorkInProgress.entryName,
          displayName = "Work in Progress",
          color = "#2760FF",
          originalName = Some(DefaultDatasetStatus.WorkInProgress)
        ),
        DatasetStatus(
          name = DefaultDatasetStatus.InReview.entryName,
          displayName = "In Review",
          color = "#FFB000",
          originalName = Some(DefaultDatasetStatus.InReview)
        ),
        DatasetStatus(
          name = DefaultDatasetStatus.Completed.entryName,
          displayName = "Completed",
          color = "#17BB62",
          originalName = Some(DefaultDatasetStatus.Completed)
        )
      )
    } yield status

    db.run(query.transactionally).toEitherT
  }

  def create(
    displayName: String,
    color: String = defaultColor,
    originalName: Option[DefaultDatasetStatus] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DatasetStatus] = {
    val query =
      datasetStatusMapper.returning(datasetStatusMapper) += DatasetStatus(
        name = slugify(displayName),
        displayName = displayName,
        color = color,
        originalName = originalName
      )

    for {
      _ <- validateColor(color).toEitherT[Future]
      _ <- validateDisplayName(displayName).toEitherT[Future]

      status <- db.run(query.transactionally).toEitherT
    } yield status
  }

  def update(
    id: Int,
    displayName: String,
    color: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (DatasetStatus, DatasetStatusInUse)] = {

    val query = for {
      _ <- datasetStatusMapper
        .filter(_.id === id)
        .map(status => (status.displayName, status.name, status.color))
        .update((displayName, slugify(displayName), color))

      datasetStatus <- datasetStatusMapper
        .filter(_.id === id)
        .joinWithUsage
        .result
        .headOption
    } yield datasetStatus

    for {
      _ <- validateColor(color).toEitherT[Future]
      _ <- validateDisplayName(displayName).toEitherT[Future]
      status <- db
        .run(query.transactionally)
        .whenNone(NotFound(s"Dataset status $id"): CoreError)
    } yield status
  }

  /**
    * Delete a dataset status. If any datasets use this status, those dataset
    * are updated to use the default status. If the status to be deleted is the
    * current default status, the next status is promoted to be new default.
    *
    * An organization must have at least one dataset status option.
    */
  def delete(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (DatasetStatus, DatasetStatusInUse)] = {

    val query = for {
      deletedStatus <- datasetStatusMapper
        .filter(_.id === id)
        .joinWithUsage
        .result
        .headOption
        .flatMap {
          case Some(deletedStatus) => DBIO.successful(deletedStatus)
          case None =>
            DBIO.failed(NotFound(s"Dataset status $id"))
        }

      defaultStatus <- datasetStatusMapper
        .filter(_.id =!= id)
        .sortBy(_.id)
        .take(1)
        .result
        .headOption
        .flatMap {
          case Some(defaultStatus) => DBIO.successful(defaultStatus)
          case None =>
            DBIO.failed(
              PredicateError("Cannot delete all dataset status options")
            )
        }

      _ <- datasetMapper
        .map(_.statusId)
        .filter(_ === id)
        .update(defaultStatus.id)

      _ <- datasetStatusMapper
        .filter(_.id === id)
        .delete
    } yield deletedStatus

    db.run(query.transactionally)
      .toEitherT(
        (e: Exception) =>
          e match {
            case e: CoreError => e
            case e: Throwable => ExceptionError(e)
          }
      )
  }
}
