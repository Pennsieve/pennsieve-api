// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.etl.`data-cli`

import com.blackfynn.test.helpers.AwaitableImplicits._
import com.blackfynn.etl.`data-cli`.exceptions._
import com.blackfynn.models.{ Dataset, ModelProperty, Package, PackageState }
import com.blackfynn.traits.PostgresProfile.api._
import java.time.ZonedDateTime
import java.io.{ File => JavaFile }

import com.blackfynn.models.PackageType
import org.scalatest.{ FlatSpec, Matchers }

class SetPackagePropertiesSpec extends FlatSpec with Matchers {

  "decodeProperties" should "parse a JSON file into a List[ModelProperty]" in {
    val file: JavaFile =
      new JavaFile(getClass.getResource("/inputs/property-info.json").getPath)
    val result: List[ModelProperty] =
      SetPackageProperties.decodeProperties(file).await

    result should equal(
      List(
        ModelProperty(
          key = "key-1",
          value = "value-1",
          dataType = "String",
          category = "Pennsieve",
          fixed = false,
          hidden = false
        ),
        ModelProperty(
          key = "key-2",
          value = "value-2",
          dataType = "String",
          category = "Pennsieve",
          fixed = false,
          hidden = false
        )
      )
    )
  }

  "decodeProperties" should "should fail on a missing file" in {
    val file: JavaFile = new JavaFile("missing.json")

    assertThrows[Exception] {
      SetPackageProperties.decodeProperties(file).await
    }
  }

  "decodeProperties" should "should fail on a file with invalid JSON" in {
    val file: JavaFile =
      new JavaFile(getClass.getResource("/inputs/asset-info.json").getPath)

    assertThrows[Exception] {
      SetPackageProperties.decodeProperties(file).await
    }
  }

  "parse" should "should create a config object" in {
    val args = Array(
      "--property-info",
      "property-info.json",
      "--package-id",
      "1",
      "--organization-id",
      "2"
    )
    val config =
      SetPackageProperties.CLIConfig(new JavaFile("property-info.json"), 1, 2)

    SetPackageProperties.parse(args).await should equal(config)
  }

  "parse" should "should fail with missing arguments" in {
    val args = Array("--package-id", "6", "--organization-id", "10")

    assertThrows[ScoptParsingFailure] {
      SetPackageProperties.parse(args).await
    }
  }
}

class SetPackagePropertiesDatabaseSpec extends DataCLIDatabaseSpecHarness {
  var dataset: Dataset = _

  override def beforeEach(): Unit = {
    super.beforeEach()

    dataset = createDataset
  }

  "update" should "update a Package in the database and return the updated Package with empty attributes" in {
    val row = Package(
      nodeId = "N:channel:d4a6a17a-3cf1-11e8-b467-0ed5f89f718b",
      name = "package-1",
      `type` = PackageType.TimeSeries,
      datasetId = dataset.id,
      ownerId = None,
      state = PackageState.READY,
      parentId = None,
      importId = None,
      createdAt = ZonedDateTime.now(),
      updatedAt = ZonedDateTime.now(),
      attributes = Nil
    )

    val `package`: Package =
      dataCLIContainer.db.run((packages returning packages) += row).await

    val data = List(
      ModelProperty(
        key = "key-1",
        value = "value-1",
        dataType = "String",
        category = "Pennsieve",
        fixed = false,
        hidden = false
      ),
      ModelProperty(
        key = "key-2",
        value = "value-2",
        dataType = "String",
        category = "Pennsieve",
        fixed = false,
        hidden = false
      )
    )

    val result = dataCLIContainer.db
      .run(
        SetPackageProperties
          .update(`package`.id, organization.id, data)(dataCLIContainer)
      )
      .await

    result.attributes should equal(data)
  }

  "update" should "update a Package in the database and return the updated Package with populated attributes" in {
    val row = Package(
      nodeId = "N:channel:d4a6a17a-3cf1-11e8-b467-0ed5f89f718b",
      name = "package-1",
      `type` = PackageType.TimeSeries,
      datasetId = dataset.id,
      ownerId = None,
      state = PackageState.READY,
      parentId = None,
      importId = None,
      createdAt = ZonedDateTime.now(),
      updatedAt = ZonedDateTime.now(),
      attributes = List(
        ModelProperty(
          key = "key-1",
          value = "value-1",
          dataType = "String",
          category = "Pennsieve",
          fixed = false,
          hidden = false
        )
      )
    )

    val `package`: Package =
      dataCLIContainer.db.run((packages returning packages) += row).await

    val data = List(
      ModelProperty(
        key = "key-1",
        value = "value-99", // (different value)
        dataType = "String",
        category = "Pennsieve",
        fixed = false,
        hidden = false
      ),
      ModelProperty(
        key = "key-2",
        value = "value-2",
        dataType = "String",
        category = "Pennsieve",
        fixed = false,
        hidden = false
      )
    )

    val result = dataCLIContainer.db
      .run(
        SetPackageProperties
          .update(`package`.id, organization.id, data)(dataCLIContainer)
      )
      .await

    result.attributes should equal(ModelProperty.merge(row.attributes, data))
    result.attributes.head.value should equal("value-99")
  }
}
