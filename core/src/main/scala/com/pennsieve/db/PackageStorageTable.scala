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

import com.pennsieve.models._
import com.pennsieve.db._
import com.pennsieve.traits.PostgresProfile.api._

import scala.concurrent.ExecutionContext

final class PackageStorageTable(schema: String, tag: Tag)
    extends Table[PackageStorage](tag, Some(schema), "package_storage") {

  def packageId = column[Int]("package_id", O.PrimaryKey)
  def size = column[Option[Long]]("size")

  def * = (packageId, size).mapTo[PackageStorage]
}

class PackageStorageMapper(val organization: Organization)
    extends TableQuery(new PackageStorageTable(organization.schemaId, _)) {

  def incrementPackage(
    packageId: Int,
    size: Long
  )(implicit
    ec: ExecutionContext
  ): DBIO[Int] =
    sql"""
       INSERT INTO "#${organization.schemaId}".package_storage
       AS package_storage (package_id, size)
       VALUES ($packageId, $size)
       ON CONFLICT (package_id)
       DO UPDATE SET size = COALESCE(package_storage.size, 0) + EXCLUDED.size
       """
      .as[Int]
      .map(_.headOption.getOrElse(0))

  /**
    * Traverse the directory hierarchy upwards, incrementing storage for all
    * packages that are ancestors of this one.
    */
  def incrementPackageAncestors(
    pkg: Package,
    size: Long
  )(implicit
    ec: ExecutionContext
  ): DBIO[Int] =
    sql"""
       WITH RECURSIVE ancestors(id, parent_id) AS (
         SELECT
           packages.id,
           packages.parent_id
         FROM "#${organization.schemaId}".packages packages
         WHERE packages.id = ${pkg.parentId}
       UNION
         SELECT
           parents.id,
           parents.parent_id
         FROM "#${organization.schemaId}".packages parents
         JOIN ancestors ON ancestors.parent_id = parents.id
       )
       INSERT INTO "#${organization.schemaId}".package_storage
       AS package_storage (package_id, size)
       SELECT id, $size FROM ancestors
       ON CONFLICT (package_id)
       DO UPDATE SET size = COALESCE(package_storage.size, 0) + EXCLUDED.size
       """
      .as[Int]
      .map(_.headOption.getOrElse(0))

  def refreshPackageStorage(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): DBIO[Int] =
    sql"""
       WITH RECURSIVE children(id, parent_id, size, path) AS (
         SELECT
           packages.id,
           packages.parent_id,
           SUM(files.size),
           ARRAY[packages.id]
         FROM "#${organization.schemaId}".packages packages
         JOIN "#${organization.schemaId}".files files
         ON packages.id = files.package_id
         WHERE packages.dataset_id = ${dataset.id}
         AND files.object_type = ${FileObjectType.Source.entryName}
         AND packages.state NOT IN (
           ${PackageState.UNAVAILABLE.entryName},
           ${PackageState.DELETING.entryName},
           ${PackageState.DELETED.entryName},
           ${PackageState.RESTORING.entryName}
         )
         GROUP BY packages.id
       UNION ALL
         SELECT
           parents.id,
           parents.parent_id,
           -- If the collection is deleting set its size to 0. It is ok for
           -- child packages to still set sizes, as long as those sizes don't
           -- propagate up the tree.
           CASE
             WHEN parents.state IN (
              ${PackageState.DELETING.entryName}, 
              ${PackageState.DELETED.entryName},
              ${PackageState.RESTORING.entryName}
             )
             THEN NULL
             ELSE children.size
           END,
           children.path || parents.id
         FROM "#${organization.schemaId}".packages parents
         JOIN children ON children.parent_id = parents.id
         -- Pathological case: break cycles in the directory structure
         WHERE NOT parents.id = ANY(children.path)
       )
       INSERT INTO "#${organization.schemaId}".package_storage (package_id, size)
       SELECT id, size FROM (
         SELECT id, SUM(size) AS size
         FROM children
         GROUP BY id
       ) AS subquery
       ON CONFLICT (package_id)
       DO UPDATE SET size = EXCLUDED.size
     """
      .as[Int]
      .map(_.headOption.getOrElse(0))

}
