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

import com.pennsieve.etl.`data-cli`.CreateAsset.AssetInfo
import com.pennsieve.etl.`data-cli`.exceptions._
import com.pennsieve.models.{
  Dataset,
  File,
  FileObjectType,
  FileProcessingState,
  Package
}
import com.pennsieve.test.helpers.AwaitableImplicits._

import java.io.{ File => JavaFile }
import org.scalatest.{ FlatSpec, Matchers }

class CreateAssetSpec extends FlatSpec with Matchers {

  "decodeAssetInfo" should "parse a JSON file into an AssetInfo" in {
    val file: JavaFile =
      new JavaFile(getClass.getResource("/inputs/asset-info.json").getPath)
    val result: AssetInfo = CreateAsset.decodeAssetInfo(file).await

    result.bucket should equal("test-storage-pennsieve")
    result.key should equal(
      "test@pennsieve.com/data/4e4c7b1a-36aa-11e8-b467-0ed5f89f718b/simple.csv"
    )
    result.size should equal(123456L)
    result.`type` should equal(FileObjectType.Source)
  }

  "decodeAssetInfo" should "should fail on a missing file" in {
    val file: JavaFile = new JavaFile("missing.json")

    assertThrows[Exception] {
      CreateAsset.decodeAssetInfo(file).await
    }
  }

  "decodeAssetInfo" should "should fail on a file with invalid JSON" in {
    val file: JavaFile =
      new JavaFile(getClass.getResource("/inputs/channel-info.json").getPath)

    assertThrows[Exception] {
      CreateAsset.decodeAssetInfo(file).await
    }
  }

  "parse" should "should create a config object" in {
    val args = Array(
      "--package-id",
      "6",
      "--organization-id",
      "10",
      "--asset-info",
      "asset-info.json"
    )
    val config = CreateAsset.CLIConfig(new JavaFile("asset-info.json"), 6, 10)

    CreateAsset.parse(args).await should equal(config)
  }

  "parse" should "should fail with missing arguments" in {
    val args = Array("--package-id", "6", "--organization-id", "10")

    assertThrows[ScoptParsingFailure] {
      CreateAsset.parse(args).await
    }
  }

}

class CreateAssetDatabaseSpec extends DataCLIDatabaseSpecHarness {

  var dataset: Dataset = _
  var `package`: Package = _

  override def beforeEach(): Unit = {
    super.beforeEach()

    dataset = createDataset
    `package` = createPackage(dataset)
  }

  "write" should "should write a File to the database and return an organization" in {
    val asset: AssetInfo = AssetInfo(
      bucket = "test-storage-pennsieve",
      key =
        "test@pennsieve.com/data/4e4c7b1a-36aa-11e8-b467-0ed5f89f718b/simple.csv",
      size = 123456L,
      `type` = FileObjectType.Source
    )

    val result = dataCLIContainer.db
      .run(
        CreateAsset
          .write(`package`.id, organization.id, asset)(dataCLIContainer)
      )
      .await

    result._1.id should equal(organization.id)
    result._2.isInstanceOf[File] should equal(true)
    result._2.processingState should equal(FileProcessingState.Processed)
  }
}
