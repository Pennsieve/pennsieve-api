// Copyright (c) 2020 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.managers

import cats.data._
import cats.implicits._
import com.blackfynn.core.utilities.FutureEitherHelpers.implicits._
import com.blackfynn.db.{ CollectionMapper, DatasetsMapper }
import com.blackfynn.models._
import com.blackfynn.traits.PostgresProfile.api._
import com.blackfynn.domain.{ CoreError, Error, NotFound, PredicateError }
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
