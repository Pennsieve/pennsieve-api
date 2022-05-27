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
    val id = 314159

    get(
      s"/${id}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }
}
