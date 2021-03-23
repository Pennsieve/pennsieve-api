// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.etl.`data-cli`

import com.pennsieve.models.PackageType
import com.pennsieve.models.{ Dataset, Package }
import com.pennsieve.test.helpers.AwaitableImplicits._
import org.scalatest.{ FlatSpec, Matchers }

class UpdatePackageTypeSpec extends FlatSpec with Matchers {

  "decodePackageType" should "parse a string into a PackageType" in {
    val result = UpdatePackageType.decodePackageType("Slide").await
    result should equal(PackageType.Slide)
  }

  "decodePackageType" should "should fail with missing PackageType" in {
    assertThrows[Exception] {
      UpdatePackageType.decodePackageType("").await
    }
  }

  "decodePackageType" should "should fail with invalid PackageType" in {
    assertThrows[Exception] {
      UpdatePackageType.decodePackageType("ThisIsNotAValidPkgType").await
    }
  }

}

class UpdatePackageTypeDatabaseSpec extends DataCLIDatabaseSpecHarness {

  var dataset: Dataset = _
  var `package`: Package = _

  override def beforeEach(): Unit = {
    super.beforeEach()

    dataset = createDataset
    `package` = createPackage(dataset, `type` = PackageType.Image)
  }

  "update" should "should update package type" in {

    getPackage(`package` id).`type` should equal(PackageType.Image)

    UpdatePackageType
      .update(`package`.id, organization.id, PackageType.Slide)(
        dataCLIContainer
      )
      .await

    getPackage(`package` id).`type` should equal(PackageType.Slide)

  }
}
