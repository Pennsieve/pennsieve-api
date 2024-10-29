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

import com.pennsieve.aws.cognito.MockCognito
import com.pennsieve.clients.CreateWorkflowRequest
import com.pennsieve.helpers.{ DataSetTestMixin, MockAuditLogger }
import org.json4s.jackson.Serialization.write

class TestWorkflowsController extends BaseApiTest {

  val auditLogger = new MockAuditLogger()
  val mockCognito = new MockCognito()

  override def afterStart(): Unit = {
    super.afterStart()

    addServlet(
      new WorkflowsController(
        insecureContainer,
        secureContainerBuilder,
        mockCognito,
        system.dispatcher
      ),
      "/*"
    )
  }

  test("create a workflow") {
    val req = write(
      CreateWorkflowRequest(
        workflowName = "CyTOF-analysis-pipeline",
        description = "Pipeline for running end-to-end analysis on CyTOF data",
        secret = "secretkey",
        datasetId = "someDatasetId"
      )
    )

    postJson(s"/", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(201)
    }
  }
}
