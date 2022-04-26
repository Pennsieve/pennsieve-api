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

import com.pennsieve.db._
import com.pennsieve.domain._
import com.pennsieve.models._
import com.pennsieve.traits.PostgresProfile.api._
import com.rms.miu.slickcats.DBIOInstances._
import org.postgresql.util.PSQLException
import com.pennsieve.core.utilities.FutureEitherHelpers
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import scala.concurrent.{ ExecutionContext, Future }

class DataUseAgreementManager(
  val db: Database,
  val organization: Organization
) {
  val dataUseAgreementMapper = new DataUseAgreementMapper(organization)

  def get(
    agreementId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DataUseAgreement] =
    db.run(
        dataUseAgreementMapper
          .filter(_.id === agreementId)
          .result
          .headOption
      )
      .whenNone(MissingDataUseAgreement: CoreError)

  def getAll(
    implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[DataUseAgreement]] =
    db.run(
        dataUseAgreementMapper
          .sortBy(a => (a.isDefault.desc, a.createdAt.asc)) // default agreement first
          .result
      )
      .toEitherT

  /**
    * Get default data use agreement for organization, if it exists.
    */
  def getDefault(
    implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Option[DataUseAgreement]] =
    db.run(dataUseAgreementMapper.filter(_.isDefault).take(1).result.headOption)
      .toEitherT

  def create(
    name: String,
    body: String,
    isDefault: Boolean = false,
    description: String = ""
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DataUseAgreement] = {
    val query = for {

      agreement <- (dataUseAgreementMapper returning dataUseAgreementMapper) += DataUseAgreement(
        name = name,
        description = description,
        body = body,
        isDefault = false
      )
      _ <- setDefault(agreement, isDefault)
    } yield agreement

    db.run(query.transactionally)
      .toEitherT[CoreError] {
        // unique constraint violation
        case e: PSQLException if e.getSQLState == "23505" =>
          PredicateError("Data use agreement name must be unique")
        case e => ExceptionError(e)
      }
  }

  def update(
    agreementId: Int,
    name: Option[String],
    body: Option[String],
    description: Option[String],
    isDefault: Option[Boolean]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] = {
    val query = for {

      agreement <- dataUseAgreementMapper.get(agreementId)

      _ <- dataUseAgreementMapper
        .filter(_.id === agreementId)
        .map(a => (a.name, a.body, a.description))
        .update(
          (
            name.getOrElse(agreement.name),
            body.getOrElse(agreement.body),
            description.getOrElse(agreement.description)
          )
        ): DBIO[Int]

      _ <- isDefault.traverse(setDefault(agreement, _))

    } yield agreement

    db.run(query.transactionally)
      .toEitherT[CoreError] {
        // unique constraint violation
        case e: PSQLException if e.getSQLState == "23505" =>
          PredicateError("Data use agreement name must be unique")
        case e: CoreError => e
        case e => ExceptionError(e)
      }
      .map(_ => ())
  }

  /**
    * Each organization has at most one default agreement. Demote the
    * current default agreement before promoting the new one.
    */
  def setDefault(
    agreement: DataUseAgreement,
    isDefault: Boolean
  )(implicit
    ec: ExecutionContext
  ): DBIO[DataUseAgreement] =
    for {
      _ <- isDefault match {
        case false =>
          dataUseAgreementMapper
            .filter(_.id === agreement.id)
            .map(_.isDefault)
            .update(false)

        case true =>
          sql"""
            UPDATE "#${organization.schemaId}".data_use_agreements
            SET is_default = CASE WHEN id = ${agreement.id} THEN true ELSE false END
            """.as[Int]
      }
    } yield agreement.copy(isDefault = isDefault)

  def delete(
    agreementId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] =
    db.run(dataUseAgreementMapper.filter(_.id === agreementId).delete)
      .toEitherT[CoreError] {
        case e: PSQLException if e.getSQLState == "23503" =>
          PredicateError("Dataset is still using this data use agreement")
        case e => ExceptionError(e)
      }
      .subflatMap {
        case 0 =>
          Left(NotFound(s"Agreement $agreementId"))
        case _ => Right(())
      }
}
