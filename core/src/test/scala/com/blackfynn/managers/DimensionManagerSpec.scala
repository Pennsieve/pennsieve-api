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

package com.pennsieve.managers

import com.pennsieve.domain.UnsupportedPackageType
import com.pennsieve.models.PackageType
import com.pennsieve.models.{
  Dataset,
  Dimension,
  DimensionAssignment,
  DimensionProperties,
  Package
}
import com.pennsieve.test.helpers.EitherValue._
import org.scalatest.Matchers._
import org.scalatest.EitherValues._

import scala.concurrent.ExecutionContext.Implicits.global

class DimensionManagerSpec extends BaseManagerSpec {

  "DimensionManager" should "be able to perform all single dimension operations" in {
    val user = createUser()
    val manager = dimensionManager()

    val dataset: Dataset = createDataset(user = user)
    val `package`: Package =
      createPackage(user = user, dataset = dataset, `type` = PackageType.Image)

    // create
    val properties: DimensionProperties = DimensionProperties(
      "dimension-1",
      10L,
      None,
      Some("um"),
      DimensionAssignment.SpatialX
    )
    val dimension: Dimension = manager.create(properties, `package`).await.value

    // get and getAll
    manager.get(dimension.id, `package`).await.value should be(dimension)
    manager.getAll(`package`).await.value should be(List(dimension))

    val updated: Dimension = dimension.copy(name = "dimension-updated")

    // update
    manager.update(updated, `package`).await.value should be(updated)

    // delete
    manager.delete(dimension, `package`).await.value
    manager.get(dimension.id, `package`).await should be('left)
  }

  "DimensionManager" should "be able to perform all batch dimension operations" in {
    val user = createUser()
    val manager = dimensionManager()

    val dataset: Dataset = createDataset(user = user)
    val `package`: Package =
      createPackage(user = user, dataset = dataset, `type` = PackageType.Image)

    val batch: List[DimensionProperties] = List(
      DimensionProperties(
        "dimension-1",
        10L,
        None,
        Some("um"),
        DimensionAssignment.SpatialX
      ),
      DimensionProperties(
        "dimension-2",
        1000L,
        Some(0.999),
        Some("seconds"),
        DimensionAssignment.Time
      )
    )

    // create
    val dimensions: List[Dimension] =
      manager.create(batch, `package`).await.value

    // get and getAll
    manager
      .get(dimensions.map(_.id).toSet, `package`)
      .await
      .value should contain theSameElementsAs (dimensions)
    manager
      .getAll(`package`)
      .await
      .value should contain theSameElementsAs (dimensions)

    val updated: List[Dimension] =
      dimensions.map(_.copy(name = "dimension-updated"))

    // update
    manager
      .update(updated, `package`)
      .await
      .value should contain theSameElementsAs (updated)

    // delete
    manager.delete(dimensions, `package`).await.value
    manager.getAll(`package`).await.value should be(Nil)
  }

  "DimensionManager" should "fail for a non-Imaging package" in {
    val user = createUser()
    val manager = dimensionManager()

    val dataset: Dataset = createDataset(user = user)
    val `package`: Package = createPackage(
      user = user,
      dataset = dataset,
      `type` = PackageType.TimeSeries
    )
    val imaging: Package =
      createPackage(user = user, dataset = dataset, `type` = PackageType.Image)

    val properties: DimensionProperties = DimensionProperties(
      "dimension-1",
      10L,
      None,
      Some("um"),
      DimensionAssignment.SpatialX
    )

    val batch: List[DimensionProperties] = List(
      DimensionProperties(
        "dimension-1",
        10L,
        None,
        Some("um"),
        DimensionAssignment.SpatialX
      ),
      DimensionProperties(
        "dimension-2",
        1000L,
        Some(0.999),
        Some("seconds"),
        DimensionAssignment.Time
      )
    )

    val dimension: Dimension = manager.create(properties, imaging).await.value
    val dimensions: List[Dimension] = manager.create(batch, imaging).await.value

    // create
    assert(
      manager
        .create(properties, `package`)
        .await
        .left
        .value
        .isInstanceOf[UnsupportedPackageType]
    )
    assert(
      manager
        .create(batch, `package`)
        .await
        .left
        .value
        .isInstanceOf[UnsupportedPackageType]
    )

    // get and getAll
    assert(
      manager
        .get(dimension.id, `package`)
        .await
        .left
        .value
        .isInstanceOf[UnsupportedPackageType]
    )
    assert(
      manager
        .get(dimensions.map(_.id).toSet, `package`)
        .await
        .left
        .value
        .isInstanceOf[UnsupportedPackageType]
    )
    assert(
      manager
        .getAll(`package`)
        .await
        .left
        .value
        .isInstanceOf[UnsupportedPackageType]
    )

    // update
    assert(
      manager
        .update(dimension, `package`)
        .await
        .left
        .value
        .isInstanceOf[UnsupportedPackageType]
    )
    assert(
      manager
        .update(dimensions, `package`)
        .await
        .left
        .value
        .isInstanceOf[UnsupportedPackageType]
    )

    // delete
    assert(
      manager
        .delete(dimension, `package`)
        .await
        .left
        .value
        .isInstanceOf[UnsupportedPackageType]
    )
    assert(
      manager
        .delete(dimensions, `package`)
        .await
        .left
        .value
        .isInstanceOf[UnsupportedPackageType]
    )
  }

  "DimensionManager" should "be able to get the count of a package's dimensions" in {
    val user = createUser()
    val manager = dimensionManager()

    val dataset: Dataset = createDataset(user = user)
    val `package`: Package =
      createPackage(user = user, dataset = dataset, `type` = PackageType.Image)

    val batch: List[DimensionProperties] = List(
      DimensionProperties(
        "dimension-1",
        10L,
        None,
        Some("um"),
        DimensionAssignment.SpatialX
      ),
      DimensionProperties(
        "dimension-2",
        1000L,
        Some(0.999),
        Some("seconds"),
        DimensionAssignment.Time
      ),
      DimensionProperties(
        "dimension-3",
        1000L,
        Some(0.999),
        Some("seconds"),
        DimensionAssignment.Time
      ),
      DimensionProperties(
        "dimension-4",
        1000L,
        Some(0.999),
        Some("seconds"),
        DimensionAssignment.Time
      )
    )

    val dimensions: List[Dimension] =
      manager.create(batch, `package`).await.value

    manager.count(`package`).await.value should equal(batch.length)
  }

}
