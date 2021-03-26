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

package com.pennsieve.api

import com.pennsieve.messages._
import com.pennsieve.dtos.ModelPropertyRO
import com.pennsieve.helpers.{
  DataSetTestMixin,
  MockAuditLogger,
  MockSQSClient
}
import com.pennsieve.messages.DeletePackageJob
import com.pennsieve.models.PackageState.{ DELETING, READY }
import com.pennsieve.models.PackageType.{ Collection, PDF }
import com.pennsieve.domain.StorageAggregation.{ sdatasets, spackages }
import org.apache.http.impl.client.HttpClients
import org.json4s.jackson.Serialization.write
import org.scalatest.EitherValues._

class TestDataController extends BaseApiTest with DataSetTestMixin {

  val mockSqsClient = MockSQSClient

  val mockAuditLogger = new MockAuditLogger()

  override def afterStart(): Unit = {
    super.afterStart()

    val httpClient = HttpClients.createMinimal()
    addServlet(
      new DataController(
        insecureContainer,
        secureContainerBuilder,
        materializer,
        system.dispatcher,
        mockAuditLogger,
        mockSqsClient
      ),
      "/*"
    )
  }

  test("swagger") {
    import com.pennsieve.web.ResourcesApp
    addServlet(new ResourcesApp, "/api-docs/*")

    get("/api-docs/swagger.json") {
      status should equal(200)
      println(body)
    }
  }

  test("moves a package") {
    val dataset = createDataSet("My DataSet")
    val collection = packageManager
      .create("Foo", Collection, READY, dataset, Some(loggedInUser.id), None)
      .await
      .right
      .value
    val collection2 = packageManager
      .create("Bar", Collection, READY, dataset, Some(loggedInUser.id), None)
      .await
      .right
      .value
    val pkg = packageManager
      .create("Baz", PDF, READY, dataset, Some(loggedInUser.id), None)
      .await
      .right
      .value

    val moveReq = write(MoveRequest(List(pkg.nodeId), Some(collection2.nodeId)))

    postJson(
      s"/move",
      moveReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      compactRender(parsedBody \ "failure") should not include (pkg.nodeId)
      compactRender(parsedBody \ "success") should include(pkg.nodeId)

    }
  }

  test("fails to move a package that would violate unique naming constraints") {
    val dataset = createDataSet("My DataSet")
    val collection = packageManager
      .create("Foo", Collection, READY, dataset, Some(loggedInUser.id), None)
      .await
      .right
      .value
    val collection2 = packageManager
      .create("Bar", Collection, READY, dataset, Some(loggedInUser.id), None)
      .await
      .right
      .value
    val pkg = packageManager
      .create(
        "Baz",
        PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        Some(collection)
      )
      .await
      .right
      .value
    val pkg2 = packageManager
      .create(
        "Baz",
        PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        Some(collection2)
      )
      .await
      .right
      .value

    val moveReq = write(MoveRequest(List(pkg.nodeId), Some(collection2.nodeId)))

    postJson(
      s"/move",
      moveReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      compactRender(parsedBody \ "failures") should include(pkg.nodeId)
      compactRender(parsedBody \ "failures") should include(pkg.name)
      compactRender(parsedBody \ "failures") should include("Baz (1)")
      compactRender(parsedBody \ "failures") should include(
        "unique naming constraint or naming convention violation"
      )
    }
  }

  test("unique naming constraints with bulk move") {

    val dataset = createDataSet("My DataSet")
    val collection = packageManager
      .create("Foo", Collection, READY, dataset, Some(loggedInUser.id), None)
      .await
      .right
      .value
    val collection2 = packageManager
      .create("Bar", Collection, READY, dataset, Some(loggedInUser.id), None)
      .await
      .right
      .value
    val pkg = packageManager
      .create(
        "Baz",
        PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        Some(collection)
      )
      .await
      .right
      .value
    val pkg2 = packageManager
      .create(
        "Baz",
        PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        Some(collection2)
      )
      .await
      .right
      .value

    val pkg3 = packageManager
      .create(
        "UniqueName",
        PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        Some(collection2)
      )
      .await
      .right
      .value

    val moveReq = write(
      MoveRequest(List(pkg2.nodeId, pkg3.nodeId), Some(collection.nodeId))
    )

    postJson(
      s"/move",
      moveReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      compactRender(parsedBody \ "success") should include(pkg3.nodeId)
      compactRender(parsedBody \ "failures") should include(pkg2.nodeId)
      compactRender(parsedBody \ "failures") should include(pkg2.name)
      compactRender(parsedBody \ "failures") should include("Baz (1)")
      compactRender(parsedBody \ "failures") should include(
        "unique naming constraint or naming convention violation"
      )
    }
  }

  test("fails to move a packge into itself") {
    val dataset = createDataSet("My DataSet")
    val collection = packageManager
      .create("Foo", Collection, READY, dataset, Some(loggedInUser.id), None)
      .await
      .right
      .value

    val moveReq =
      write(MoveRequest(List(collection.nodeId), Some(collection.nodeId)))

    postJson(
      s"/move",
      moveReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      compactRender(parsedBody \ "failures") should include(collection.nodeId)
      compactRender(parsedBody \ "failures") should include(
        "cannot move object into itself"
      )
    }
  }

  test("fails to moves a collection into its child") {

    val dataset = createDataSet("My DataSet")
    val collection = packageManager
      .create("Foo", Collection, READY, dataset, Some(loggedInUser.id), None)
      .await
      .right
      .value
    val collection2 = packageManager
      .create(
        "Bar",
        Collection,
        READY,
        dataset,
        Some(loggedInUser.id),
        Some(collection)
      )
      .await
      .right
      .value
    val moveReq =
      write(MoveRequest(List(collection.nodeId), Some(collection2.nodeId)))

    postJson(
      s"/move",
      moveReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      compactRender(parsedBody \ "failures") should include(collection.nodeId)
      compactRender(parsedBody \ "failures") should include(
        "cannot move object into one of its descendants"
      )
    }
  }

  test("fails to moves a collection right back where it is") {

    val dataset = createDataSet("My DataSet")
    val collection = packageManager
      .create("Foo", Collection, READY, dataset, Some(loggedInUser.id), None)
      .await
      .right
      .value
    val collection2 = packageManager
      .create(
        "Bar",
        Collection,
        READY,
        dataset,
        Some(loggedInUser.id),
        Some(collection)
      )
      .await
      .right
      .value
    val moveReq =
      write(MoveRequest(List(collection2.nodeId), Some(collection.nodeId)))

    postJson(
      s"/move",
      moveReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      compactRender(parsedBody \ "failures") should include(collection2.nodeId)
      compactRender(parsedBody \ "failures") should include(
        "cannot move object where it already is"
      )
    }
  }

  test("can move a package directly into a dataset") {

    val dataset = createDataSet("My DataSet")
    val collection = packageManager
      .create("Foo", Collection, READY, dataset, Some(loggedInUser.id), None)
      .await
      .right
      .value
    val collection2 = packageManager
      .create(
        "Bar",
        Collection,
        READY,
        dataset,
        Some(loggedInUser.id),
        Some(collection)
      )
      .await
      .right
      .value
    val moveReq = write(MoveRequest(List(collection2.nodeId), None))

    postJson(
      s"/move",
      moveReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      compactRender(parsedBody \ "failures") should not include (collection2.nodeId)

      val moved =
        packageManager.getByNodeId(collection2.nodeId).await.right.value

      assert(moved.parentId.isEmpty)
    }
  }

  test("fails to moves a dataset") {
    val datasetOne = createDataSet("My DataSet")
    val datasetTwo = createDataSet("Another DataSet")
    val moveReq =
      write(MoveRequest(List(datasetOne.nodeId), Some(datasetTwo.nodeId)))

    postJson(
      s"/move",
      moveReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  test("bulk move works") {

    val dataset = createDataSet("My DataSet")
    val collection = packageManager
      .create("Foo", Collection, READY, dataset, Some(loggedInUser.id), None)
      .await
      .right
      .value
    val collection2 = packageManager
      .create("Bar", Collection, READY, dataset, Some(loggedInUser.id), None)
      .await
      .right
      .value
    val pkg = packageManager
      .create(
        "Baz",
        PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        Some(collection)
      )
      .await
      .right
      .value

    val moveReq = write(
      MoveRequest(List(pkg.nodeId, dataset.nodeId), Some(collection2.nodeId))
    )

    postJson(
      s"/move",
      moveReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      compactRender(parsedBody \ "success") should include(pkg.nodeId)

      compactRender(parsedBody \ "failures") should include(dataset.nodeId)
      compactRender(parsedBody \ "failures") should include(
        "cannot retrieve package"
      )
    }
  }

  test("moving a package from collection to collection should update storage") {
    val dataset1 = createDataSet("My DataSet")
    val collection1 = packageManager
      .create(
        "Collection 1",
        Collection,
        READY,
        dataset1,
        Some(loggedInUser.id),
        None
      )
      .await
      .right
      .value
    val collection2 = packageManager
      .create(
        "Collection 2",
        Collection,
        READY,
        dataset1,
        Some(loggedInUser.id),
        None
      )
      .await
      .right
      .value
    val pkg1 = packageManager
      .create(
        "Some Package",
        PDF,
        READY,
        dataset1,
        Some(loggedInUser.id),
        Some(collection1)
      )
      .await
      .right
      .value
    secureContainer.storageManager
      .incrementStorage(spackages, 1000, pkg1.id)
      .await

    val datasetStorageBefore = secureContainer.storageManager
      .getStorage(sdatasets, List(dataset1.id))
      .await
      .right
      .value
      .get(dataset1.id)
      .flatten
    val storageMap1 = secureContainer.storageManager
      .getStorage(spackages, List(collection1.id, collection2.id, pkg1.id))
      .await
      .right
      .value
    val coll1StorageBefore = storageMap1.get(collection1.id).flatten
    val coll2StorageBefore = storageMap1.get(collection2.id).flatten
    val pkgStorageBefore = storageMap1.get(pkg1.id).flatten
    assert(
      datasetStorageBefore == Some(1000) && coll1StorageBefore == Some(1000) && coll2StorageBefore == None && pkgStorageBefore == Some(
        1000
      )
    )

    val moveReq =
      write(MoveRequest(List(pkg1.nodeId), Some(collection2.nodeId)))

    postJson(
      s"/move",
      moveReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      compactRender(parsedBody \ "failure") should not include (pkg1.nodeId)
      compactRender(parsedBody \ "success") should include(pkg1.nodeId)
    }

    val datasetStorageAfter = secureContainer.storageManager
      .getStorage(sdatasets, List(dataset1.id))
      .await
      .right
      .value
      .get(dataset1.id)
      .flatten
    val storageMap2 = secureContainer.storageManager
      .getStorage(spackages, List(collection1.id, collection2.id, pkg1.id))
      .await
      .right
      .value
    val coll1StorageAfter = storageMap2.get(collection1.id).flatten
    val coll2StorageAfter = storageMap2.get(collection2.id).flatten
    val pkg1StorageAfter = storageMap2.get(pkg1.id).flatten
    assert(
      datasetStorageAfter == Some(1000) && coll1StorageAfter == Some(0) && coll2StorageAfter == Some(
        1000
      ) && pkg1StorageAfter == Some(1000)
    )
  }

  test("moving a package into a collection should update storage") {
    val myDataset = createDataSet("My New DataSet")
    val myCollection = packageManager
      .create(
        "Test Collection",
        Collection,
        READY,
        myDataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .right
      .value
    val myPkg = packageManager
      .create(
        "Test Package",
        PDF,
        READY,
        myDataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .right
      .value
    secureContainer.storageManager
      .incrementStorage(spackages, 1000, myPkg.id)
      .await

    val datasetStorageBefore1 = secureContainer.storageManager
      .getStorage(sdatasets, List(myDataset.id))
      .await
      .right
      .value
      .get(myDataset.id)
      .flatten
    val storageMapBefore = secureContainer.storageManager
      .getStorage(spackages, List(myCollection.id, myPkg.id))
      .await
      .right
      .value
    val collStorageBefore1 = storageMapBefore.get(myCollection.id).flatten
    val pkgStorageBefore1 = storageMapBefore.get(myPkg.id).flatten
    assert(
      datasetStorageBefore1 == Some(1000) && collStorageBefore1 == None && pkgStorageBefore1 == Some(
        1000
      )
    )

    val moveReq =
      write(MoveRequest(List(myPkg.nodeId), Some(myCollection.nodeId)))

    postJson(
      s"/move",
      moveReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      compactRender(parsedBody \ "failure") should not include (myPkg.nodeId)
      compactRender(parsedBody \ "success") should include(myPkg.nodeId)
    }

    val datasetStorageAfter1 = secureContainer.storageManager
      .getStorage(sdatasets, List(myDataset.id))
      .await
      .right
      .value
      .get(myDataset.id)
      .flatten
    val storageMapAfter = secureContainer.storageManager
      .getStorage(spackages, List(myCollection.id, myPkg.id))
      .await
      .right
      .value
    val collStorageAfter1 = storageMapAfter.get(myCollection.id).flatten
    val pkgStorageAfter1 = storageMapAfter.get(myPkg.id).flatten
    assert(
      datasetStorageAfter1 == Some(1000) && collStorageAfter1 == Some(1000) && pkgStorageAfter1 == Some(
        1000
      )
    )
  }

  test("successfully deletes a package") {
    val dataset = createDataSet("My DataSet")
    val pkg = packageManager
      .create("Foo", PDF, READY, dataset, Some(loggedInUser.id), None)
      .await
      .right
      .value
    val deleteReq = write(DeleteRequest(List(pkg.nodeId)))

    postJson(
      s"/delete",
      deleteReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      compactRender(parsedBody \ "success") should include(pkg.nodeId)
    }
  }

  test("successfully deletes a collection") {
    val dataset = createDataSet("My DataSet")
    val collection = packageManager
      .create("Foo", Collection, READY, dataset, Some(loggedInUser.id), None)
      .await
      .right
      .value

    val deleteReq = write(DeleteRequest(List(collection.nodeId)))

    postJson(
      s"/delete",
      deleteReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      compactRender(parsedBody \ "success") should include(collection.nodeId)
    }
  }

  test("fails to delete a dataset") {
    val dataset = createDataSet("My DataSet")
    val deleteReq = write(DeleteRequest(List(dataset.nodeId)))

    postJson(
      s"/delete",
      deleteReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      compactRender(parsedBody \ "failures") should include(dataset.nodeId)
      compactRender(parsedBody \ "failures") should include("not found")
    }
  }

  test("fails to delete an organization") {
    val deleteReq = write(DeleteRequest(List(loggedInOrganization.nodeId)))

    postJson(
      s"/delete",
      deleteReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      compactRender(parsedBody \ "failures") should include(
        loggedInOrganization.nodeId
      )
      compactRender(parsedBody \ "failures") should include("not found")
    }
  }

  test("bulk delete works") {

    val datasetOne = createDataSet("My DataSet")
    val pkg = packageManager
      .create("Baz", PDF, READY, dataset, Some(loggedInUser.id), None)
      .await
      .right
      .value
    val collection = packageManager
      .create("Foo", Collection, READY, dataset, Some(loggedInUser.id), None)
      .await
      .right
      .value

    val datasetTwo = createDataSet("My DataSet2")

    val collection2 = packageManager
      .create("Bar", Collection, READY, dataset, Some(loggedInUser.id), None)
      .await
      .right
      .value
    val pkg2 = packageManager
      .create(
        "Baz",
        PDF,
        READY,
        dataset,
        Some(loggedInUser.id),
        Some(collection2)
      )
      .await
      .right
      .value

    val validItemIds = List(pkg.nodeId, collection.nodeId)

    val validDeletedIds = List(pkg.id, collection.id)

    val deleteReq = write(
      DeleteRequest(
        validItemIds ::: List(datasetTwo.nodeId, loggedInOrganization.nodeId)
      )
    )

    postJson(
      s"/delete",
      deleteReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      compactRender(parsedBody \ "success") should include(pkg.nodeId)
      compactRender(parsedBody \ "success") should include(collection.nodeId)

      compactRender(parsedBody \ "failures") should include(datasetTwo.nodeId)
      compactRender(parsedBody \ "failures") should include("not found")

      compactRender(parsedBody \ "failures") should include(
        loggedInOrganization.nodeId
      )
      compactRender(parsedBody \ "failures") should include("not found")
    }
  }

  test("updating data's properties works") {

    val dataset = createDataSet("My DataSet")
    val pkg = packageManager
      .create("Foo", PDF, READY, dataset, Some(loggedInUser.id), None)
      .await
      .right
      .value

    val property = ModelPropertyRO(
      key = "test",
      value = "test",
      dataType = Some("string"),
      category = Some("test"),
      fixed = Some(false),
      hidden = Some(false)
    )
    val propertiesJson = write(UpdatePropertiesRequest(List(property)))

    putJson(
      s"/${pkg.nodeId}/properties",
      propertiesJson,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val updatedPkg = packageManager.get(pkg.id).await.right.value
      updatedPkg.attributes.find(_.key == "test") shouldBe defined
    }
  }

  test("trying to update a deleted package node's properties 404s") {

    val dataset = createDataSet("My DataSet")
    val pkg = packageManager
      .create("Foo", PDF, DELETING, dataset, Some(loggedInUser.id), None)
      .await
      .right
      .value

    putJson(
      s"/${pkg.nodeId}/properties",
      "{}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }
  }
}
