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
import com.pennsieve.domain.{ CoreError, NotFound, PredicateError }
import com.pennsieve.managers.{ DatasetManager, PackageManager }
import com.pennsieve.models._

import scala.concurrent.{ ExecutionContext, Future }

/**
  * In-memory fake of `PackageManager`. Reads/writes `state.packages`.
  *
  * Inherits `db` (throwing) and `actor` from the supplied (fake)
  * `DatasetManager`. The trait's `organization` derives from `datasetManager.organization`.
  */
class FakePackageManager(val datasetManager: DatasetManager)
    extends PackageManager {

  // We can rely on the supplied datasetManager being a FakeDatasetManager so
  // we can access shared InMemoryState through it.
  private def state: InMemoryState =
    datasetManager.asInstanceOf[FakeDatasetManager].state
  private def orgId: Int = datasetManager.organization.id

  private def putPackage(p: Package): Package = {
    state.packages.put((orgId, p.id), p)
    p
  }

  private def packagesForDataset(datasetId: Int): Iterable[Package] =
    state.packages.collect {
      case ((o, _), p) if o == orgId && p.datasetId == datasetId => p
    }

  override def create(
    name: String,
    `type`: PackageType,
    state0: PackageState,
    dataset: Dataset,
    ownerId: Option[Int],
    parent: Option[Package],
    importId: Option[java.util.UUID] = None,
    attributes: List[ModelProperty] = List(),
    description: Option[String] = None,
    externalLocation: Option[String] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Package] = {
    if (parent.exists(_.datasetId != dataset.id))
      EitherT.leftT(
        PredicateError(
          "a package must belong to the same dataset as its parent"
        )
      )
    else if (name.trim.isEmpty)
      EitherT.leftT(PredicateError("package name must not be empty"))
    else if (`type` == PackageType.ExternalFile && externalLocation.isEmpty)
      EitherT.leftT(
        PredicateError("Externally Linked files must have a location")
      )
    else {
      val nodeCode =
        if (`type` == PackageType.Collection) NodeCodes.collectionCode
        else NodeCodes.packageCode
      val id = state.newId()
      // Match the json round-trip: ISO_OFFSET_DATE_TIME drops named zones.
      // Using a plain ZoneOffset (not ZonedDateTime.now()) keeps fake-side
      // timestamps comparable to deserialized PackagesPage values.
      val now = java.time.ZonedDateTime
        .now()
        .withZoneSameInstant(
          java.time.ZoneOffset.ofTotalSeconds(
            java.time.ZonedDateTime.now().getOffset.getTotalSeconds
          )
        )
      val pkg = Package(
        nodeId = NodeCodes.generateId(nodeCode),
        name = name,
        `type` = `type`,
        datasetId = dataset.id,
        ownerId = ownerId,
        state = state0,
        parentId = parent.map(_.id),
        importId = importId,
        attributes = attributes,
        createdAt = now,
        updatedAt = now,
        id = id
      )
      putPackage(pkg)
      // optional external file linkage — not needed for the tests we currently
      // exercise, but keep the placeholder for later
      EitherT.rightT(pkg)
    }
  }

  override def get(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Package] =
    state.packages.get((orgId, id)) match {
      case Some(p) => EitherT.rightT(p)
      case None => EitherT.leftT(NotFound(s"Package ($id)"))
    }

  override def getByNodeId(
    nodeId: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Package] =
    state.packages.values.find(p => p.nodeId == nodeId) match {
      case Some(p) => EitherT.rightT(p)
      case None => EitherT.leftT(NotFound(nodeId))
    }

  override def children(
    parent: Option[Package],
    dataset: Dataset,
    offset: Option[Int] = None,
    limit: Option[Int] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[Package]] = {
    val all = packagesForDataset(dataset.id)
      .filter(p => p.parentId == parent.map(_.id))
      .toList
      .sortBy(_.name)
    val sliced = (offset, limit) match {
      case (Some(o), Some(l)) => all.drop(o).take(l)
      case _ => all
    }
    EitherT.rightT(sliced)
  }

  private def hasMatchingSourceFile(pkg: Package, filename: String): Boolean =
    state.files.exists {
      case ((o, _), f) =>
        o == orgId && f.packageId == pkg.id && f.name == filename &&
          f.objectType == com.pennsieve.models.FileObjectType.Source
    }

  override def getPackagesPage(
    dataset: Dataset,
    startAtId: Option[Int],
    pageSize: Int,
    filterType: Option[Set[PackageType]],
    filename: Option[String]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Vector[Package]] = {
    val all = packagesForDataset(dataset.id)
      .filterNot(_.state == PackageState.DELETING)
      .filterNot(_.state == PackageState.DELETED)
      .toList
      .sortBy(_.id)
      .filter(p => startAtId.forall(p.id >= _))
      .filter(p => filterType.forall(_.contains(p.`type`)))
      .filter(p => filename.forall(hasMatchingSourceFile(p, _)))
    EitherT.rightT(all.take(pageSize + 1).toVector)
  }

  override def getPackagesPageWithFiles(
    dataset: Dataset,
    startAtId: Option[Int],
    pageSize: Int,
    filterType: Option[Set[PackageType]],
    filename: Option[String]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[PackagePageTruncated]] = {
    for {
      pkgs <- getPackagesPage(
        dataset,
        startAtId,
        pageSize,
        filterType,
        filename
      )
    } yield
      pkgs.map { p =>
        val matched = state.files
          .collect {
            case ((o, _), f)
                if o == orgId && f.packageId == p.id &&
                  f.objectType == com.pennsieve.models.FileObjectType.Source &&
                  filename.forall(_ == f.name) =>
              f
          }
          .toSeq
          .sortBy(_.id)
        // Mirror SQL: cap at 100 files, signal truncation if more remain.
        val (files, truncated) =
          if (matched.size > 100) (matched.take(100), true)
          else (matched, false)
        (p, files, truncated)
      }
  }

  override def markFilesInPendingStateAsUploaded(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] = {
    val pkgIds = packagesForDataset(dataset.id).map(_.id).toSet
    val updates = state.files.collect {
      case ((o, fid), f)
          if o == orgId && pkgIds.contains(f.packageId) &&
            f.uploadedState.contains(com.pennsieve.models.FileState.PENDING) =>
        (fid, f)
    }
    updates.foreach {
      case (fid, f) =>
        state.files.put(
          (orgId, fid),
          f.copy(uploadedState = Some(com.pennsieve.models.FileState.UPLOADED))
        )
    }
    EitherT.rightT(updates.size)
  }

  override def getByExternalIdsForDataset(
    dataset: Dataset,
    ids: List[com.pennsieve.models.ExternalId]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[Package]] = {
    val (intIds, nodeIds) = ids.map(_.value) match {
      case xs =>
        (xs.collect { case Left(i) => i }, xs.collect {
          case Right(s) => s
        })
    }
    val pkgs = packagesForDataset(dataset.id)
      .filterNot(
        p => p.state == PackageState.DELETING || p.state == PackageState.DELETED
      )
      .filter(p => intIds.contains(p.id) || nodeIds.contains(p.nodeId))
      .toList
    EitherT.rightT(pkgs)
  }

  override def packageTypes(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Map[PackageType, Int]] = {
    val grouped = packagesForDataset(dataset.id)
      .filterNot(
        p =>
          p.state == PackageState.DELETING ||
            p.state == PackageState.DELETED ||
            p.state == PackageState.RESTORING
      )
      .groupBy(_.`type`)
      .view
      .mapValues(_.size)
      .toMap
    EitherT.rightT(grouped)
  }
}
