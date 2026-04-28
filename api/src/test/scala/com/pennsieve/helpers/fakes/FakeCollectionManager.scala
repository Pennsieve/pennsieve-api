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
import com.pennsieve.db.CollectionMapper
import com.pennsieve.domain.{ CoreError, NotFound, PredicateError }
import com.pennsieve.managers.CollectionManager
import com.pennsieve.models.{ Collection, Organization }
import com.pennsieve.traits.PostgresProfile.api.Database

import scala.concurrent.{ ExecutionContext, Future }

/**
  * Mirrors the real CollectionManager validation: trims input, rejects names
  * that aren't 1-255 chars (after trim), rejects duplicates within the same
  * organization. The real manager catches these as PSQL exception codes
  * (23514 length-check, 23505 unique-key) and translates to PredicateError;
  * the fake enforces the same predicates explicitly so the same user-facing
  * messages are returned.
  */
class FakeCollectionManager(
  state: InMemoryState,
  override val organization: Organization
) extends CollectionManager {

  def db: Database =
    sys.error(
      "FakeCollectionManager: a method not yet stubbed by your test tried to " +
        "use the database. Override the method on this fake."
    )

  def collectionMapper: CollectionMapper =
    sys.error("FakeCollectionManager: collectionMapper not used in fakes.")

  private val LengthError =
    PredicateError("Collection name must be between 1 and 255 characters")
  private val UniqueError =
    PredicateError("Collection name must be unique")

  private def validateLength(name: String): Either[CoreError, String] = {
    val trimmed = name.trim
    if (trimmed.isEmpty || trimmed.length > 255) Left(LengthError)
    else Right(trimmed)
  }

  private def existsInOrg(
    name: String,
    excludeId: Option[Int] = None
  ): Boolean =
    state.collections.exists {
      case ((orgId, id), c) =>
        orgId == organization.id &&
          excludeId.forall(_ != id) &&
          c.name.equalsIgnoreCase(name)
    }

  override def create(
    name: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Collection] =
    EitherT.fromEither[Future](for {
      trimmed <- validateLength(name)
      _ <- if (existsInOrg(trimmed)) Left(UniqueError) else Right(())
    } yield {
      val collection = Collection(id = state.newId(), name = trimmed)
      state.collections.put((organization.id, collection.id), collection)
      collection
    })

  override def get(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Collection] =
    state.collections.get((organization.id, id)) match {
      case Some(c) => EitherT.rightT(c)
      case None => EitherT.leftT(NotFound(s"Collection ($id)"))
    }

  override def getCollections(
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[Collection]] =
    EitherT.rightT(
      state.collections
        .collect {
          case ((orgId, _), c) if orgId == organization.id => c
        }
        .toSeq
        .sortBy(_.id)
    )

  override def update(
    name: String,
    collection: Collection
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Collection] =
    EitherT.fromEither[Future](for {
      trimmed <- validateLength(name)
      _ <- if (existsInOrg(trimmed, excludeId = Some(collection.id)))
        Left(UniqueError)
      else Right(())
    } yield {
      val updated = collection.copy(name = trimmed)
      state.collections.put((organization.id, collection.id), updated)
      updated
    })
}
