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

import com.pennsieve.helpers.DataCanvasTestMixin

class TestDataCanvasController extends BaseApiTest with DataCanvasTestMixin {

  override def afterStart(): Unit = {
    super.afterStart()

    addServlet(
      new DataCanvasController(
        insecureContainer,
        secureContainerBuilder,
        system,
        system.dispatcher
      ),
      "/"
    )

  }

  test("get an existing data-canvas should return a 200") {
    val canvas = createDataCanvas("test canvas", "this is a test")
    val id = canvas.id

    get(s"/${id}") {
      status should equal(200)
    }
  }

  test("get an non-existant data-canvas should return a 404") {
    // create data-canvas
    // what is the id of the created data-canvas?
    val id = 314159

    get(s"/${id}") {
      status should equal(404)
    }
  }
}
