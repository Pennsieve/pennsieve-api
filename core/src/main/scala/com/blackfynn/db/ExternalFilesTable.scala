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

import java.time.ZonedDateTime

import com.pennsieve.models.{ ExternalFile, Organization, Package }
import com.pennsieve.traits.PostgresProfile.api._

final class ExternalFilesTable(schema: String, tag: Tag)
    extends Table[ExternalFile](tag, Some(schema), "externally_linked_files") {

  def packageId = column[Int]("package_id", O.PrimaryKey)
  def description = column[Option[String]]("description")
  def location = column[String]("location")
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)

  def * =
    (packageId, location, description, createdAt, updatedAt)
      .mapTo[ExternalFile]
}

class ExternalFilesMapper(val organization: Organization)
    extends TableQuery(new ExternalFilesTable(organization.schemaId, _)) {

  def get(`package`: Package) =
    this.filter(_.packageId === `package`.id)

  def get(packages: Seq[Package]) =
    this.filter(_.packageId inSetBind packages.map(_.id))
}
