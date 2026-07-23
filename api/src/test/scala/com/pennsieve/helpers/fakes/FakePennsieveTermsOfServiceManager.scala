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
import com.pennsieve.db.PennsieveTermsOfService
import com.pennsieve.domain.CoreError
import com.pennsieve.managers.PennsieveTermsOfServiceManager
import com.pennsieve.traits.PostgresProfile.api.Database

import java.time.ZonedDateTime
import scala.concurrent.{ ExecutionContext, Future }

class FakePennsieveTermsOfServiceManager(state: InMemoryState)
    extends PennsieveTermsOfServiceManager {

  def db: Database =
    sys.error(
      "FakePennsieveTermsOfServiceManager: a method not yet stubbed by your " +
        "test tried to use the database. Override the method on this fake."
    )

  override def get(
    userId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Option[PennsieveTermsOfService]] =
    EitherT.rightT(state.pennsieveTos.get(userId))

  override def getUserMap(
    userIds: Seq[Int]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Map[Int, PennsieveTermsOfService]] =
    EitherT.rightT(
      userIds.flatMap(id => state.pennsieveTos.get(id).map(id -> _)).toMap
    )

  override def setNewVersion(
    userId: Int,
    version: ZonedDateTime
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, PennsieveTermsOfService] = {
    val tos =
      PennsieveTermsOfService(userId = userId, acceptedVersion = version)
    state.pennsieveTos.put(userId, tos)
    EitherT.rightT(tos)
  }
}
