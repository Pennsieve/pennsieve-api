// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

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
import org.scalatest.{ FlatSpec, Matchers }
import scala.concurrent.ExecutionContext.Implicits.global

trait DataCLIDatabaseSpecHarness
    extends FlatSpec
    with DataCLISpecHarness
    with Matchers {

  val files = new FilesMapper(organization)
  val packages = new PackagesMapper(organization)
  val datasets = new DatasetsMapper(organization)
  val channels = new ChannelsMapper(organization)
  val dimensions = new DimensionsMapper(organization)

  def createDataset: Dataset = {

    val status = new DatasetStatusManager(dataCLIContainer.db, organization)
      .create("Test")
      .await
      .right
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
