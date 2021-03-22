// Copyright (c) 2020 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.api

import com.blackfynn.dtos.CollectionDTO
import com.blackfynn.helpers._
import io.circe.{ Decoder, Encoder }
import org.json4s._
import org.json4s.jackson.JsonMethods._
import cats.implicits._

import org.json4s.jackson.Serialization.write

class TestCollectionsController extends BaseApiTest with DataSetTestMixin {
  override def afterStart(): Unit = {
    super.afterStart()

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
  }

  override def afterEach(): Unit = {
    super.afterEach()
  }

  val myCollectionName = "My Collection"
  val myOtherCollectionName = "My Other Collection"
  val myNewOtherCollectionName = "My New Other Collection"

  test("can create a collection") {

    val collectionRequest =
      write(CreateCollectionRequest(name = myCollectionName))

    postJson(
      s"/",
      collectionRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
      parsedBody
        .extract[CollectionDTO]
        .name shouldBe myCollectionName
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

    val collectionRequest = write(
      CreateCollectionRequest(
        name = "1234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456"
      )
    )

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

    val collectionRequest =
      write(CreateCollectionRequest(name = myCollectionName))

    postJson(
      s"/",
      collectionRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
    }

    val collectionRequest2 =
      write(CreateCollectionRequest(name = myOtherCollectionName))

    postJson(
      s"/",
      collectionRequest2,
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

    val collection2 = createCollection(myOtherCollectionName)

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
      write(
        UpdateCollectionRequest(
          name = "1234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456"
        )
      )

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
