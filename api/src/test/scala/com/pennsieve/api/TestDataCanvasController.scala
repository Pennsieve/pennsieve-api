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
}
