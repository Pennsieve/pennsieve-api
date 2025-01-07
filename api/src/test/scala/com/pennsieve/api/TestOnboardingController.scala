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

class TestOnboardingController extends BaseApiTest {

  override def afterStart(): Unit = {
    super.afterStart()
    addServlet(
      new OnboardingController(
        insecureContainer,
        secureContainerBuilder,
        system.dispatcher
      ),
      "/*"
    )
  }

  test("swagger should not contain documentation for '/events'") {
    import com.pennsieve.web.ResourcesApp
    addServlet(new ResourcesApp, "/api-docs/*")

    get("/api-docs/swagger.json") {
      status should equal(200)

      val pathsDoc = (parsedBody \ "paths").extract[Map[String, _]]
      pathsDoc should not contain key("/events")

    }
  }

  test("GET should return a Gone response code") {
    get(s"/events", headers = authorizationHeader(loggedInJwt)) {
      status should equal(410)
    }
  }

  test("POST should return a Gone response code") {
    postJson(
      s"/events",
      "\"FirstTimeSignOn\"",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(410)
    }
  }

}
