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

package com.pennsieve.etl.`data-cli`

import java.util.UUID
import com.pennsieve.db._
import com.pennsieve.managers.DatasetStatusManager
import com.pennsieve.models.{
  Dataset,
  File,
  NodeCodes,
  Package,
  PackageState,
  PackageType
}
import com.pennsieve.traits.PostgresProfile.api._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext.Implicits.global

trait DataCLIDatabaseSpecHarness
    extends AnyFlatSpec
    with DataCLISpecHarness
    with Matchers {

  val files = new FilesMapper(organization)
  val packages = new PackagesMapper(organization)
  val datasets = new DatasetsMapper(organization)
  val channels = new ChannelsMapper(organization)

  def createDataset: Dataset = {

    val status = new DatasetStatusManager(dataCLIContainer.db, organization)
      .create("Test")
      .await
      .toOption
      .get

    val dataset = Dataset(
      nodeId = NodeCodes.generateId(NodeCodes.dataSetCode),
      name = "Test Dataset",
      statusId = status.id
    )

    val id: Int = dataCLIContainer.db
      .run((datasets returning datasets.map(_.id)) += dataset)
      .await

    dataset.copy(id = id)
  }

  def createPackage(
    dataset: Dataset,
    state: PackageState = PackageState.UNAVAILABLE,
    `type`: PackageType = PackageType.TimeSeries,
    importId: UUID = UUID.randomUUID
  ): Package = {

    val p = Package(
      nodeId = NodeCodes.generateId(NodeCodes.packageCode),
      name = "Test Package",
      `type` = `type`,
      datasetId = dataset.id,
      ownerId = None,
      state = state,
      importId = Some(importId)
    )

    val id: Int = dataCLIContainer.db
      .run((packages returning packages.map(_.id)) += p)
      .await

    p.copy(id = id)
  }

  def getPackage(id: Int): Package = {
    dataCLIContainer.db.run(packages.get(id).result.head).await
  }

  def getFiles(p: Package): Seq[File] = {
    dataCLIContainer.db.run(files.getByPackage(p).result).await
  }

}
