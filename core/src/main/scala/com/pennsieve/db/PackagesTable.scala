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

package com.pennsieve.db

import cats._
import cats.implicits._
import com.pennsieve.core.utilities.recommendName
import com.pennsieve.domain.{
  CoreError,
  Error,
  NameCheckError,
  PredicateError,
  SqlError
}
import com.pennsieve.models.{
  CollectionUpload,
  Dataset,
  FileObjectType,
  ModelProperty,
  Organization,
  Package,
  PackageState,
  PackageType
}
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.traits.PostgresProfile.ProfileAction
import com.rms.miu.slickcats.DBIOInstances._
import java.util.UUID
import java.time.ZonedDateTime

import scala.collection.compat._
import scala.concurrent.{ ExecutionContext, Future }
import slick.dbio.Effect
import slick.jdbc.GetResult
import slick.sql.FixedSqlAction

final class PackagesTable(schema: String, tag: Tag)
    extends Table[Package](tag, Some(schema), "packages") {

  // set by the database
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)

  def name = column[String]("name")
  def `type` = column[PackageType]("type")
  def state = column[PackageState]("state")
  def nodeId = column[String]("node_id")
  def parentId = column[Option[Int]]("parent_id")
  def datasetId = column[Int]("dataset_id")
  def attributes = column[List[ModelProperty]]("attributes")
  def ownerId = column[Option[Int]]("owner_id")
  def importId = column[Option[UUID]]("import_id")

  def * =
    (
      nodeId,
      name,
      `type`,
      datasetId,
      ownerId,
      state,
      parentId,
      importId,
      createdAt,
      updatedAt,
      id,
      attributes
    ).mapTo[Package]
}

class PackagesMapper(val organization: Organization)
    extends TableQuery(new PackagesTable(organization.schemaId, _)) {

  def get(id: Int): Query[PackagesTable, Package, Seq] =
    this.filter(_.id === id)

  def getPackage(
    id: Int
  )(implicit
    executionContext: ExecutionContext
  ): DBIO[Package] = {
    this
      .get(id)
      .result
      .headOption
      .flatMap {
        case None => DBIO.failed(SqlError(s"No package with id $id exists"))
        case Some(pkg) => DBIO.successful(pkg)
      }
  }

  def getByNodeId(nodeId: String) = this.filter(_.nodeId === nodeId)
  def getByOwnerId(userId: Int) = this.filter(_.ownerId === userId)
  def getByIds(ids: Set[Int]) = this.filter(_.id inSet ids)

  def getNodeId(
    id: Int
  )(implicit
    executionContext: ExecutionContext
  ): DBIO[String] = {
    this
      .filter(_.id === id)
      .map(_.nodeId)
      .result
      .headOption
      .flatMap {
        case None => DBIO.failed(SqlError(s"No package with id $id exists"))
        case Some(nodeId) => DBIO.successful(nodeId)
      }
  }

  def addAttribute(
    id: Int,
    attribute: ModelProperty
  )(implicit
    executionContext: ExecutionContext
  ): DBIO[Int] = {
    for {
      attributes <- this.get(id).map(_.attributes).result.head
      newAttributes = ModelProperty.merge(attributes, List(attribute))
      result <- this.get(id).map(_.attributes).update(newAttributes)
    } yield result
  }

  def getParent(p: Package) = p.parentId match {
    case Some(parentId) => get(parentId).result.headOption
    case None => DBIO.successful(None)
  }

  def updateState(
    id: Int,
    state: PackageState
  ): ProfileAction[Int, NoStream, Effect.Write] =
    this.get(id).map(_.state).update(state)

  def batchUpdateState(
    ids: Set[Int],
    state: PackageState
  ): ProfileAction[Int, NoStream, Effect.Write] =
    this.filter(_.id inSet ids).map(_.state).update(state)

  def updateType(
    id: Int,
    `type`: PackageType
  ): FixedSqlAction[Int, NoStream, Effect.Write] = {
    this.get(id).map(_.`type`).update(`type`)
  }

  /*
   * Note: This query excludes packages in the DELETING state
   */
  def children(
    parent: Option[Package],
    dataset: Dataset
  ): Query[PackagesTable, Package, Seq] =
    this
      .filter(hasParent(_, parent))
      .filter(_.datasetId === dataset.id)
      .filter(_.state =!= (PackageState.DELETING: PackageState))

  def hasParent(
    that: PackagesTable,
    parent: Option[Package]
  ): Rep[Option[Boolean]] =
    parent match {
      case Some(p) => that.parentId === p.id
      case None => that.parentId.isEmpty.?
    }

  /**
    * Find a unique name for a package, given its location in the dataset
    * directory hierarchy and package type.
    */
  def checkName(
    name: String,
    parent: Option[Package],
    dataset: Dataset,
    packageType: PackageType,
    duplicateThreshold: Int = 100
  )(implicit
    ec: ExecutionContext
  ): DBIO[Either[NameCheckError, String]] =
    for {
      children <- this
        .children(parent, dataset)
        .filter(hasPackageType(_, packageType))
        .map(_.name)
        .result
        .map(_.toSet)
    } yield
      recommendName(
        name.trim,
        children,
        duplicateThreshold = duplicateThreshold
      )

  /**
    * Check if a name is unique at this level in the dataset directory
    * hierarchy, for the given package type.
    */
  def assertNameIsUnique(
    name: String,
    parent: Option[Package],
    dataset: Dataset,
    packageType: PackageType
  )(implicit
    ec: ExecutionContext
  ): DBIO[Unit] =
    for {
      nameExists <- this
        .children(parent, dataset)
        .filter(_.name === name)
        .filter(hasPackageType(_, packageType))
        .exists
        .result

      _ <- assert(!nameExists)(PredicateError("package name must be unique"))
    } yield ()

  def hasPackageType(
    that: PackagesTable,
    packageType: PackageType
  ): Rep[Boolean] =
    packageType match {
      case PackageType.Collection =>
        that.`type` === (PackageType.Collection: PackageType)
      case _ =>
        that.`type` =!= (PackageType.Collection: PackageType)
    }

  def getDatasetAndOwner(p: Package) = {
    val datasets = new DatasetsMapper(organization)
    for {
      ((_, dataset), user) <- (this
        .filter(_.id === p.id)
        join datasets on (_.datasetId === _.id)
        join UserMapper on (_._1.ownerId === _.id))
    } yield (dataset, user)
  }

  def sourceSize(p: Package) = {
    val files = new FilesMapper(organization)
    val sourceType: FileObjectType = FileObjectType.Source
    files.getByPackage(p).filter(_.objectType === sourceType).map(_.size).sum
  }

  def switchOwner(oldOwnerId: Int, newOwnerId: Option[Int]) =
    this
      .getByOwnerId(oldOwnerId)
      .map(p => p.ownerId)
      .update(newOwnerId) // If ownerId is None, then clear the package owner

  // This pattern shouldn't be followed, we should try and keep the `db` object
  // out of this class. We're breaking this rule due to the recursive nature of
  // this function and the need to operate on the database's results.
  def descendants(
    p: Package,
    db: Database
  )(implicit
    ec: ExecutionContext
  ): Future[Set[Int]] = {
    val nodeId = p.nodeId
    db.run {
        sql"""
          WITH RECURSIVE parents AS (
          SELECT
            id,
            parent_id,
            node_id,
            state
          FROM
            "#${organization.schemaId}".packages
          WHERE
            node_id = $nodeId
          UNION
          SELECT
            children.id,
            children.parent_id,
            children.node_id,
            children.state
          FROM
            "#${organization.schemaId}".packages children
          INNER JOIN parents ON
            parents.id = children.parent_id
        )
        SELECT
          parents.id as package_id
        FROM
          parents
        WHERE
          parents.state != '#${PackageState.DELETING.entryName}'
        AND parents.node_id != $nodeId
      """.as[Int]
      }
      .map(_.to(Set))
  }

  type PackagesSeen = Set[Int]
  type AncestorsResult = Either[CoreError, List[Package]]
  type AncestorsAccumulator = (Package, AncestorsResult, PackagesSeen)

  def ancestors(
    p: Package,
    db: Database
  )(implicit
    ec: ExecutionContext
  ): Future[AncestorsResult] = {
    val rec: AncestorsAccumulator => Future[
      Either[AncestorsAccumulator, AncestorsResult]
    ] = {
      case (current, ancestors, seen) =>
        current.parentId match {
          case None => Future(ancestors.asRight)
          case Some(parentId) =>
            if (seen contains parentId) {
              Future(
                Error(
                  s"circular dependency found for package ${current.nodeId}"
                ).asLeft.asRight
              )
            } else {
              db.run(
                  this
                    .filter(_.id === parentId)
                    .result
                    .headOption
                )
                .map {
                  case None =>
                    Error(
                      s"failed to fetch package with id $parentId from the database"
                    ).asLeft.asRight
                  case Some(parent) =>
                    (
                      parent,
                      ancestors.map(as => parent :: as),
                      seen + parent.id
                    ).asLeft
                }
            }
        }
    }

    val init = (p, List.empty.asRight, Set(p.id)): AncestorsAccumulator

    FlatMap[Future].tailRecM[AncestorsAccumulator, AncestorsResult](init)(rec)
  }

  /**
    * Create a single collection in the database
    * @param collectionUpload
    * @param parent
    * @param dataset
    * @param ownerId
    * @return
    */
  def createCollection(
    collectionUpload: CollectionUpload,
    parent: Option[Package],
    dataset: Dataset,
    ownerId: Option[Int]
  )(implicit
    ec: ExecutionContext
  ): DBIO[Package] = {

    for {

      _ <- parent match {
        case None => DBIO.successful(())
        case Some(p) =>
          assert(p.datasetId == dataset.id)(
            PredicateError(
              "a package must belong to the same dataset as its parent"
            )
          )
      }

      _ <- assert(collectionUpload.name.trim.nonEmpty)(
        PredicateError("package name must not be empty")
      )

      checkedName <- this
        .checkName(
          collectionUpload.name,
          parent,
          dataset,
          PackageType.Collection
        )
        .map {
          case Left(NameCheckError(recommendation, _)) => recommendation
          case Right(name) => name
        }

      _ <- this.assertNameIsUnique(
        checkedName,
        parent,
        dataset,
        PackageType.Collection
      )

      row = Package(
        collectionUpload.id.value,
        checkedName.trim,
        PackageType.Collection,
        dataset.id,
        ownerId,
        PackageState.READY,
        parent.map(_.id)
      )

      packageId <- this
        .filter(_.nodeId === collectionUpload.id.value)
        .result
        .headOption
        .flatMap {
          case Some(existingCollection) =>
            DBIO.successful(existingCollection.id)
          case None => this returning this.map(_.id) += row
        }

      newCollection <- this
        .get(packageId)
        .result
        .head

    } yield newCollection

  }

  type CreateCollectionAccumulator =
    (Package, List[CollectionUpload], List[Package])

  /**
    * Given a root collection, recursively create collections nested within it
    * @param startingCollection
    * @param dataset
    * @param collectionUploads
    * @param ownerId
    * @param executionContext
    * @return
    */
  def createNestedCollections(
    startingCollection: Package,
    dataset: Dataset,
    collectionUploads: List[CollectionUpload],
    ownerId: Option[Int]
  )(implicit
    executionContext: ExecutionContext
  ): DBIO[List[Package]] = {

    val recursiveFn: CreateCollectionAccumulator => DBIO[
      Either[CreateCollectionAccumulator, List[Package]]
    ] = {
      case (currentCollection, collectionToCreate, collectionsCreated) =>
        collectionToCreate match {
          case Nil => DBIO.successful(collectionsCreated.asRight)
          case head :: tail =>
            createCollection(head, Some(currentCollection), dataset, ownerId)
              .map { newCollection =>
                (newCollection, tail, collectionsCreated :+ newCollection).asLeft
              }
        }
    }

    val init = (startingCollection, collectionUploads, List.empty)

    FlatMap[DBIO].tailRecM[CreateCollectionAccumulator, List[Package]](init)(
      recursiveFn
    )
  }

}
