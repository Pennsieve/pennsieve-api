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
import com.pennsieve.models.{ FileObjectType, Organization, Package }

import scala.concurrent.{ ExecutionContext, Future }

/** Tracks storage cumulatively across packages, datasets, and organizations
  * via `InMemoryState`. `incrementStorage(spackages, ...)` cascades to the
  * package's dataset and the org. `setPackageStorage` computes from source
  * files and applies the delta. */
class FakeStorageManager(state: InMemoryState, organization: Organization)
    extends StorageServiceClientTrait {

  override def getStorage(
    storageType: StorageAggregation,
    itemIds: List[Int]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Map[Int, Option[Long]]] = {
    val map = storageType match {
      case StorageAggregation.spackages =>
        itemIds.map { id =>
          id -> state.packageStorage.get((organization.id, id))
        }.toMap
      case StorageAggregation.sdatasets =>
        itemIds.map { id =>
          id -> state.datasetStorage.get((organization.id, id))
        }.toMap
      case StorageAggregation.sorganizations =>
        itemIds.map(id => id -> state.organizationStorage.get(id)).toMap
      case StorageAggregation.susers =>
        itemIds.map(_ -> Option.empty[Long]).toMap
    }
    EitherT.rightT(map)
  }

  override def incrementStorage(
    storageType: StorageAggregation,
    size: Long,
    itemId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Map[String, Long]] = {
    storageType match {
      case StorageAggregation.spackages =>
        applyPackageDelta(itemId, size)
      case StorageAggregation.sdatasets =>
        addTo(state.datasetStorage, (organization.id, itemId), size)
      case StorageAggregation.sorganizations =>
        addToOrg(itemId, size)
      case StorageAggregation.susers =>
        addToUser(itemId, size)
    }
    EitherT.rightT(Map.empty)
  }

  override def setPackageStorage(
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Long] = {
    val newSize = state.files.collect {
      case ((orgId, _), f)
          if orgId == organization.id && f.packageId == `package`.id &&
            f.objectType == FileObjectType.Source =>
        f.size
    }.sum
    val current =
      state.packageStorage.getOrElse((organization.id, `package`.id), 0L)
    val delta = newSize - current
    applyPackageDelta(`package`.id, delta)
    EitherT.rightT(newSize)
  }

  private def applyPackageDelta(packageId: Int, delta: Long): Unit = {
    addTo(state.packageStorage, (organization.id, packageId), delta)
    state.packages.get((organization.id, packageId)).foreach { pkg =>
      // Walk up ancestor packages, accruing storage at each level.
      var cur = pkg.parentId
      while (cur.isDefined) {
        val pid = cur.get
        addTo(state.packageStorage, (organization.id, pid), delta)
        cur = state.packages.get((organization.id, pid)).flatMap(_.parentId)
      }
      addTo(state.datasetStorage, (organization.id, pkg.datasetId), delta)
      addToOrg(organization.id, delta)
      pkg.ownerId.foreach(addToUser(_, delta))
    }
  }

  private def addTo[K](
    map: scala.collection.concurrent.TrieMap[K, Long],
    key: K,
    delta: Long
  ): Unit = {
    val current = map.getOrElse(key, 0L)
    map.put(key, current + delta)
    ()
  }

  private def addToOrg(orgId: Int, delta: Long): Unit = {
    val current = state.organizationStorage.getOrElse(orgId, 0L)
    state.organizationStorage.put(orgId, current + delta)
    ()
  }

  private def addToUser(userId: Int, delta: Long): Unit = {
    val current = state.userStorage.getOrElse(userId, 0L)
    state.userStorage.put(userId, current + delta)
    ()
  }
}
