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
      // Sibling packages within (dataset, parent, type) — used to auto-rename
      // duplicates the way `packagesMapper.checkName` does in production.
      val siblings = state.packages.collect {
        case ((o, _), p)
            if o == orgId && p.datasetId == dataset.id &&
              p.parentId == parent.map(_.id) && p.`type` == `type` =>
          p.name
      }.toSet
      val finalName =
        com.pennsieve.core.utilities.recommendName(name, siblings) match {
          case Left(com.pennsieve.domain.NameCheckError(rec, _)) => rec
          case Right(n) => n
        }
      val origName = name
      val resolvedName = finalName
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
        name = resolvedName,
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
      // Mirror real impl's external-file creation
      if (`type` == PackageType.ExternalFile && externalLocation.isDefined) {
        state.externalFiles.put(
          (orgId, pkg.id),
          com.pennsieve.models.ExternalFile(
            packageId = pkg.id,
            location = externalLocation.get,
            description = description
          )
        )
      }
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

  override def update(
    `package`: Package,
    description: Option[String] = None,
    externalLocation: Option[String] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Package] = {
    val pkg = `package`.copy(name = `package`.name.trim)
    if (pkg.name.isEmpty)
      EitherT.leftT(PredicateError("package name must not be empty"))
    else
      state.packages.get((orgId, pkg.id)) match {
        case None => EitherT.leftT(NotFound(s"Package (${pkg.id})"): CoreError)
        case Some(_) =>
          state.packages.put((orgId, pkg.id), pkg)
          // Mirror real impl's external-file update path
          if (pkg.`type` == PackageType.ExternalFile && externalLocation.isDefined) {
            state.externalFiles.put(
              (orgId, pkg.id),
              com.pennsieve.models.ExternalFile(
                packageId = pkg.id,
                location = externalLocation.get,
                description = description
              )
            )
          }
          EitherT.rightT(pkg)
      }
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

  override def getPackageAndDatasetByNodeId(
    nodeId: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Package, com.pennsieve.models.Dataset)] = {
    state.packages.values
      .find(p => p.nodeId == nodeId)
      .filterNot(
        p => p.state == PackageState.DELETING || p.state == PackageState.DELETED
      )
      .flatMap { p =>
        state.datasets.get((orgId, p.datasetId)).map(d => (p, d))
      } match {
      case Some(pair) => EitherT.rightT(pair)
      case None => EitherT.leftT(NotFound(s"Package ($nodeId)"): CoreError)
    }
  }

  override def ancestors(
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[Package]] = {
    def walk(p: Package): List[Package] =
      p.parentId match {
        case None => Nil
        case Some(pid) =>
          state.packages.get((orgId, pid)) match {
            case Some(parent) => walk(parent) :+ parent
            case None => Nil
          }
      }
    EitherT.rightT(walk(`package`))
  }

  override def getPackageAndDatasetById(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Package, com.pennsieve.models.Dataset)] =
    state.packages.get((orgId, id)).flatMap { p =>
      state.datasets.get((orgId, p.datasetId)).map(d => (p, d))
    } match {
      case Some(pair) => EitherT.rightT(pair)
      case None => EitherT.leftT(NotFound(s"Package ($id)"): CoreError)
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

  private def packagesInOrg: Iterable[Package] =
    state.packages.collect { case ((o, _), p) if o == orgId => p }

  override def getByNodeIds(
    nodeIds: List[String]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[Package]] =
    EitherT.rightT(
      packagesInOrg
        .filter(p => nodeIds.contains(p.nodeId))
        .filterNot(
          p =>
            p.state == PackageState.DELETING || p.state == PackageState.DELETED
        )
        .toList
    )

  override def getPackageHierarchy(
    nodeIds: List[String],
    fileIds: Option[List[Int]] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[PackageHierarchy]] = {
    // Resolve roots (only those in this org).
    val roots = packagesInOrg
      .filter(p => nodeIds.contains(p.nodeId))
      .toList

    // Walk descendants. Yields (pkg, nodeIdPath, namePath) where the path
    // does NOT include the package itself.
    def descend(
      pkg: Package,
      nodeIdPath: Seq[String],
      namePath: Seq[String]
    ): Seq[(Package, Seq[String], Seq[String])] = {
      val self = (pkg, nodeIdPath, namePath)
      val children = packagesInOrg
        .filter(c => c.parentId.contains(pkg.id))
        .toList
      val descendants = children.flatMap(
        c => descend(c, nodeIdPath :+ pkg.nodeId, namePath :+ pkg.name)
      )
      self +: descendants
    }

    val all = roots.flatMap(r => descend(r, Seq.empty, Seq.empty))

    // Skip Collection / DELETING / DELETED packages — they don't yield rows.
    val processable = all.filterNot {
      case (p, _, _) =>
        p.`type` == PackageType.Collection ||
          p.state == PackageState.DELETING ||
          p.state == PackageState.DELETED
    }

    val rows = processable.flatMap {
      case (pkg, nodeIdPath, namePath) =>
        val sourceFiles = state.files.collect {
          case ((o, _), f)
              if o == orgId && f.packageId == pkg.id &&
                f.objectType == FileObjectType.Source =>
            f
        }.toList
        val totalFileCount = sourceFiles.size
        val filtered = fileIds match {
          case Some(ids) => sourceFiles.filter(f => ids.contains(f.id))
          case None => sourceFiles
        }
        filtered.map { f =>
          PackageHierarchy(
            datasetId = pkg.datasetId,
            nodeIdPath = nodeIdPath,
            packageId = pkg.id,
            nodeId = pkg.nodeId,
            packageType = pkg.`type`,
            packageState = pkg.state,
            packageNamePath = namePath,
            packageName = pkg.name,
            packageFileCount = totalFileCount,
            fileId = f.id,
            fileName = f.name,
            size = f.size,
            fileType = f.fileType,
            s3Bucket = f.s3Bucket,
            s3Key = f.s3Key,
            publishedS3VersionId = f.publishedS3VersionId
          )
        }
    }

    EitherT.rightT(rows)
  }

  override def checkName(
    name: String,
    parent: Option[Package],
    dataset: Dataset,
    packageType: PackageType,
    duplicateThreshold: Int = 100
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, String] = {
    val siblings = state.packages.collect {
      case ((o, _), p)
          if o == orgId && p.datasetId == dataset.id &&
            p.parentId == parent.map(_.id) &&
            p.state != PackageState.DELETING &&
            p.state != PackageState.DELETED &&
            p.state != PackageState.RESTORING =>
        p.name
    }.toSet
    com.pennsieve.core.utilities.recommendName(
      name.trim,
      siblings,
      duplicateThreshold = duplicateThreshold
    ) match {
      case Right(n) => EitherT.rightT(n)
      case Left(err) => EitherT.leftT(err: CoreError)
    }
  }

  override def descendants(
    p: Package
  )(implicit
    ec: ExecutionContext
  ): Future[Set[Int]] = {
    def walk(pid: Int): Set[Int] = {
      val children = state.packages.collect {
        case ((o, _), c) if o == orgId && c.parentId.contains(pid) => c.id
      }.toSet
      children ++ children.flatMap(walk)
    }
    Future.successful(walk(p.id))
  }

  override def delete(
    traceId: com.pennsieve.audit.middleware.TraceId,
    pkg: Package
  )(
    storageManager: com.pennsieve.managers.StorageServiceClientTrait
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, com.pennsieve.messages.BackgroundJob] = {
    // Soft-delete: mark DELETING, prepend deletion sentinel, and recurse for
    // collection types.
    def softDelete(p: Package): Unit = {
      val updated = p.copy(
        state = PackageState.DELETING,
        name = s"__DELETED__${p.nodeId}_${p.name}"
      )
      state.packages.put((orgId, p.id), updated)
      if (p.`type` == PackageType.Collection) {
        state.packages
          .collect {
            case ((o, _), c) if o == orgId && c.parentId.contains(p.id) => c
          }
          .foreach(softDelete)
      }
    }
    softDelete(pkg)

    for {
      _ <- datasetManager.assertNotLocked(pkg.datasetId)
      // Decrement package storage if pkg was READY (mirrors real impl).
      _ <- if (pkg.state == PackageState.READY)
        storageManager
          .getStorage(
            com.pennsieve.domain.StorageAggregation.spackages,
            List(pkg.id)
          )
          .flatMap { m =>
            m.get(pkg.id).flatten match {
              case Some(amount) =>
                storageManager
                  .incrementStorage(
                    com.pennsieve.domain.StorageAggregation.spackages,
                    -amount,
                    pkg.id
                  )
                  .map(_ => ())
              case None => EitherT.rightT[Future, CoreError](())
            }
          } else EitherT.rightT[Future, CoreError](())
    } yield
      com.pennsieve.messages.DeletePackageJob(
        pkg.id,
        organization.id,
        datasetManager.actor.nodeId,
        traceId = traceId
      ): com.pennsieve.messages.BackgroundJob
  }

  override def getPackageHierarchyForOrg(
    orgId: Int,
    packageIds: List[Int]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[PackageHierarchy]] = {
    val roots = state.packages.collect {
      case ((o, _), p) if o == orgId && packageIds.contains(p.id) => p
    }.toList

    def descend(
      pkg: Package,
      nodeIdPath: Seq[String],
      namePath: Seq[String]
    ): Seq[(Package, Seq[String], Seq[String])] = {
      val children = state.packages.collect {
        case ((o, _), c) if o == orgId && c.parentId.contains(pkg.id) => c
      }.toList
      (pkg, nodeIdPath, namePath) +: children.flatMap(
        c => descend(c, nodeIdPath :+ pkg.nodeId, namePath :+ pkg.name)
      )
    }

    val all = roots.flatMap(r => descend(r, Seq.empty, Seq.empty))

    val processable = all.filterNot {
      case (p, _, _) =>
        p.`type` == PackageType.Collection ||
          p.state == PackageState.DELETING ||
          p.state == PackageState.DELETED
    }

    val rows = processable.flatMap {
      case (pkg, nodeIdPath, namePath) =>
        val sourceFiles = state.files.collect {
          case ((o, _), f)
              if o == orgId && f.packageId == pkg.id &&
                f.objectType == FileObjectType.Source =>
            f
        }.toList
        val totalFileCount = sourceFiles.size
        sourceFiles.map { f =>
          PackageHierarchy(
            datasetId = pkg.datasetId,
            nodeIdPath = nodeIdPath,
            packageId = pkg.id,
            nodeId = pkg.nodeId,
            packageType = pkg.`type`,
            packageState = pkg.state,
            packageNamePath = namePath,
            packageName = pkg.name,
            packageFileCount = totalFileCount,
            fileId = f.id,
            fileName = f.name,
            size = f.size,
            fileType = f.fileType,
            s3Bucket = f.s3Bucket,
            s3Key = f.s3Key,
            publishedS3VersionId = f.publishedS3VersionId
          )
        }
    }

    EitherT.rightT(rows)
  }

  override def switchOwner(
    user: com.pennsieve.models.User,
    owner: Option[com.pennsieve.models.User]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] = {
    val updated = state.packages.collect {
      case ((o, pid), p) if o == orgId && p.ownerId.contains(user.id) =>
        (pid, p.copy(ownerId = owner.map(_.id)))
    }.toList
    updated.foreach { case (pid, p) => state.packages.put((orgId, pid), p) }
    EitherT.rightT(updated.size)
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
