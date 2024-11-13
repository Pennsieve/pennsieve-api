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

class TestSecurityController extends BaseApiTest {

  override def afterStart(): Unit = {
    super.afterStart()
    addServlet(
      new SecurityController(
        insecureContainer = insecureContainer,
        secureContainerBuilder = secureContainerBuilder,
        asyncExecutor = system.dispatcher
      ),
      "/*"
    )
  }

  test(
    "swagger should show GET '/user/credentials/upload/{dataset}' as deprecated"
  ) {
    import com.pennsieve.web.ResourcesApp
    addServlet(new ResourcesApp, "/api-docs/*")

    get("/api-docs/swagger.json") {
      status should equal(200)
      val doc = parsedBody \ "paths" \ "/user/credentials/upload/{dataset}" \ "get"

      val summary = (doc \ "summary").extract[String]
      summary should endWith("[deprecated]")

      (doc \ "deprecated").extract[Boolean] shouldBe true
    }
  }

  test(
    "GET '/user/credentials/upload/{dataset}' should return a deprecation warning header"
  ) {
    get(
      "/user/credentials/upload/fake-dataset-id",
      headers = authorizationHeader(loggedInJwt)
    ) {
      response.headers should contain key "Warning"
      response.getHeader("Warning") should include("deprecated")
    }
  }

}
