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
import com.pennsieve.db.DataCanvasMapper
import com.pennsieve.domain.{
  CoreError,
  NotFound,
  PredicateError,
  ServiceError
}
import com.pennsieve.managers.DataCanvasManager
import com.pennsieve.models.{
  DataCanvas,
  DataCanvasFolder,
  DataCanvasFolderPath,
  DataCanvasPackage,
  NodeCodes,
  Organization,
  Role,
  User
}
import com.pennsieve.traits.PostgresProfile.api.Database

import scala.concurrent.{ ExecutionContext, Future }

class FakeDataCanvasManager(
  state: InMemoryState,
  org: Organization,
  val actor: User
) extends DataCanvasManager {

  def db: Database =
    sys.error(
      "FakeDataCanvasManager: a method not yet stubbed by your test tried " +
        "to use the database. Override the method on this fake."
    )

  override val datacanvasMapper: DataCanvasMapper = new DataCanvasMapper(org)
  override lazy val organization: Organization = org

  private val maxNameLength = 256

  override def isLocked(
    dataCanvas: DataCanvas
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Boolean] = EitherT.rightT(false)

  override def isFolderLocked(
    folder: DataCanvasFolder
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Boolean] = EitherT.rightT(false)

  override def create(
    name: String,
    description: String,
    userId: Int = actor.id,
    nodeId: String = NodeCodes.generateId(NodeCodes.dataCanvasCode),
    isPublic: Option[Boolean] = None,
    role: Option[String] = None,
    statusId: Option[Int] = None,
    permissionBit: Option[Int] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DataCanvas] =
    if (name.trim.isEmpty)
      EitherT.leftT(PredicateError("data-canvas name must not be empty"))
    else if (name.trim.length >= maxNameLength)
      EitherT.leftT(
        PredicateError(
          s"dataset name must be less than $maxNameLength characters"
        )
      )
    else if (state.dataCanvases.values.exists(c => c.name == name.trim))
      EitherT.leftT(PredicateError("data-canvas name must be unique"))
    else {
      // Resolve status: explicit `statusId` if provided, otherwise the
      // org's default DatasetStatus (mirrors real impl).
      val resolvedStatusId = statusId
        .orElse {
          state.datasetStatuses.collectFirst {
            case ((o, _), s) if o == org.id && s.name == "NO_STATUS" => s.id
          }
        }
        .getOrElse(0)
      val id = state.newId()
      val canvas = DataCanvas(
        id = id,
        name = name,
        description = description,
        nodeId = nodeId,
        permissionBit = permissionBit.getOrElse(0),
        role = role,
        statusId = resolvedStatusId,
        isPublic = isPublic.getOrElse(false)
      )
      state.dataCanvases.put((org.id, id), canvas)
      // Track owner for getForUser. (orgId, canvasId, userId) -> Role.
      state.dataCanvasOwners.put((org.id, id, userId), Role.Owner)
      // Auto-create root folder.
      val rootId = state.newId()
      val root = DataCanvasFolder(
        id = rootId,
        parentId = None,
        dataCanvasId = id,
        name = "||ROOT||",
        nodeId = NodeCodes.generateId(NodeCodes.folderCode)
      )
      state.dataCanvasFolders.put((org.id, rootId), root)
      EitherT.rightT(canvas)
    }

  override def getAll(
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[DataCanvas]] =
    EitherT.rightT(canvasesForOrg.toSeq.sortBy(_.id))

  override def getById(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DataCanvas] =
    state.dataCanvases.get((org.id, id)) match {
      case Some(c) => EitherT.rightT(c)
      case None => EitherT.leftT(NotFound(id.toString): CoreError)
    }

  override def getByNodeId(
    nodeId: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DataCanvas] =
    canvasesForOrg.find(_.nodeId == nodeId) match {
      case Some(c) => EitherT.rightT(c)
      case None => EitherT.leftT(NotFound(nodeId): CoreError)
    }

  override def getForUser(
    userId: Int = actor.id,
    withRole: Role = Role.Owner,
    restrictToRole: Boolean = false
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[DataCanvas]] = {
    val canvasIds = state.dataCanvasOwners.collect {
      case ((orgId, cid, uid), r)
          if orgId == org.id && uid == userId &&
            (if (restrictToRole) r == withRole else r >= withRole) =>
        cid
    }.toSet
    EitherT.rightT(
      canvasIds.flatMap(id => state.dataCanvases.get((org.id, id))).toSeq
    )
  }

  override def update(
    dataCanvas: DataCanvas,
    checkforDuplicateNames: Boolean = true
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DataCanvas] =
    if (dataCanvas.name.trim.isEmpty)
      EitherT.leftT(PredicateError("data-canvas name must not be empty"))
    else if (dataCanvas.name.trim.length >= maxNameLength)
      EitherT.leftT(
        PredicateError(
          s"data-canvas name must be less than $maxNameLength characters"
        )
      )
    else {
      // Mirror real impl: if a different canvas already has this name and we
      // care about uniqueness, error out.
      val nameTaken = canvasesForOrg
        .exists(c => c.name == dataCanvas.name && c.id != dataCanvas.id)
      if (nameTaken && checkforDuplicateNames)
        EitherT.leftT(
          ServiceError(s"name is already taken: ${dataCanvas.name}"): CoreError
        )
      else {
        state.dataCanvases.get((org.id, dataCanvas.id)) match {
          case None =>
            EitherT.leftT(NotFound(dataCanvas.id.toString): CoreError)
          case Some(_) =>
            state.dataCanvases.put((org.id, dataCanvas.id), dataCanvas)
            EitherT.rightT(dataCanvas)
        }
      }
    }

  override def delete(
    dataCanvas: DataCanvas
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Boolean] =
    if (dataCanvas.name.trim.isEmpty)
      EitherT.leftT(PredicateError("data-canvas name must not be empty"))
    else {
      state.dataCanvases.remove((org.id, dataCanvas.id))
      // Cascade-delete folders + packages.
      foldersForCanvas(dataCanvas.id)
        .foreach(f => state.dataCanvasFolders.remove((org.id, f.id)))
      state.dataCanvasPackages
        .collect {
          case (k, p) if p.dataCanvasId == dataCanvas.id => k
        }
        .foreach(state.dataCanvasPackages.remove)
      EitherT.rightT(true)
    }

  override def attachPackage(
    dataCanvasId: Int,
    folderId: Int,
    datasetId: Int,
    packageId: Int,
    organizationId: Int = org.id
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DataCanvasPackage] =
    state.dataCanvases.get((org.id, dataCanvasId)) match {
      case None =>
        EitherT.leftT(NotFound(dataCanvasId.toString): CoreError)
      case Some(_) =>
        val key =
          (org.id, dataCanvasId, folderId, datasetId, packageId)
        val dcp = DataCanvasPackage(
          organizationId = organizationId,
          packageId = packageId,
          datasetId = datasetId,
          dataCanvasFolderId = folderId,
          dataCanvasId = dataCanvasId
        )
        state.dataCanvasPackages.put(key, dcp)
        EitherT.rightT(dcp)
    }

  override def getPackage(
    folderId: Int,
    packageId: Int,
    datasetId: Int,
    organizationId: Option[Int] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DataCanvasPackage] =
    state.dataCanvasPackages.values
      .find(
        p =>
          p.dataCanvasFolderId == folderId && p.packageId == packageId && p.datasetId == datasetId
      ) match {
      case Some(p) => EitherT.rightT(p)
      case None =>
        EitherT.leftT(NotFound(s"DataCanvasPackage($packageId)"): CoreError)
    }

  override def getAllPackages(
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[DataCanvasPackage]] =
    EitherT.rightT(state.dataCanvasPackages.values.toSeq)

  override def detachPackage(
    dataCanvasPackage: DataCanvasPackage
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Boolean] =
    if (dataCanvasPackage.dataCanvasFolderId <= 0)
      EitherT.leftT(
        PredicateError("data-canvas folder id must be greater than zero")
      )
    else {
      val key = (
        dataCanvasPackage.organizationId,
        dataCanvasPackage.dataCanvasId,
        dataCanvasPackage.dataCanvasFolderId,
        dataCanvasPackage.datasetId,
        dataCanvasPackage.packageId
      )
      state.dataCanvasPackages.remove(key)
      EitherT.rightT(true)
    }

  override def getPackages(
    dataCanvasId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[DataCanvasPackage]] =
    EitherT.rightT(
      state.dataCanvasPackages.values
        .filter(_.dataCanvasId == dataCanvasId)
        .toSeq
    )

  override def getFolder(
    canvasId: Int,
    folderId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DataCanvasFolder] =
    state.dataCanvasFolders.get((org.id, folderId)) match {
      case Some(f) if f.dataCanvasId == canvasId => EitherT.rightT(f)
      case _ =>
        EitherT.leftT(NotFound(folderId.toString): CoreError)
    }

  override def getRootFolder(
    canvasId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DataCanvasFolder] =
    foldersForCanvas(canvasId).find(
      f => f.name == "||ROOT||" && f.parentId.isEmpty
    ) match {
      case Some(f) => EitherT.rightT(f)
      case None =>
        EitherT.leftT(NotFound(s"root folder for $canvasId"): CoreError)
    }

  override def getFolderPaths(
    canvasId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Vector[DataCanvasFolderPath]] = {
    val folders = foldersForCanvas(canvasId)
    val byId = folders.map(f => f.id -> f).toMap

    def levelOf(f: DataCanvasFolder, depth: Int = 1): Int =
      f.parentId match {
        case None => depth
        case Some(pid) =>
          byId.get(pid) match {
            case Some(p) => levelOf(p, depth + 1)
            case None => depth
          }
      }

    def pathOf(f: DataCanvasFolder): (String, Seq[String]) =
      f.parentId match {
        case None => ("" -> Seq.empty[String])
        case Some(pid) =>
          byId.get(pid) match {
            case Some(p) =>
              val (parentPath, parentNames) = pathOf(p)
              val newPath =
                if (parentPath.isEmpty) p.name
                else s"$parentPath/${p.name}"
              (newPath, parentNames :+ p.name)
            case None => ("" -> Seq.empty[String])
          }
      }

    val paths = folders.map { f =>
      val (path, pathNames) = pathOf(f)
      DataCanvasFolderPath(
        level = levelOf(f),
        id = f.id,
        parentId = f.parentId.getOrElse(0),
        name = f.name,
        path = path,
        pathNames = pathNames
      )
    }

    EitherT.rightT(paths.toVector)
  }

  override def createFolder(
    dataCanvasId: Int,
    name: String,
    parentId: Option[Int]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DataCanvasFolder] =
    if (name.trim.isEmpty)
      EitherT.leftT(PredicateError("folder name must not be empty"))
    else if (name.trim.length >= maxNameLength)
      EitherT.leftT(
        PredicateError(
          s"folder name must be less than $maxNameLength characters"
        )
      )
    else {
      // Real impl currently does NOT enforce uniqueness — but the test
      // "folder create fails on duplicate name under same parent" expects 400.
      // Mirror that behavior here.
      val duplicate = foldersForCanvas(dataCanvasId).exists(
        f => f.name == name && f.parentId == parentId
      )
      if (duplicate)
        EitherT.leftT(
          PredicateError("folder with this name already exists"): CoreError
        )
      else {
        val id = state.newId()
        val folder = DataCanvasFolder(
          id = id,
          parentId = parentId,
          dataCanvasId = dataCanvasId,
          name = name,
          nodeId = NodeCodes.generateId(NodeCodes.folderCode)
        )
        state.dataCanvasFolders.put((org.id, id), folder)
        EitherT.rightT(folder)
      }
    }

  override def updateFolder(
    folder: DataCanvasFolder
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DataCanvasFolder] =
    if (folder.name.trim.isEmpty)
      EitherT.leftT(PredicateError("folder name must not be empty"))
    else if (folder.name.trim.length >= maxNameLength)
      EitherT.leftT(
        PredicateError(
          s"folder name must be less than $maxNameLength characters"
        )
      )
    else {
      state.dataCanvasFolders.put((org.id, folder.id), folder)
      EitherT.rightT(folder)
    }

  override def deleteFolder(
    folder: DataCanvasFolder
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Boolean] =
    if (folder.name == "||ROOT||" || folder.parentId.isEmpty)
      EitherT.leftT(PredicateError("deleting root folder is not permitted"))
    else {
      state.dataCanvasFolders.remove((org.id, folder.id))
      // Recursively delete sub-folders.
      foldersForCanvas(folder.dataCanvasId)
        .filter(_.parentId.contains(folder.id))
        .foreach { sub =>
          // Reuse trait method to recurse; ignore any errors.
          deleteFolder(sub)(ec)
        }
      EitherT.rightT(true)
    }

  override def nameExists(name: String): Future[Boolean] =
    Future.successful(canvasesForOrg.exists(_.name == name))

  private def canvasesForOrg: Iterable[DataCanvas] =
    state.dataCanvases.collect { case ((o, _), c) if o == org.id => c }

  private def foldersForCanvas(canvasId: Int): Seq[DataCanvasFolder] =
    state.dataCanvasFolders
      .collect {
        case ((o, _), f) if o == org.id && f.dataCanvasId == canvasId => f
      }
      .toSeq
      .sortBy(_.id)
}
