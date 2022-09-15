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
import com.pennsieve.core.utilities.{ checkOrErrorT, FutureEitherHelpers }
import com.pennsieve.db.{
  AllDataCanvasesViewMapper,
  DataCanvasFolderMapper,
  DataCanvasMapper,
  DataCanvasPackageMapper,
  DataCanvasUserMapper,
  PackagesMapper
}
import com.pennsieve.domain.{
  CoreError,
  NotFound,
  PredicateError,
  ServiceError,
  SqlError
}
import com.pennsieve.models.{
  DBPermission,
  DataCanvas,
  DataCanvasContent,
  DataCanvasFolder,
  DataCanvasFolderPath,
  DataCanvasFoldersAndPackages,
  DataCanvasPackage,
  DataCanvasUser,
  NodeCodes,
  Organization,
  Package,
  Role,
  User
}
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits.{
  FutureEitherT,
  _
}
import com.pennsieve.messages.BackgroundJob
import org.postgresql.util.PSQLException

import scala.concurrent.{ ExecutionContext, Future }
import slick.dbio.DBIO
import slick.jdbc.GetResult

object DataCanvasManager {
  val maxNameLength = 256
}

class DataCanvasManager(
  val db: Database,
  val actor: User,
  val datacanvasMapper: DataCanvasMapper
) {
  import DataCanvasManager._

  val organization: Organization = datacanvasMapper.organization

  implicit val dataCanvasUser: DataCanvasUserMapper = new DataCanvasUserMapper(
    organization
  )
  val dataCanvasPackageMapper = new DataCanvasPackageMapper(organization)
  val dataCanvasFolderMapper = new DataCanvasFolderMapper(organization)

  val datasetStatusManager: DatasetStatusManager =
    new DatasetStatusManager(db, organization)

  val packagesMapper: PackagesMapper = new PackagesMapper(organization)

  def isLocked(
    dataCanvas: DataCanvas
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Boolean] =
    db.run(datacanvasMapper.isLocked(dataCanvas, actor)).toEitherT

  def isFolderLocked(
    folder: DataCanvasFolder
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Boolean] =
    db.run(dataCanvasFolderMapper.isLocked(folder, actor)).toEitherT

  def create(
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
  ): EitherT[Future, CoreError, DataCanvas] = {
    //val nodeId = NodeCodes.generateId(NodeCodes.dataCanvasCode)

    for {
      _ <- FutureEitherHelpers.assert(name.trim.nonEmpty)(
        PredicateError("data-canvas name must not be empty")
      )

      nameExists <- nameExists(name.trim).toEitherT
      _ <- FutureEitherHelpers.assert(!nameExists)(
        PredicateError("data-canvas name must be unique")
      )

      _ <- checkOrErrorT(name.trim.length < maxNameLength)(
        PredicateError(
          s"dataset name must be less than ${maxNameLength} characters"
        )
      )

      createdDataCanvas = for {
        dataCanvasStatus <- statusId
          .map(datasetStatusManager.getById(_))
          .getOrElse(datasetStatusManager.getDefaultStatus)

        dataCanvas <- (datacanvasMapper returning datacanvasMapper) += DataCanvas(
          nodeId = nodeId,
          name = name,
          description = description,
          role = role,
          statusId = dataCanvasStatus.id,
          permissionBit = permissionBit.getOrElse(0),
          isPublic = isPublic.getOrElse(false)
        )

        _ <- dataCanvasUser += DataCanvasUser(
          dataCanvas.id,
          userId,
          DBPermission.Owner,
          Some(Role.Owner)
        )

      } yield dataCanvas

      dataCanvas <- db.run(createdDataCanvas.transactionally).toEitherT

      // create root folder
      _ <- createFolder(dataCanvas.id, "||ROOT||", None)

    } yield dataCanvas

  }

  def getAll(
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[DataCanvas]] =
    db.run(datacanvasMapper.result).toEitherT

  def getById(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DataCanvas] =
    db.run(
        datacanvasMapper
          .filter(_.id === id)
          .result
          .headOption
      )
      .whenNone(NotFound(id.toString))

  def getByNodeId(
    nodeId: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DataCanvas] =
    db.run(
        datacanvasMapper
          .filter(_.nodeId === nodeId)
          .result
          .headOption
      )
      .whenNone(NotFound(nodeId))

  def getForUser(
    userId: Int = actor.id,
    withRole: Role = Role.Owner,
    restrictToRole: Boolean = false
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[DataCanvas]] = {
    val query = dataCanvasUser.maxRoles(userId).flatMap {
      roleMap: Map[Int, Option[Role]] =>
        {
          val dataCanvasIds: List[Int] = roleMap
            .filter {
              case (_, Some(role)) =>
                if (restrictToRole) role == withRole
                else role >= withRole
              case (_, None) => false
            }
            .keys
            .toList

          val query = datacanvasMapper.filter(_.id.inSet(dataCanvasIds))

          for {
            datacanvases <- query.result
          } yield datacanvases
        }
    }
    db.run(query).toEitherT
  }

  def update(
    dataCanvas: DataCanvas,
    checkforDuplicateNames: Boolean = true
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DataCanvas] = {
    for {
      _ <- FutureEitherHelpers.assert(dataCanvas.name.trim.nonEmpty)(
        PredicateError("data-canvas name must not be empty")
      )

      currentDataCanvas <- db
        .run(
          datacanvasMapper
            .filter(_.name === dataCanvas.name)
            .result
            .headOption
        )
        .map(_.getOrElse(dataCanvas))
        .toEitherT

      _ <- FutureEitherHelpers.assert(
        (currentDataCanvas.id == dataCanvas.id) || !checkforDuplicateNames
      )(ServiceError(s"name is already taken: ${dataCanvas.name}"))

      query = for {
        _ <- datacanvasMapper
          .filter(_.id === dataCanvas.id)
          .update(dataCanvas)
      } yield ()

      _ <- db
        .run(query.transactionally)
        .toEitherT[CoreError] {
          case e: PSQLException
              if (e.getMessage() == "ERROR: value too long for type character varying(255)") =>
            PredicateError(
              s"data-canvas name must be less than ${maxNameLength} characters"
            ): CoreError
        }

      updatedDataCanvas <- getById(dataCanvas.id)

    } yield updatedDataCanvas
  }

  def delete(
    dataCanvas: DataCanvas
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Boolean] = {
    for {
      _ <- FutureEitherHelpers.assert(dataCanvas.name.trim.nonEmpty)(
        PredicateError("data-canvas name must not be empty")
      )
      query = for {
        _ <- datacanvasMapper
          .filter(_.id === dataCanvas.id)
          .delete
      } yield ()

      _ <- db
        .run(query.transactionally)
        .toEitherT[CoreError] {
          case e: PSQLException =>
            SqlError(e.getMessage()): CoreError
        }

    } yield true
  }

  def attachPackage(
    dataCanvasId: Int,
    folderId: Int,
    datasetId: Int,
    packageId: Int,
    organizationId: Int = organization.id
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DataCanvasPackage] = {
    for {
      _ <- getById(dataCanvasId)

      attachedDataCanvasPackage = for {
        dataCanvasPackage <- (dataCanvasPackageMapper returning dataCanvasPackageMapper) += DataCanvasPackage(
          organizationId,
          packageId,
          datasetId,
          folderId,
          dataCanvasId
        )
      } yield dataCanvasPackage

      dataCanvasPackage <- db
        .run(attachedDataCanvasPackage.transactionally)
        .toEitherT

    } yield dataCanvasPackage
  }

  def getPackage(
    folderId: Int,
    packageId: Int,
    datasetId: Int,
    organizationId: Option[Int] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DataCanvasPackage] = {
    val query = dataCanvasPackageMapper
      .filter(_.dataCanvasFolderId === folderId)
      .filter(_.datasetId === datasetId)
      .filter(_.packageId === packageId)
      .result
      .head

    db.run(query).toEitherT
  }

  def getAllPackages(
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[DataCanvasPackage]] =
    db.run(dataCanvasPackageMapper.result).toEitherT

  def detachPackage(
    dataCanvasPackage: DataCanvasPackage
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Boolean] =
    for {
      _ <- FutureEitherHelpers.assert(dataCanvasPackage.dataCanvasFolderId > 0)(
        PredicateError("data-canvas folder id must be greater than zero")
      )

      query = for {
        _ <- dataCanvasPackageMapper
          .filter(_.dataCanvasFolderId === dataCanvasPackage.dataCanvasFolderId)
          .filter(_.datasetId === dataCanvasPackage.datasetId)
          .filter(_.organizationId === dataCanvasPackage.organizationId)
          .filter(_.packageId === dataCanvasPackage.packageId)
          .delete
      } yield ()

      _ <- db
        .run(query.transactionally)
        .toEitherT[CoreError] {
          case e: PSQLException =>
            SqlError(e.getMessage()): CoreError
        }
    } yield true

  def getPackages(
    dataCanvasId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[DataCanvasPackage]] =
    db.run(
        dataCanvasPackageMapper
          .filter(_.dataCanvasId === dataCanvasId)
          .result
      )
      .toEitherT

  def getFolder(
    canvasId: Int,
    folderId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DataCanvasFolder] = {
    val query = dataCanvasFolderMapper
      .filter(_.id === folderId)
      .filter(_.dataCanvasId === canvasId)
      .result
      .head

    db.run(query).toEitherT
  }

  def getRootFolder(
    canvasId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DataCanvasFolder] = {
    val query = dataCanvasFolderMapper
      .filter(_.dataCanvasId === canvasId)
      .filter(_.name === "||ROOT||")
      .filter(_.parentId.isEmpty)
      .result
      .head

    db.run(query).toEitherT
  }

  def getFolderPaths(
    canvasId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Vector[DataCanvasFolderPath]] = {
    implicit val getFolderResult = GetResult(
      r => DataCanvasFolderPath(r.<<, r.<<, r.<<, r.<<, r.<<, r <<)
    )
    val query =
      sql"""
          WITH RECURSIVE datacanvas_folder_tree AS (
          -- seed
          SELECT 1 as level,
                 id,
                 parent_id,
                 name,
                 cast('' as varchar(255)) as path,
                 ARRAY[]::VARCHAR[] AS name_path
          FROM "#${organization.schemaId}".datacanvas_folder
          WHERE "#${organization.schemaId}".datacanvas_folder.datacanvas_id = #${canvasId}
            and "#${organization.schemaId}".datacanvas_folder.parent_id is null
        UNION ALL
          -- recursive
          SELECT r.level+1,
                 t.id,
                 t.parent_id,
                 t.name,
                 CAST(concat_ws('/', r.path, t.name) as varchar(255)) as path,
                 (r.name_path || t.name)::VARCHAR[] AS name_path
          FROM "#${organization.schemaId}".datacanvas_folder t
          JOIN datacanvas_folder_tree r ON t.parent_id = r.id
        )
        SELECT * FROM datacanvas_folder_tree;
       """
        .as[DataCanvasFolderPath]

    db.run(query).toEitherT
  }

  def createFolder(
    dataCanvasId: Int,
    name: String,
    parentId: Option[Int]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DataCanvasFolder] = {
    val nodeId = NodeCodes.generateId(NodeCodes.folderCode)

    for {
      _ <- FutureEitherHelpers.assert(name.trim.nonEmpty)(
        PredicateError("folder name must not be empty")
      )

      // TODO: check that name does not exist (this could be enforced by a unique index on canvasId, parentId and name)

      _ <- checkOrErrorT(name.trim.length < maxNameLength)(
        PredicateError(
          s"folder name must be less than ${maxNameLength} characters"
        )
      )

      createdFolder = for {
        folder <- (dataCanvasFolderMapper returning dataCanvasFolderMapper) += DataCanvasFolder(
          name = name,
          nodeId = nodeId,
          parentId = parentId,
          dataCanvasId = dataCanvasId
        )
      } yield folder

      folder <- db.run(createdFolder.transactionally).toEitherT
    } yield folder
  }

  def updateFolder(
    folder: DataCanvasFolder
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DataCanvasFolder] = {
    for {
      _ <- FutureEitherHelpers.assert(folder.name.trim.nonEmpty)(
        PredicateError("folder name must not be empty")
      )

      _ <- checkOrErrorT(folder.name.trim.length < maxNameLength)(
        PredicateError(
          s"folder name must be less than ${maxNameLength} characters"
        )
      )

      query = for {
        _ <- dataCanvasFolderMapper
          .filter(_.id === folder.id)
          .update(folder)
      } yield ()

      _ <- db
        .run(query.transactionally)
        .toEitherT[CoreError] {
          case e: PSQLException
              if (e.getMessage() == "ERROR: value too long for type character varying(255)") =>
            PredicateError(
              s"folder name must be less than ${maxNameLength} characters"
            ): CoreError
        }

      updatedFolder <- getFolder(folder.dataCanvasId, folder.id)

    } yield updatedFolder
  }

  def deleteFolder(
    folder: DataCanvasFolder
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Boolean] = {
    for {
      _ <- FutureEitherHelpers.assert(
        (folder.name.compareTo("||ROOT||") != 0) && (!folder.parentId.isEmpty)
      )(PredicateError("deleting root folder is not permitted"))
      query = for {
        _ <- dataCanvasFolderMapper
          .filter(_.id === folder.id)
          .delete
      } yield ()

      _ <- db
        .run(query.transactionally)
        .toEitherT[CoreError] {
          case e: PSQLException =>
            SqlError(e.getMessage()): CoreError
        }

    } yield true
  }

  def nameExists(name: String): Future[Boolean] =
    db.run(datacanvasMapper.nameExists(name))

}

object AllDataCanvasesViewManager

class AllDataCanvasesViewManager(
  val db: Database,
  val actor: User,
  val allDataCanvasesViewMapper: AllDataCanvasesViewMapper
) {
  import AllDataCanvasesViewManager._

  def get(
    nodeId: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Int, DataCanvas)] =
    db.run(
        allDataCanvasesViewMapper
          .filter(_.nodeId === nodeId)
          .result
          .headOption
      )
      .whenNone(NotFound(nodeId))

  def getForOrganization(
    orgId: Int,
    isPublic: Boolean = false
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[(Int, DataCanvas)]] =
    db.run(
        allDataCanvasesViewMapper
          .filter(_.organizationId === orgId)
          .filter(_.isPublic === isPublic)
          .result
      )
      .toEitherT
}
