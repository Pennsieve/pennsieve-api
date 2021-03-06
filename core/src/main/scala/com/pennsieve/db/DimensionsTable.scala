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

import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.models.{ Dimension, DimensionAssignment, Organization }
import java.time.ZonedDateTime

import com.pennsieve.domain.SqlError

import scala.concurrent.ExecutionContext

final class DimensionsTable(schema: String, tag: Tag)
    extends Table[Dimension](tag, Some(schema), "dimensions") {

  // set by the database
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)

  def packageId = column[Int]("package_id")
  def name = column[String]("name")
  def length = column[Long]("length")
  def resolution = column[Option[Double]]("resolution")
  def unit = column[Option[String]]("unit")
  def assignment = column[DimensionAssignment]("assignment")

  def * =
    (
      packageId,
      name,
      length,
      resolution,
      unit,
      assignment,
      createdAt,
      updatedAt,
      id
    ).mapTo[Dimension]
}

class DimensionsMapper(val organization: Organization)
    extends TableQuery(new DimensionsTable(organization.schemaId, _)) {
  def get(id: Int) = this.filter(_.id === id)
  def getByPackageId(packageId: Int) = this.filter(_.packageId === packageId)

  def getDimension(
    id: Int
  )(implicit
    executionContext: ExecutionContext
  ): DBIO[Dimension] = {
    this
      .get(id)
      .result
      .headOption
      .flatMap {
        case None => DBIO.failed(SqlError(s"No dimension with id ($id) exists"))
        case Some(dimension) => DBIO.successful(dimension)
      }
  }

}
