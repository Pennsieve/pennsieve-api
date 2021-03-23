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
import com.pennsieve.traits.PostgresProfile.api._

import scala.concurrent.ExecutionContext

final class OrganizationStorageTable(tag: Tag)
    extends Table[OrganizationStorage](
      tag,
      Some("pennsieve"),
      "organization_storage"
    ) {

  def organizationId = column[Int]("organization_id", O.PrimaryKey)
  def size = column[Option[Long]]("size")

  def * = (organizationId, size).mapTo[OrganizationStorage]
}

object OrganizationStorageMapper
    extends TableQuery(new OrganizationStorageTable(_)) {

  def incrementOrganization(
    organizationId: Int,
    size: Long
  )(implicit
    ec: ExecutionContext
  ): DBIO[Int] =
    sql"""
       INSERT INTO pennsieve.organization_storage
       AS organization_storage (organization_id, size)
       VALUES ($organizationId, $size)
       ON CONFLICT (organization_id)
       DO UPDATE SET size = COALESCE(organization_storage.size, 0) + EXCLUDED.size
      """
      .as[Int]
      .map(_.headOption.getOrElse(0))

  def setOrganization(
    organizationId: Int,
    size: Long
  )(implicit
    ec: ExecutionContext
  ): DBIO[Int] =
    sql"""
       INSERT INTO pennsieve.organization_storage (organization_id, size)
       VALUES ($organizationId, $size)
       ON CONFLICT (organization_id)
       DO UPDATE SET size = EXCLUDED.size
       """
      .as[Int]
      .map(_.headOption.getOrElse(0))
}
