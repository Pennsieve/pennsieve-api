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

import com.pennsieve.models.{ DataCanvasPackage, Organization }
import com.pennsieve.traits.PostgresProfile.api._
import slick.lifted.Tag

final class DataCanvasPackageTable(schema: String, tag: Tag)
    extends Table[DataCanvasPackage](tag, Some(schema), "datacanvas_package") {

  def dataCanvasId: Rep[Int] = column[Int]("datacanvas_id")
  def organizatiodId: Rep[Int] = column[Int]("organization_id")
  def packageId: Rep[Int] = column[Int]("package_id")
  def datasetId: Rep[Int] = column[Int]("dataset_id")

  def * =
    (dataCanvasId, organizatiodId, packageId, datasetId)
      .mapTo[DataCanvasPackage]
}

class DataCanvasPackageMapper(val organization: Organization)
    extends TableQuery(new DataCanvasPackageTable(organization.schemaId, _)) {

  def get(
    dataCanvasId: Int,
    datasetId: Int,
    packageId: Int
  ): Query[DataCanvasPackageTable, DataCanvasPackage, Seq] =
    this
      .filter(_.dataCanvasId === dataCanvasId)
      .filter(_.datasetId === datasetId)
      .filter(_.packageId === packageId)
}