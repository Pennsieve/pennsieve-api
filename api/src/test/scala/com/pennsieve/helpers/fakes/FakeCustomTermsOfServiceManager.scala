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
import com.pennsieve.db.CustomTermsOfService
import com.pennsieve.domain.{ CoreError, InvalidDateVersion }
import com.pennsieve.managers.CustomTermsOfServiceManager
import com.pennsieve.models.DateVersion
import com.pennsieve.traits.PostgresProfile.api.Database

import java.time.{ ZoneOffset, ZonedDateTime }
import scala.concurrent.{ ExecutionContext, Future }

class FakeCustomTermsOfServiceManager(state: InMemoryState)
    extends CustomTermsOfServiceManager {

  def db: Database =
    sys.error(
      "FakeCustomTermsOfServiceManager: a method not yet stubbed by your " +
        "test tried to use the database. Override the method on this fake."
    )

  override def get(
    userId: Int,
    organizationId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Option[CustomTermsOfService]] =
    EitherT.rightT(state.customTos.get((userId, organizationId)))

  override def getAll(
    userId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[CustomTermsOfService]] =
    EitherT.rightT(state.customTos.collect {
      case ((uid, _), tos) if uid == userId => tos
    }.toSeq)

  override def getUserMap(
    userIds: Seq[Int],
    organizationId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Map[Int, Seq[CustomTermsOfService]]] =
    EitherT.rightT(
      state.customTos
        .collect {
          case ((uid, oid), tos)
              if oid == organizationId && userIds.contains(uid) =>
            uid -> tos
        }
        .groupBy(_._1)
        .map { case (uid, pairs) => uid -> pairs.map(_._2).toSeq }
        .toMap
    )

  override def accept(
    userId: Int,
    organizationId: Int,
    acceptedVersion: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, CustomTermsOfService] =
    DateVersion.from(acceptedVersion) match {
      case Left(_) =>
        EitherT.leftT(InvalidDateVersion(acceptedVersion))
      case Right(validVersion) =>
        val tos = CustomTermsOfService(
          userId = userId,
          organizationId = organizationId,
          acceptedVersion = validVersion.toZonedDateTime,
          acceptedDate = ZonedDateTime.now(ZoneOffset.UTC)
        )
        state.customTos.put((userId, organizationId), tos)
        EitherT.rightT(tos)
    }
}
