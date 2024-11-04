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

import cats.data.EitherT
import cats.implicits._
import com.pennsieve.aws.cognito.CognitoClient
import com.pennsieve.clients.{ CreateWorkflowRequest, IntegrationServiceClient }
import com.pennsieve.core.utilities.JwtAuthenticator
import com.pennsieve.dtos.{ APITokenSecretDTO, WorkflowDTO }
import com.pennsieve.helpers.APIContainers.{
  InsecureAPIContainer,
  SecureContainerBuilderType
}
import com.pennsieve.helpers.ResultHandlers._
import com.pennsieve.helpers.either.EitherTErrorHandler.implicits._
import com.pennsieve.models.{ DBPermission, NodeCodes, User }
import org.scalatra._
import org.scalatra.swagger.Swagger

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ ExecutionContext, Future }

class WorkflowsController(
  val insecureContainer: InsecureAPIContainer,
  val secureContainerBuilder: SecureContainerBuilderType,
  cognitoClient: CognitoClient,
  integrationServiceClient: IntegrationServiceClient,
  asyncExecutor: ExecutionContext
)(implicit
  val swagger: Swagger
) extends ScalatraServlet
    with AuthenticatedController {

  override protected implicit def executor: ExecutionContext = asyncExecutor

  override val pennsieveSwaggerTag = "Workflows"

  post(
    "/",
    operation(
      apiOperation[WorkflowDTO]("createWorkflow")
        summary "creates a new workflow integration for an organization"
        parameters bodyParam[CreateWorkflowRequest]("body")
          .description("properties for the new workflow")
    )
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, WorkflowDTO] = for {
        secureContainer <- getSecureContainer()
        body <- extractOrErrorT[CreateWorkflowRequest](parsedBody)
        _ = body

        // All integrations have an associated user
        workflowIntegrationUser <- secureContainer.userManager
          .createIntegrationUser(
            User(
              nodeId = NodeCodes.generateId(NodeCodes.userCode),
              "",
              firstName = "Workflow-Integration",
              middleInitial = None,
              isIntegrationUser = true,
              degree = None,
              lastName = "User"
            )
          )
          .coreErrorToActionResult()

        // Adding the workflowIntegrationUser to the organization
        _ <- insecureContainer.organizationManager
          .addUser(
            secureContainer.organization,
            workflowIntegrationUser,
            DBPermission.Delete
          )
          .coreErrorToActionResult()

        // Create the API-Token and Secret:
        tokenSecret <- insecureContainer.tokenManager
          .create(
            name = "Workflow-Integration-user",
            user = workflowIntegrationUser,
            organization = secureContainer.organization,
            cognitoClient = cognitoClient
          )
          .coreErrorToActionResult()

        // call integration-service: /workflows endpoint to create workflow
        serviceToken = JwtAuthenticator.generateServiceToken(
          1.minute,
          secureContainer.organization.id,
          Some(body.datasetIntId)
        )

        _ <- integrationServiceClient
          .postWorkflows(
            body.copy(
              apiToken = tokenSecret._1.token,
              apiSecret = tokenSecret._2.plaintext
            ),
            serviceToken
          )
          .coreErrorToActionResult()

      } yield WorkflowDTO(Some(APITokenSecretDTO(tokenSecret)))

      val _ = result

      override val is: Future[ActionResult] = result.value.map(CreatedResult)
    }
  }
}
