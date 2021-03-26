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

import com.pennsieve.aws.email.LoggingEmailer
import com.pennsieve.models.DBPermission
import com.pennsieve.web.Settings
import com.pennsieve.aws.cognito.MockCognito
import com.pennsieve.audit.middleware.Auditor
import com.pennsieve.helpers.MockAuditLogger

import java.time.Duration

import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.write
import org.scalatest.EitherValues._
import org.scalatest._

class TestSwaggerDocs extends BaseApiTest {

  val auditLogger: Auditor = new MockAuditLogger()

  override def afterStart(): Unit = {
    super.afterStart()

    addServlet(
      new AccountController(insecureContainer, system.dispatcher),
      "/*"
    )

    addServlet(
      new AnnotationsController(
        insecureContainer,
        secureContainerBuilder,
        system.dispatcher,
        auditLogger
      ),
      "/annotations/*"
    )

    addServlet(
      new CollectionsController(
        insecureContainer,
        secureContainerBuilder,
        system.dispatcher
      ),
      "/collections/*"
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

}
