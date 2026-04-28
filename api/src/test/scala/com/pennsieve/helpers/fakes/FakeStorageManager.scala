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
import com.pennsieve.domain.{ CoreError, StorageAggregation }
import com.pennsieve.managers.StorageServiceClientTrait
import com.pennsieve.models.Package

import scala.concurrent.{ ExecutionContext, Future }

/** Minimal `StorageServiceClientTrait` fake. The unit tests for User/Account/etc.
  * don't actually exercise stored sizes; they just need this so the cake
  * resolves. Returns empty/zero results for everything. */
class FakeStorageManager extends StorageServiceClientTrait {

  override def getStorage(
    storageType: StorageAggregation,
    itemIds: List[Int]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Map[Int, Option[Long]]] =
    EitherT.rightT(itemIds.map(_ -> Option(0L)).toMap)

  override def incrementStorage(
    storageType: StorageAggregation,
    size: Long,
    itemId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Map[String, Long]] =
    EitherT.rightT(Map.empty)

  override def setPackageStorage(
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Long] =
    EitherT.rightT(0L)
}
