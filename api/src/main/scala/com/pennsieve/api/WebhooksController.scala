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

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.implicits._
import com.pennsieve.audit.middleware.Auditor
import com.pennsieve.aws.cognito.CognitoClient
import com.pennsieve.domain.CoreError
import com.pennsieve.dtos.{ APITokenSecretDTO, WebhookDTO }
import com.pennsieve.helpers.APIContainers.{
  InsecureAPIContainer,
  SecureContainerBuilderType
}
import com.pennsieve.helpers.ResultHandlers._
import com.pennsieve.helpers.either.EitherTErrorHandler.implicits._
import org.scalatra._
import org.scalatra.swagger.Swagger

import scala.concurrent.{ ExecutionContext, Future }
import com.pennsieve.models.{ DBPermission, NodeCodes, Role, User }

case class CreateWebhookRequest(
  apiUrl: String,
  imageUrl: Option[String],
  description: String,
  secret: String,
  displayName: String,
  targetEvents: Option[List[String]],
  hasAccess: Boolean,
  isPrivate: Boolean,
  isDefault: Boolean
)

case class UpdateWebhookRequest(
  apiUrl: Option[String] = None,
  imageUrl: Option[String] = None,
  description: Option[String] = None,
  secret: Option[String] = None,
  displayName: Option[String] = None,
  targetEvents: Option[List[String]] = None,
  hasAccess: Option[Boolean] = None,
  isPrivate: Option[Boolean] = None,
  isDefault: Option[Boolean] = None,
  isDisabled: Option[Boolean] = None
)

class WebhooksController(
  val insecureContainer: InsecureAPIContainer,
  val secureContainerBuilder: SecureContainerBuilderType,
  system: ActorSystem,
  auditLogger: Auditor,
  cognitoClient: CognitoClient,
  asyncExecutor: ExecutionContext
)(implicit
  val swagger: Swagger
) extends ScalatraServlet
    with AuthenticatedController {

  override protected implicit def executor: ExecutionContext = asyncExecutor

  override val pennsieveSwaggerTag = "Webhooks"

  post(
    "/",
    operation(
      apiOperation[WebhookDTO]("createWebhook")
        summary "creates a new webhook integration for an organization"
        parameters bodyParam[CreateWebhookRequest]("body").description(
          "properties for the new webhook including api url and secret key"
        )
    )
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, WebhookDTO] = for {
        secureContainer <- getSecureContainer

        body <- extractOrErrorT[CreateWebhookRequest](parsedBody)

        // All integrations have an associated user
        integrationUser <- secureContainer.userManager
          .createIntegrationUser(
            User(
              nodeId = NodeCodes.generateId(NodeCodes.userCode),
              "",
              firstName = "Integration",
              middleInitial = None,
              isIntegrationUser = true,
              degree = None,
              lastName = "User"
            )
          )
          .coreErrorToActionResult

        // Adding the integrationuser to the organization
        _ <- insecureContainer.organizationManager
          .addUser(
            secureContainer.organization,
            integrationUser,
            DBPermission.Delete
          )
          .coreErrorToActionResult

        // Create the API-Token and Secret:
        // Using INSECURE CONTAINER as we are creating an API key using a different user than the targeted user.
        tokenSecret <- insecureContainer.tokenManager
          .create(
            name = "Integration-user",
            user = integrationUser,
            organization = secureContainer.organization,
            cognitoClient = cognitoClient
          )
          .coreErrorToActionResult

        // Create the webhook and eventType subscriptions.
        newWebhookAndSubscriptions <- secureContainer.webhookManager
          .create(
            apiUrl = body.apiUrl,
            imageUrl = body.imageUrl,
            description = body.description,
            secret = body.secret,
            displayName = body.displayName,
            hasAccess = body.hasAccess,
            isPrivate = body.isPrivate,
            isDefault = body.isDefault,
            targetEvents = body.targetEvents,
            integrationUser = integrationUser
          )
          .coreErrorToActionResult

      } yield
        WebhookDTO(
          newWebhookAndSubscriptions._1,
          newWebhookAndSubscriptions._2,
          Some(APITokenSecretDTO(tokenSecret))
        )

      override val is: Future[ActionResult] = result.value.map(CreatedResult)
    }
  }

  get(
    "/",
    operation(
      apiOperation[List[WebhookDTO]]("getIntegrations")
        .summary(
          "gets all integrations that a user has permission to and that belong to the given organization"
        )
    )
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Seq[WebhookDTO]] =
        for {
          secureContainer <- getSecureContainer
          webhookMap <- {
            secureContainer.webhookManager.getWithSubscriptions
              .coreErrorToActionResult()
          }

          _ = println(webhookMap)

        } yield webhookMap.map(x => WebhookDTO(x._1, x._2))

      override val is: Future[ActionResult] = result.value.map(OkResult(_))
    }
  }

  get(
    "/:id",
    operation(
      apiOperation[WebhookDTO]("getWebhook")
        summary "get a webhook for an organization"
        parameters pathParam[Int]("id").description("webhook id")
    )
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, WebhookDTO] = for {
        secureContainer <- getSecureContainer
        webhookId <- paramT[Int]("id")

        webhookMap <- secureContainer.webhookManager
          .getWithSubscriptions(webhookId)
          .coreErrorToActionResult

      } yield WebhookDTO(webhookMap._1, webhookMap._2)

      override val is: Future[ActionResult] = result.value.map(OkResult(_))
    }
  }

  put(
    "/:id",
    operation(
      apiOperation[WebhookDTO]("updateWebhook")
        summary "update a webhook for an organization"
        parameters (pathParam[Int]("id").description("webhook id"),
        bodyParam[UpdateWebhookRequest]("body")
          .description("updates to webhook properties"))
    )
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, WebhookDTO] = for {
        secureContainer <- getSecureContainer
        webhookId <- paramT[Int]("id")
        body <- extractOrErrorT[UpdateWebhookRequest](parsedBody)

        webhook <- secureContainer.webhookManager
          .getWithPermissionCheck(webhookId, DBPermission.Administer)
          .coreErrorToActionResult

        updatedWebhook = webhook.copy(
          apiUrl = body.apiUrl.getOrElse(webhook.apiUrl),
          imageUrl = body.imageUrl.orElse(webhook.imageUrl),
          description = body.description.getOrElse(webhook.description),
          secret = body.secret.getOrElse(webhook.secret),
          displayName = body.displayName.getOrElse(webhook.displayName),
          isPrivate = body.isPrivate.getOrElse(webhook.isPrivate),
          isDefault = body.isDefault.getOrElse(webhook.isDefault),
          isDisabled = body.isDisabled.getOrElse(webhook.isDisabled)
        )
        updatedWebhookAndEvents <- secureContainer.webhookManager
          .update(updatedWebhook, body.targetEvents)
          .coreErrorToActionResult

      } yield WebhookDTO(updatedWebhookAndEvents._1, updatedWebhookAndEvents._2)

      override val is: Future[ActionResult] = result.value.map(OkResult)

    }
  }

  delete(
    "/:id",
    operation(
      apiOperation[Int]("deleteWebhook")
        summary "delete a webhook for an organization"
        parameters pathParam[Int]("id").description("webhook id")
    )
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Int] = for {
        secureContainer <- getSecureContainer
        webhookId <- paramT[Int]("id")
        webhook <- secureContainer.webhookManager
          .getWithPermissionCheck(webhookId, DBPermission.Administer)
          .coreErrorToActionResult

        deleted <- secureContainer.webhookManager
          .delete(webhook)
          .coreErrorToActionResult

        // Remove users associated with Webhook
        integrationMember <- insecureContainer.userManager
          .get(webhook.integrationUserId)
          .coreErrorToActionResult

        userDatasets <- secureContainer.datasetManager
          .find(integrationMember, Role.Viewer)
          .map(_.map(_.dataset).toList)
          .coreErrorToActionResult()

        integrationUserToken <- secureContainer.tokenManager
          .get(integrationMember, secureContainer.organization)
          .coreErrorToActionResult()

        token = integrationUserToken.headOption

        // Remove Integration API Key/Secret
        _ <- token match {
          case Some(token) =>
            // Use insecure container as the actor is not the owner of the token
            insecureContainer.tokenManager
              .delete(token, cognitoClient = cognitoClient)
              .coreErrorToActionResult()
          case None =>
            EitherT
              .rightT[Future, CoreError](())
              .coreErrorToActionResult()
        }

        // Remove Integration User from Datasets
        _ <- secureContainer.datasetManager
          .removeCollaborators(
            userDatasets,
            Set(integrationMember.nodeId),
            removeIntegrationUsers = true
          )
          .coreErrorToActionResult()

        // Remove Integration User from Organization
        _ <- secureContainer.organizationManager
          .removeUser(secureContainer.organization, integrationMember)
          .coreErrorToActionResult

      } yield deleted

      override val is: Future[ActionResult] = result.value.map(OkResult)

    }
  }
}
