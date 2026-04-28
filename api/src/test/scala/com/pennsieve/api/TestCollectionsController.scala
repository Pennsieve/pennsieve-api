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

import com.pennsieve.dtos.CollectionDTO
import com.pennsieve.models.{ Collection, Organization, User }
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.write

class TestCollectionsController extends BaseApiUnitTest {

  private val loggedInUser: User = User(
    nodeId = "N:user:00000000-0000-0000-0000-000000000001",
    email = "user@test.com",
    firstName = "Test",
    middleInitial = None,
    lastName = "User",
    degree = None,
    credential = "",
    color = "",
    url = "",
    isSuperAdmin = false,
    id = 1
  )

  private val loggedInOrganization: Organization = Organization(
    nodeId = "N:organization:00000000-0000-0000-0000-000000000001",
    name = "Test Organization",
    slug = "test-organization",
    encryptionKeyId = Some("test-key"),
    id = 1
  )

  private var loggedInJwt: String = _

  private val myCollectionName = "My Collection"
  private val myOtherCollectionName = "My Other Collection"
  private val myNewOtherCollectionName = "My New Other Collection"

  override def beforeAll(): Unit = {
    super.beforeAll()
    addServlet(
      new CollectionsController(
        insecureContainer,
        secureContainerBuilder,
        system.dispatcher
      ),
      "/*"
    )
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    state.users.clear()
    state.organizations.clear()
    state.collections.clear()

    state.users.put(loggedInUser.id, loggedInUser)
    state.organizations.put(loggedInOrganization.id, loggedInOrganization)

    loggedInJwt = mintUserJwt(loggedInUser, loggedInOrganization)
  }

  /** Seed a collection directly into the in-memory store, mirroring what
    * `DataSetTestMixin.createCollection` did via the real manager. */
  private def createCollection(name: String): Collection = {
    val collection = Collection(id = state.newId(), name = name)
    state.collections.put((loggedInOrganization.id, collection.id), collection)
    collection
  }

  test("swagger") {
    import com.pennsieve.web.ResourcesApp
    addServlet(new ResourcesApp, "/api-docs/*")

    get("/api-docs/swagger.json") {
      status should equal(200)
    }
  }

  test("can create a collection") {
    val collectionRequest =
      write(CreateCollectionRequest(name = myCollectionName))

    postJson(
      s"/",
      collectionRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
      parsedBody.extract[CollectionDTO].name shouldBe myCollectionName
    }
  }

  test("can't create a collection with an empty name") {
    val collectionRequest = write(CreateCollectionRequest(name = ""))

    postJson(
      s"/",
      collectionRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
      (parsedBody \ "message")
        .extract[String] shouldBe "Collection name must be between 1 and 255 characters"
      (parsedBody \ "type").extract[String] shouldBe "BadRequest"
    }
  }

  test("can't create a collection with name with more than 255 characters") {
    val collectionRequest = write(CreateCollectionRequest(name = "1" * 256))

    postJson(
      s"/",
      collectionRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
      (parsedBody \ "message")
        .extract[String] shouldBe "Collection name must be between 1 and 255 characters"
      (parsedBody \ "type").extract[String] shouldBe "BadRequest"
    }
  }

  test("can't create a collection if the name already exists") {
    val collectionRequest =
      write(CreateCollectionRequest(name = myCollectionName))

    postJson(
      s"/",
      collectionRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
    }

    postJson(
      s"/",
      collectionRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
      (parsedBody \ "message")
        .extract[String] shouldBe "Collection name must be unique"
      (parsedBody \ "type").extract[String] shouldBe "BadRequest"
    }
  }

  test("can list all the collections") {
    postJson(
      s"/",
      write(CreateCollectionRequest(name = myCollectionName)),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
    }

    postJson(
      s"/",
      write(CreateCollectionRequest(name = myOtherCollectionName)),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
    }

    get(s"/", headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()) {
      parsedBody
        .extract[List[CollectionDTO]]
        .map(_.name) shouldBe List(myCollectionName, myOtherCollectionName)
    }
  }

  test("can update the name of a collection") {
    val collection = createCollection(myCollectionName)
    val collectionUpdateRequest =
      write(UpdateCollectionRequest(name = myNewOtherCollectionName))

    putJson(
      s"/${collection.id}",
      collectionUpdateRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    get(s"/", headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()) {
      parsedBody
        .extract[List[CollectionDTO]]
        .map(_.name) shouldBe List(myNewOtherCollectionName)
    }
  }

  test("can't update the name of a collection if the new name already exists") {
    val collection = createCollection(myCollectionName)
    createCollection(myOtherCollectionName)
    val collectionUpdateRequest =
      write(UpdateCollectionRequest(name = myOtherCollectionName))

    putJson(
      s"/${collection.id}",
      collectionUpdateRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
      (parsedBody \ "message")
        .extract[String] shouldBe "Collection name must be unique"
      (parsedBody \ "type").extract[String] shouldBe "BadRequest"
    }
  }

  test("can't update the name of a collection if the new name is empty") {
    val collection = createCollection(myCollectionName)
    val collectionUpdateRequest =
      write(UpdateCollectionRequest(name = ""))

    putJson(
      s"/${collection.id}",
      collectionUpdateRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
      (parsedBody \ "message")
        .extract[String] shouldBe "Collection name must be between 1 and 255 characters"
      (parsedBody \ "type").extract[String] shouldBe "BadRequest"
    }
  }

  test(
    "can't update the name of a collection if the new name is longer than 255 characters"
  ) {
    val collection = createCollection(myCollectionName)
    val collectionUpdateRequest =
      write(UpdateCollectionRequest(name = "1" * 256))

    putJson(
      s"/${collection.id}",
      collectionUpdateRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
      (parsedBody \ "message")
        .extract[String] shouldBe "Collection name must be between 1 and 255 characters"
      (parsedBody \ "type").extract[String] shouldBe "BadRequest"
    }
  }
}
