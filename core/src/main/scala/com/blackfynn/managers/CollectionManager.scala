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
import com.pennsieve.db.{ CollectionMapper, DatasetsMapper }
import com.pennsieve.models._
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.domain.{ CoreError, Error, NotFound, PredicateError }
import org.postgresql.util.PSQLException
import slick.dbio.DBIO

import scala.concurrent.{ ExecutionContext, Future }

class CollectionManager(
  val db: Database,
  val collectionMapper: CollectionMapper
) {

  val organization: Organization = collectionMapper.organization

  val datasetsMapper: DatasetsMapper = new DatasetsMapper(organization)

  def create(
    name: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Collection] = {

    val row = Collection(name = name.trim)

    val createdCollection = (collectionMapper returning collectionMapper) += row

    for {
      contributor <- db
        .run(createdCollection.transactionally)
        .toEitherT[CoreError] {
          //State 23505 is duplicate key value violates unique constraint "collection_name_key"
          case ex: PSQLException if ex.getSQLState == "23505" =>
            PredicateError("Collection name must be unique"): CoreError
          //State 23514 is duplicate new row for relation "collection" violates check constraint "collection_name_length_check"
          case ex: PSQLException if ex.getSQLState == "23514" =>
            PredicateError(
              "Collection name must be between 1 and 255 characters"
            ): CoreError
          case err => Error(err.getMessage): CoreError
        }

    } yield (contributor)
  }

  def get(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Collection] =
    db.run(collectionMapper.filter(_.id === id).result.headOption)
      .whenNone(NotFound(s"Collection ($id)"))

  def maybeGet(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Option[Collection]] =
    db.run(collectionMapper.filter(_.id === id).result.headOption).toEitherT

  def getCollections(
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[Collection]] =
    db.run(collectionMapper.result).toEitherT

  def update(
    name: String,
    collection: Collection
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Collection] =
    for {

      _ <- db
        .run(
          collectionMapper
            .filter(_.id === collection.id)
            .map(c => c.name)
            .update(name.trim)
        )
        .toEitherT[CoreError] {
          //State 23505 is duplicate key value violates unique constraint "collection_name_key"
          case ex: PSQLException if ex.getSQLState == "23505" =>
            PredicateError("Collection name must be unique"): CoreError
          //State 23514 is duplicate new row for relation "collection" violates check constraint "collection_name_length_check"
          case ex: PSQLException if ex.getSQLState == "23514" =>
            PredicateError(
              "Collection name must be between 1 and 255 characters"
            ): CoreError
          case err => Error(err.getMessage): CoreError
        }

      updatedCollection <- get(collection.id)
    } yield updatedCollection

  def delete(
    collection: Collection
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] = {
    for {
      _ <- db
        .run(
          collectionMapper
            .filter(_.id === collection.id)
            .delete
        )
        .toEitherT
    } yield ()
  }

  def deleteAllCollections(
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] = {
    for {
      _ <- db
        .run(collectionMapper.delete)
        .toEitherT
    } yield ()
  }
}
