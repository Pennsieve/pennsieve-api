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

import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import com.pennsieve.dtos.DataCanvasDTO
import com.pennsieve.helpers.DataCanvasTestMixin

import scala.concurrent.Future
import org.json4s.jackson.Serialization.{ read, write }

import scala.util.Random

// TODO: add the following tests
//   1. create with empty name
//   2. create with duplicate name
//   3. update with empty name

class TestDataCanvasController extends BaseApiTest with DataCanvasTestMixin {

  override def afterStart(): Unit = {
    super.afterStart()

    implicit val httpClient: HttpRequest => Future[HttpResponse] = { _ =>
      Future.successful(HttpResponse())
    }

    addServlet(
      new DataCanvasController(
        insecureContainer,
        secureContainerBuilder,
        system,
        system.dispatcher
      ),
      "/*"
    )

  }

  override def afterEach(): Unit = {
    super.afterEach()
  }

  /**
    * GET tests (read)
    */
  test("get requires authentication") {
    val canvas = createDataCanvas(
      "test: get requires authentication",
      "test: get requires authentication"
    )
    get(s"/${canvas.id}") {
      status should equal(401)
    }
  }

  test("get an existing data-canvas") {
    val canvas = createDataCanvas(
      "test: get an existing data-canvas",
      "test: get an existing data-canvas"
    )
    get(
      s"/${canvas.id}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }
  }

  test("get a non-existant data-canvas should return a 404") {
    get(
      s"/${bogusCanvasId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  /**
    * POST tests (create)
    */
  test("create a new data-canvas") {
    val createDataCanvasRequest = write(
      CreateDataCanvasRequest(
        name = "test: create a new data-canvas",
        description = "test: create a new data-canvas"
      )
    )
    postJson(
      "/",
      createDataCanvasRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
    }
  }

  test("create requires authentication") {
    val createDataCanvasRequest = write(
      CreateDataCanvasRequest(
        name = "test: create requires authentication",
        description = "test: create requires authentication"
      )
    )
    postJson("/", createDataCanvasRequest) {
      status should equal(401)
    }
  }

  test("create does not permit name > 255 chars") {
    val createDataCanvasRequest = write(
      CreateDataCanvasRequest(
        name = randomString(256),
        description = "test: create a new data-canvas"
      )
    )
    postJson(
      "/",
      createDataCanvasRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
    }
  }

  test("create by default data-canvas is not public") {
    val createDataCanvasRequest = write(
      CreateDataCanvasRequest(
        name = randomString(64),
        description = "test: create a new data-canvas"
      )
    )
    postJson(
      "/",
      createDataCanvasRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)

      val result: DataCanvasDTO = parsedBody
        .extract[DataCanvasDTO]

      result.isPublic shouldBe false
    }
  }

  test("create a public data-canvas") {
    val createDataCanvasRequest = write(
      CreateDataCanvasRequest(
        name = randomString(64),
        description = "test: create a new data-canvas",
        isPublic = Some(true)
      )
    )
    postJson(
      "/",
      createDataCanvasRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)

      val result: DataCanvasDTO = parsedBody
        .extract[DataCanvasDTO]

      result.isPublic shouldBe true
    }
  }

  /**
    * PUT tests (update)
    */
  test("update requires authentication (401)") {
    val canvas = createDataCanvas(
      "test: update requires authentication",
      "test: update requires authentication"
    )
    val updateDataCanvasRequest = write(
      UpdateDataCanvasRequest(
        name = "test: update requires authentication UPDATED",
        description = "test: update requires authentication UPDATED"
      )
    )

    putJson(s"/${canvas.id}", updateDataCanvasRequest) {
      status should equal(401)
    }
  }

  test("update an existing data-canvas") {
    val canvas = createDataCanvas(
      "test: update an existing data-canvas",
      "test: update an existing data-canvas"
    )
    val updateDataCanvasRequest = write(
      UpdateDataCanvasRequest(
        name = "test: update an existing data-canvas UPDATED",
        description = "test: update an existing data-canvas UPDATED"
      )
    )

    putJson(
      s"/${canvas.id}",
      updateDataCanvasRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
    }
  }

  test("update a non-existent data-canvas should fail") {
    val updateDataCanvasRequest = write(
      UpdateDataCanvasRequest(
        name = randomString(),
        description = "test: update an existing data-canvas UPDATED"
      )
    )

    putJson(
      s"/${bogusCanvasId}",
      updateDataCanvasRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  test("update does not permit name > 255 chars") {
    val canvas = createDataCanvas(
      "test: update an existing data-canvas",
      "test: update an existing data-canvas"
    )
    val updateDataCanvasRequest = write(
      UpdateDataCanvasRequest(
        name = randomString(256),
        description = "test: update an existing data-canvas UPDATED"
      )
    )

    putJson(
      s"/${canvas.id}",
      updateDataCanvasRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
    }
  }

  test("update a data-canvas to be publicly visible") {
    // first create a data-canvas which will not be publicly visible
    val canvas = createDataCanvas()
    canvas.isPublic shouldBe false

    // update the data-canvas to make it publicly visible
    val updateDataCanvasRequest = write(
      UpdateDataCanvasRequest(
        name = "test: update an existing data-canvas UPDATED",
        description = "test: update an existing data-canvas UPDATED",
        isPublic = Some(true)
      )
    )

    putJson(
      s"/${canvas.id}",
      updateDataCanvasRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)

      val result: DataCanvasDTO = parsedBody
        .extract[DataCanvasDTO]

      result.isPublic shouldBe true
    }
  }

  /**
    * DELETE tests
    */
  test("delete requires authentication") {
    val canvas = createDataCanvas()
    delete(s"/${canvas.id}") {
      status should equal(401)
    }
  }

  test("delete an existing data-canvas") {
    val canvas = createDataCanvas()
    delete(
      s"/${canvas.id}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }
  }

  test("delete a non-existent data-canvas should fail") {
    delete(
      s"/${bogusCanvasId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  /**
    * Public All DataCanvases View
    */
  test("public get data-canvases requires authentication") {
    // create data-canvas with isPublic = true
    val canvas = createDataCanvas(isPublic = true)
    // invoke API without authorization
    get(s"/get/${canvas.nodeId}") {
      status should equal(401)
    }
  }

  test("public get a publicly available data-canvas when authenticated") {
    // create data-canvas with isPublic = true
    val canvas = createDataCanvas(isPublic = true)
    // invoke API with authorization
    get(
      s"/get/${canvas.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }
  }

  test("public get fails because data-canvas is not publicly available") {
    // create data-canvas with isPublic = false (the default case)
    val canvas = createDataCanvas(isPublic = false)
    // invoke API with authorization
    get(
      s"/get/${canvas.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(401)
    }
  }

  test(
    "public get a publicly available data-canvas from a different organization"
  ) {
    // create data-canvas with isPublic = true, in one organization
    val canvas = createDataCanvas(isPublic = true)
    // invoke API with authorization for a user in a different organization
    get(
      s"/get/${canvas.nodeId}",
      headers = authorizationHeader(externalJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }
  }

}