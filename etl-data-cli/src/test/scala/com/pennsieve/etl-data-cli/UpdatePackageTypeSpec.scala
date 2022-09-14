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

import com.pennsieve.models.PackageType
import com.pennsieve.models.{ Dataset, Package }
import com.pennsieve.test.helpers.AwaitableImplicits._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class UpdatePackageTypeSpec extends AnyFlatSpec with Matchers {

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

    getPackage(`package`.id).`type` should equal(PackageType.Image)

    UpdatePackageType
      .update(`package`.id, organization.id, PackageType.Slide)(
        dataCLIContainer
      )
      .await

    getPackage(`package`.id).`type` should equal(PackageType.Slide)

  }
}
