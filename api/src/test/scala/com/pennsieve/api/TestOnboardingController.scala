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

import com.pennsieve.models.{ Organization, User }

class TestOnboardingController extends BaseApiUnitTest {

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

  override def beforeAll(): Unit = {
    super.beforeAll()
    addServlet(
      new OnboardingController(
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
    state.users.put(loggedInUser.id, loggedInUser)
    state.organizations.put(loggedInOrganization.id, loggedInOrganization)
    loggedInJwt = mintUserJwt(loggedInUser, loggedInOrganization)
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
