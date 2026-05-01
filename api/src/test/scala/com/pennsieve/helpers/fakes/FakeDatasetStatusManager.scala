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
import cats.implicits._
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.domain.{ CoreError, NotFound }
import com.pennsieve.managers.DatasetStatusManager
import com.pennsieve.models.{
  DatasetStatus,
  DatasetStatusInUse,
  DefaultDatasetStatus,
  Organization
}
import com.pennsieve.traits.PostgresProfile.api.{ DBIO, Database }

import scala.concurrent.{ ExecutionContext, Future }

class FakeDatasetStatusManager(val state: InMemoryState, org: Organization)
    extends DatasetStatusManager {

  def db: Database =
    sys.error(
      "FakeDatasetStatusManager: a method not yet stubbed by your test " +
        "tried to use the database. Override the method on this fake."
    )

  override def organization: Organization = org

  private def statuses: Seq[DatasetStatus] =
    state.datasetStatuses
      .collect {
        case ((orgId, _), s) if orgId == org.id => s
      }
      .toSeq
      .sortBy(_.id)

  private def datasetCount(statusId: Int): Int =
    state.datasets.values.count(_.statusId == statusId)

  override def get(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DatasetStatus] =
    state.datasetStatuses.get((org.id, id)) match {
      case Some(s) => EitherT.rightT(s)
      case None => EitherT.leftT(NotFound("Dataset status"))
    }

  override def getAll(
    implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[DatasetStatus]] =
    EitherT.rightT(statuses)

  override def getAllWithUsage(
    implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[(DatasetStatus, DatasetStatusInUse)]] =
    EitherT.rightT(
      statuses.map(s => (s, DatasetStatusInUse(datasetCount(s.id) > 0)))
    )

  override def getById(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): DBIO[DatasetStatus] =
    state.datasetStatuses.get((org.id, id)) match {
      case Some(s) => DBIO.successful(s)
      case None => DBIO.failed(NotFound(s"Dataset status $id"))
    }

  override def getByName(
    name: String
  )(implicit
    ec: ExecutionContext
  ): DBIO[DatasetStatus] =
    statuses.find(_.name == name) match {
      case Some(s) => DBIO.successful(s)
      case None => DBIO.failed(NotFound(s"Dataset status $name"))
    }

  override def getDefaultStatus(
    implicit
    ec: ExecutionContext
  ): DBIO[DatasetStatus] =
    statuses.headOption match {
      case Some(s) => DBIO.successful(s)
      case None => DBIO.failed(NotFound("No default dataset status found"))
    }

  override def create(
    displayName: String,
    color: String = defaultColor,
    originalName: Option[com.pennsieve.models.DefaultDatasetStatus] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DatasetStatus] =
    for {
      _ <- validateColor(color)
        .toEitherT[Future]
      _ <- validateDisplayName(displayName)
        .toEitherT[Future]
    } yield {
      val id = state.newId()
      val s = DatasetStatus(
        name = slugify(displayName),
        displayName = displayName,
        color = color,
        originalName = originalName,
        id = id
      )
      state.datasetStatuses.put((org.id, id), s)
      s
    }

  override def update(
    id: Int,
    displayName: String,
    color: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (DatasetStatus, DatasetStatusInUse)] =
    for {
      _ <- validateColor(color).toEitherT[Future]
      _ <- validateDisplayName(displayName).toEitherT[Future]
      result <- state.datasetStatuses.get((org.id, id)) match {
        case None =>
          EitherT.leftT[Future, (DatasetStatus, DatasetStatusInUse)](
            NotFound(s"Dataset status $id"): CoreError
          )
        case Some(existing) =>
          val updated = existing.copy(
            displayName = displayName,
            name = slugify(displayName),
            color = color
          )
          state.datasetStatuses.put((org.id, id), updated)
          EitherT.rightT[Future, CoreError](
            (updated, DatasetStatusInUse(datasetCount(id) > 0))
          )
      }
    } yield result

  override def delete(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (DatasetStatus, DatasetStatusInUse)] = {
    state.datasetStatuses.get((org.id, id)) match {
      case None => EitherT.leftT(NotFound(s"Dataset status $id"): CoreError)
      case Some(deleted) =>
        if (statuses.size <= 1)
          EitherT.leftT(
            com.pennsieve.domain.PredicateError(
              "Cannot delete the last dataset status"
            ): CoreError
          )
        else {
          val inUse = DatasetStatusInUse(datasetCount(id) > 0)
          state.datasetStatuses.remove((org.id, id))
          // Reassign datasets using this status to the next one (mirror real
          // impl).
          val nextDefault = statuses.headOption
          nextDefault.foreach { next =>
            state.datasets.foreach {
              case (key, ds) if ds.statusId == id =>
                state.datasets.put(key, ds.copy(statusId = next.id))
              case _ => ()
            }
          }
          EitherT.rightT((deleted, inUse))
        }
    }
  }

  override def resetDefaultStatusOptions(
    implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[DatasetStatus]] = {
    statuses.foreach(s => state.datasetStatuses.remove((org.id, s.id)))
    val seeded = List(
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
    val seededWithIds = seeded.map { s =>
      val id = state.newId()
      val withId = s.copy(id = id)
      state.datasetStatuses.put((org.id, id), withId)
      withId
    }
    state.datasetStatusDefaultsSeeded.put(org.id, true)
    EitherT.rightT(seededWithIds)
  }
}
