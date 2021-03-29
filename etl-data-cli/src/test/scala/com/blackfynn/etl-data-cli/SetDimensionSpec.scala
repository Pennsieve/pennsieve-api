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

import com.pennsieve.test.helpers.AwaitableImplicits._
import com.pennsieve.etl.`data-cli`.SetDimension.DimensionInfo
import com.pennsieve.etl.`data-cli`.exceptions._
import com.pennsieve.models.{ Dataset, Dimension, DimensionAssignment, Package }
import com.pennsieve.traits.PostgresProfile.api._

import java.io.{ File => JavaFile }
import org.scalatest.{ FlatSpec, Matchers }

class SetDimensionSpec extends FlatSpec with Matchers {

  "decodeDimensionInfo" should "parse a JSON file into a DimensionInfo without an id" in {
    val file: JavaFile =
      new JavaFile(getClass.getResource("/inputs/dimension-info.json").getPath)
    val result: DimensionInfo = SetDimension.decodeDimensionInfo(file).await

    result.id should equal(None)
    result.name should equal("dimension-1")
    result.length should equal(167L)
    result.resolution should equal(Some(0.996))
    result.unit should equal(Some("um"))
    result.assignment should equal(DimensionAssignment.SpatialX)
  }

  "decodeDimensionInfo" should "parse a JSON file into a DimensionInfo with an id" in {
    val file: JavaFile = new JavaFile(
      getClass.getResource("/inputs/update-dimension-info.json").getPath
    )
    val result: DimensionInfo = SetDimension.decodeDimensionInfo(file).await

    result.id should equal(Some(1))
    result.name should equal("dimension-1")
    result.length should equal(3L)
    result.resolution should equal(None)
    result.unit should equal(None)
    result.assignment should equal(DimensionAssignment.Color)
  }

  "decodeDimensionInfo" should "should fail on a missing file" in {
    val file: JavaFile = new JavaFile("missing.json")

    assertThrows[Exception] {
      SetDimension.decodeDimensionInfo(file).await
    }
  }

  "decodeDimensionInfo" should "should fail on a file with invalid JSON" in {
    val file: JavaFile =
      new JavaFile(getClass.getResource("/inputs/asset-info.json").getPath)

    assertThrows[Exception] {
      SetDimension.decodeDimensionInfo(file).await
    }
  }

  "parse" should "should create a config object" in {
    val args = Array(
      "--package-id",
      "6",
      "--organization-id",
      "10",
      "--dimension-info",
      "dimension-info.json",
      "--output-file",
      "dimension.json"
    )
    val config = SetDimension.CLIConfig(
      new JavaFile("dimension-info.json"),
      new JavaFile("dimension.json"),
      6,
      10
    )

    SetDimension.parse(args).await should equal(config)
  }

  "parse" should "should fail with missing arguments" in {
    val args = Array("--package-id", "6", "--organization-id", "10")

    assertThrows[ScoptParsingFailure] {
      SetDimension.parse(args).await
    }
  }

}

class SetDimensionDatabaseSpec extends DataCLIDatabaseSpecHarness {

  var dataset: Dataset = _
  var `package`: Package = _

  override def beforeEach(): Unit = {
    super.beforeEach()

    dataset = createDataset
    `package` = createPackage(dataset)
  }

  "create" should "should create a Dimension to the database and return a Dimension" in {
    val data: DimensionInfo = DimensionInfo(
      name = "dimension-1",
      length = 167L,
      resolution = Some(0.996),
      unit = Some("um"),
      assignment = DimensionAssignment.SpatialX
    )

    val result = dataCLIContainer.db
      .run(
        SetDimension
          .create(`package`.id, organization.id, data)(dataCLIContainer)
      )
      .await

    result.id should not equal (0)
    result.name should equal(data.name)
  }

  "update" should "update a Dimension in the database and return the updated Dimension" in {
    val dimensionId: Int = 1

    val row = Dimension(
      packageId = `package`.id,
      name = "dimension-1",
      length = 3L,
      resolution = None,
      unit = None,
      assignment = DimensionAssignment.Color,
      id = dimensionId
    )

    val dimension: Dimension =
      dataCLIContainer.db.run((dimensions returning dimensions) += row).await

    val data: DimensionInfo = DimensionInfo(
      id = Some(dimensionId),
      name = dimension.name,
      length = 4L,
      resolution = Some(0.886),
      unit = Some("cm"),
      assignment = DimensionAssignment.SpatialY
    )

    val result = dataCLIContainer.db
      .run(
        SetDimension.update(`package`.id, organization.id, dimensionId, data)(
          dataCLIContainer
        )
      )
      .await

    result.id should equal(dimensionId)
    result.name should equal(dimension.name)
    result.length should equal(data.length)
    result.resolution should equal(data.resolution)
    result.unit should equal(data.unit)
    result.assignment should equal(data.assignment)

  }
}
