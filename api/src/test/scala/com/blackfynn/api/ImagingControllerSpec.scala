// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.api

import com.pennsieve.dtos.DimensionDTO
import com.pennsieve.helpers.DataSetTestMixin
import com.pennsieve.models.{
  Dataset,
  Dimension,
  DimensionAssignment,
  DimensionProperties,
  Package
}
import com.pennsieve.models.PackageState.READY
import com.pennsieve.test.helpers.EitherValue._
import com.pennsieve.models.PackageType
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.write
import org.scalatest.EitherValues._

class ImagingControllerSpec extends BaseApiTest with DataSetTestMixin {

  override def afterStart(): Unit = {
    super.afterStart()

    addServlet(
      new ImagingController(
        insecureContainer,
        secureContainerBuilder,
        system.dispatcher
      ),
      "/*"
    )
  }

  test("get a dimension") {
    val dataset: Dataset = createDataSet("test dataset")
    val `package`: Package = packageManager
      .create(
        "imaging",
        PackageType.Image,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    val properties: DimensionProperties = DimensionProperties(
      "dimension-1",
      10L,
      None,
      Some("um"),
      DimensionAssignment.SpatialX
    )
    val dimension: Dimension =
      dimensionManager.create(properties, `package`).await.value

    get(
      s"/${`package`.nodeId}/dimensions/${dimension.id}",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)

      val response = parsedBody.extract[DimensionDTO]

      response should equal(DimensionDTO(dimension, `package`))
    }
  }

  test("get all of a package's dimensions") {
    val dataset: Dataset = createDataSet("test dataset")
    val `package`: Package = packageManager
      .create(
        "imaging",
        PackageType.Image,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    val properties: List[DimensionProperties] = List(
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

    val dimensions: List[Dimension] =
      dimensionManager.create(properties, `package`).await.value

    get(
      s"/${`package`.nodeId}/dimensions",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)

      val response = parsedBody.extract[List[DimensionDTO]]

      response should contain theSameElementsAs (DimensionDTO(
        dimensions,
        `package`
      ))
    }
  }

  test("create a dimension") {
    val dataset: Dataset = createDataSet("test dataset")
    val `package`: Package = packageManager
      .create(
        "imaging",
        PackageType.Image,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    val properties: DimensionProperties = DimensionProperties(
      "dimension-1",
      10L,
      None,
      Some("um"),
      DimensionAssignment.SpatialX
    )

    postJson(
      s"/${`package`.nodeId}/dimensions",
      write(properties),
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(201)

      val response = parsedBody.extract[DimensionDTO]

      response.name should equal(properties.name)
      response.length should equal(properties.length)
      response.resolution should equal(properties.resolution)
      response.unit should equal(properties.unit)
      response.assignment should equal(properties.assignment)
    }

    dimensionManager.getAll(`package`).await.value.length should equal(1)
  }

  test("update a dimension") {
    val dataset: Dataset = createDataSet("test dataset")
    val `package`: Package = packageManager
      .create(
        "imaging",
        PackageType.Image,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    val properties: DimensionProperties = DimensionProperties(
      "dimension-1",
      10L,
      None,
      Some("um"),
      DimensionAssignment.SpatialX
    )
    val dimension: Dimension =
      dimensionManager.create(properties, `package`).await.value
    val updated: Dimension = dimension.copy(name = "dimension-updated")

    putJson(
      s"/${`package`.nodeId}/dimensions/${dimension.id}",
      write(updated),
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)

      val response = parsedBody.extract[DimensionDTO]

      response.name should equal("dimension-updated")
      response should equal(DimensionDTO(updated, `package`))
    }
  }

  test("delete a dimension") {
    val dataset: Dataset = createDataSet("test dataset")
    val `package`: Package = packageManager
      .create(
        "imaging",
        PackageType.Image,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    val properties: DimensionProperties = DimensionProperties(
      "dimension-1",
      10L,
      None,
      Some("um"),
      DimensionAssignment.SpatialX
    )
    val dimension: Dimension =
      dimensionManager.create(properties, `package`).await.value

    dimensionManager.getAll(`package`).await.value.length should equal(1)

    delete(
      s"/${`package`.nodeId}/dimensions/${dimension.id}",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
    }

    dimensionManager.getAll(`package`).await.value.length should equal(0)
  }

  test("create a batch dimensions") {
    val dataset: Dataset = createDataSet("test dataset")
    val `package`: Package = packageManager
      .create(
        "imaging",
        PackageType.Image,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    val properties: List[DimensionProperties] = List(
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

    postJson(
      s"/${`package`.nodeId}/dimensions/batch",
      write(properties),
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(201)

      parsedBody.extract[List[DimensionDTO]]
    }

    dimensionManager.getAll(`package`).await.value.length should equal(2)
  }

  test("update a batch of dimensions") {
    val dataset: Dataset = createDataSet("test dataset")
    val `package`: Package = packageManager
      .create(
        "imaging",
        PackageType.Image,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    val properties: List[DimensionProperties] = List(
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

    val dimensions: List[Dimension] =
      dimensionManager.create(properties, `package`).await.value
    val updated: List[Dimension] =
      dimensions.map(_.copy(name = "dimension-updated"))

    putJson(
      s"/${`package`.nodeId}/dimensions/batch",
      write(updated),
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)

      val response = parsedBody.extract[List[DimensionDTO]]

      response should contain theSameElementsAs (DimensionDTO(
        updated,
        `package`
      ))
    }
  }

  test("delete a batch of dimensions") {
    val dataset: Dataset = createDataSet("test dataset")
    val `package`: Package = packageManager
      .create(
        "imaging",
        PackageType.Image,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    val properties: List[DimensionProperties] = List(
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

    val dimensions: List[Dimension] =
      dimensionManager.create(properties, `package`).await.value

    dimensionManager.getAll(`package`).await.value.length should equal(2)

    deleteJson(
      s"/${`package`.nodeId}/dimensions/batch",
      write(dimensions.map(_.id)),
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
    }

    dimensionManager.getAll(`package`).await.value.length should equal(0)
  }

  test("get a package's dimension count") {
    val dataset: Dataset = createDataSet("test dataset")
    val `package`: Package = packageManager
      .create(
        "imaging",
        PackageType.Image,
        READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    val properties: List[DimensionProperties] = List(
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
      dimensionManager.create(properties, `package`).await.value

    get(
      s"/${`package`.nodeId}/dimensions/count",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)

      val response = parsedBody.extract[Int]

      response should equal(properties.length)
    }

  }

}
