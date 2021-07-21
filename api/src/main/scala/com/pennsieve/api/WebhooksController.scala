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
import com.pennsieve.dtos._
import com.pennsieve.helpers.APIContainers.{
  InsecureAPIContainer,
  SecureContainerBuilderType
}
import com.pennsieve.helpers.ResultHandlers._
import com.pennsieve.helpers.either.EitherTErrorHandler.implicits._
import com.pennsieve.core.utilities.checkOrErrorT
import org.scalatra._
import org.scalatra.swagger.Swagger

import scala.concurrent.{ ExecutionContext, Future }
import com.pennsieve.models.{ Role, User, Webhook, WebhookEventSubcription }

case class CreateWebhookRequest(
  apiUrl: String,
  imageUrl: Option[String],
  description: String,
  secret: String,
  displayName: String,
  targetEvents: Option[List[String]],
  isPrivate: Boolean,
  isDefault: Boolean
)

class WebhooksController(
  val insecureContainer: InsecureAPIContainer,
  val secureContainerBuilder: SecureContainerBuilderType,
  system: ActorSystem,
  auditLogger: Auditor,
  asyncExecutor: ExecutionContext
)(implicit
  val swagger: Swagger
) extends ScalatraServlet
    with AuthenticatedController {

  override protected implicit def executor: ExecutionContext = asyncExecutor

  override val swaggerTag = "Webhooks"

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

        newWebhookAndSubscriptions <- secureContainer.webhookManager
          .create(
            apiUrl = body.apiUrl,
            imageUrl = body.imageUrl,
            description = body.description,
            secret = body.secret,
            displayName = body.displayName,
            isPrivate = body.isPrivate,
            isDefault = body.isDefault,
            targetEvents = body.targetEvents,
            createdBy = secureContainer.user.id
          )
          .coreErrorToActionResult

      } yield
        WebhookDTO(newWebhookAndSubscriptions._1, newWebhookAndSubscriptions._2)

      override val is = result.value.map(CreatedResult)
    }
  }

  get(
    "/",
    operation(
      apiOperation[List[WebhookDTO]]("getIntegrations")
        summary "gets all integrations that a user has permission to and that belong to the given organization"
        parameters ()
    )
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Seq[WebhookDTO]] =
        for {
          secureContainer <- getSecureContainer
          organization = secureContainer.organization

          integrations <- {
            secureContainer.webhookManager
              .get()
              .coreErrorToActionResult()
          }

        } yield integrations.map(WebhookDTO(_))

      override val is = result.value.map(OkResult(_))
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

      } yield WebhookDTO(webhookMap.head._1, webhookMap.head._2)

      override val is = result.value.map(OkResult(_))
    }
  }
}
