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
import com.pennsieve.domain.{
  CoreError,
  MissingDataUseAgreement,
  NotFound,
  PredicateError
}
import com.pennsieve.managers.DataUseAgreementManager
import com.pennsieve.models.{ DataUseAgreement, Organization }
import com.pennsieve.traits.PostgresProfile.api.Database

import scala.concurrent.{ ExecutionContext, Future }

class FakeDataUseAgreementManager(
  val state: InMemoryState,
  override val organization: Organization
) extends DataUseAgreementManager {

  def db: Database =
    sys.error(
      "FakeDataUseAgreementManager: a method not yet stubbed by your test " +
        "tried to use the database. Override the method on this fake."
    )

  private def agreementsForOrg: Iterable[DataUseAgreement] =
    state.dataUseAgreements.collect {
      case ((orgId, _), a) if orgId == organization.id => a
    }

  override def get(
    agreementId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DataUseAgreement] =
    state.dataUseAgreements.get((organization.id, agreementId)) match {
      case Some(a) => EitherT.rightT(a)
      case None => EitherT.leftT(MissingDataUseAgreement: CoreError)
    }

  override def getAll(
    implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[DataUseAgreement]] =
    EitherT.rightT(
      agreementsForOrg.toSeq.sortBy(a => (!a.isDefault, a.createdAt))
    )

  override def getDefault(
    implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Option[DataUseAgreement]] =
    EitherT.rightT(agreementsForOrg.find(_.isDefault))

  override def create(
    name: String,
    body: String,
    isDefault: Boolean = false,
    description: String = ""
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DataUseAgreement] = {
    if (agreementsForOrg.exists(_.name == name))
      EitherT.leftT(PredicateError("Data use agreement name must be unique"))
    else {
      if (isDefault)
        agreementsForOrg.filter(_.isDefault).foreach { a =>
          state.dataUseAgreements
            .put((organization.id, a.id), a.copy(isDefault = false))
        }
      val id = state.newId()
      val now = java.time.ZonedDateTime
        .now()
        .withZoneSameInstant(
          java.time.ZoneOffset.ofTotalSeconds(
            java.time.ZonedDateTime.now().getOffset.getTotalSeconds
          )
        )
      val agreement = DataUseAgreement(
        name = name,
        description = description,
        body = body,
        isDefault = isDefault,
        createdAt = now,
        id = id
      )
      state.dataUseAgreements.put((organization.id, id), agreement)
      EitherT.rightT(agreement)
    }
  }

  override def update(
    agreementId: Int,
    name: Option[String],
    body: Option[String],
    description: Option[String],
    isDefault: Option[Boolean]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] =
    state.dataUseAgreements.get((organization.id, agreementId)) match {
      case None => EitherT.leftT(NotFound(s"Agreement $agreementId"): CoreError)
      case Some(existing) =>
        val newName = name.getOrElse(existing.name)
        if (name.isDefined && newName != existing.name &&
          agreementsForOrg.exists(a => a.name == newName))
          EitherT.leftT(
            PredicateError("Data use agreement name must be unique"): CoreError
          )
        else {
          val updated = existing.copy(
            name = newName,
            body = body.getOrElse(existing.body),
            description = description.getOrElse(existing.description)
          )
          state.dataUseAgreements.put((organization.id, agreementId), updated)
          isDefault match {
            case Some(true) =>
              // Demote any existing default, then promote.
              agreementsForOrg.foreach { a =>
                state.dataUseAgreements.put(
                  (organization.id, a.id),
                  a.copy(isDefault = a.id == agreementId)
                )
              }
            case Some(false) =>
              state.dataUseAgreements.put(
                (organization.id, agreementId),
                updated.copy(isDefault = false)
              )
            case None => ()
          }
          EitherT.rightT(())
        }
    }

  override def delete(
    agreementId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] =
    state.dataUseAgreements.remove((organization.id, agreementId)) match {
      case Some(_) => EitherT.rightT(())
      case None => EitherT.leftT(NotFound(s"Agreement $agreementId"): CoreError)
    }
}
