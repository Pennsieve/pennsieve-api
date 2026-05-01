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

package com.pennsieve.helpers.fakes

import cats.data.EitherT
import com.pennsieve.core.utilities.slugify
import com.pennsieve.db.{
  WebhookEventSubscriptionsMapper,
  WebhookEventTypesMapper,
  WebhooksMapper
}
import com.pennsieve.domain.{
  CoreError,
  InvalidAction,
  NotFound,
  PermissionError,
  PredicateError
}
import com.pennsieve.dtos.WebhookTargetDTO
import com.pennsieve.managers.WebhookManager
import com.pennsieve.models.{ DBPermission, Organization, User, Webhook }
import com.pennsieve.traits.PostgresProfile

import scala.concurrent.{ ExecutionContext, Future }

/** In-memory fake `WebhookManager`. Reads/writes `state.webhooks` and tracks
  * subscriptions in a separate map. Event-name validation uses a fixed seed
  * mirroring what `pennsievedb` provides in production. */
class FakeWebhookManager(
  val state: InMemoryState,
  org: Organization,
  val actor: User
) extends WebhookManager {

  def db: PostgresProfile.api.Database =
    sys.error(
      "FakeWebhookManager: a method not yet stubbed by your test tried to " +
        "use the database. Override the method on this fake."
    )

  override lazy val webhooksMapper: WebhooksMapper = new WebhooksMapper(org)
  override lazy val webhookEventSubscriptionsMapper
    : WebhookEventSubscriptionsMapper =
    new WebhookEventSubscriptionsMapper(org)
  override lazy val webhookEventTypesMapper: WebhookEventTypesMapper =
    new WebhookEventTypesMapper(org)

  // Mirrors seed data in `pennsievedb`. Tests assert against this exact set.
  private val ValidEvents: Set[String] =
    Set("METADATA", "FILES", "STATUS", "PERMISSIONS")

  private def validate(
    apiUrl: String,
    imageUrl: Option[String],
    description: String,
    secret: String,
    displayName: String,
    integrationUserId: Int
  ): Option[CoreError] = {
    val trimmedImage = imageUrl.map(_.trim).filter(_.nonEmpty)
    if (apiUrl.trim.length >= 256 || apiUrl.trim.isEmpty)
      Some(PredicateError("api url must be between 1 and 255 characters"))
    else if (trimmedImage.exists(_.length >= 256))
      Some(
        PredicateError("image url must be less than or equal to 255 characters")
      )
    else if (description.trim.length >= 200 || description.trim.isEmpty)
      Some(PredicateError("description must be between 1 and 199 characters"))
    else if (secret.trim.length >= 256 || secret.trim.isEmpty)
      Some(PredicateError("secret must be between 1 and 255 characters"))
    else if (integrationUserId <= 0)
      Some(PredicateError("integration user should be populated"))
    else if (displayName.trim.length >= 256 || displayName.trim.isEmpty)
      Some(PredicateError("display name must be between 1 and 255 characters"))
    else None
  }

  override def create(
    apiUrl: String,
    imageUrl: Option[String],
    description: String,
    secret: String,
    displayName: String,
    isPrivate: Boolean,
    isDefault: Boolean,
    hasAccess: Boolean,
    targetEvents: Option[List[String]],
    customTargets: Option[List[WebhookTargetDTO]],
    integrationUser: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Webhook, Seq[String])] = {
    validate(
      apiUrl,
      imageUrl,
      description,
      secret,
      displayName,
      integrationUser.id
    ) match {
      case Some(err) => EitherT.leftT(err)
      case None =>
        targetEvents match {
          case Some(events) if events.exists(e => !ValidEvents.contains(e)) =>
            EitherT.leftT(
              PredicateError(s"$events contains an unknown event name"): CoreError
            )
          case _ =>
            val id = state.newId()
            val webhook = Webhook(
              apiUrl = apiUrl.trim,
              imageUrl = imageUrl.map(_.trim).filter(_.nonEmpty),
              description = description.trim,
              secret = secret.trim,
              name = slugify(displayName),
              displayName = displayName.trim,
              isPrivate = isPrivate,
              isDefault = isDefault,
              isDisabled = false,
              hasAccess = hasAccess,
              integrationUserId = integrationUser.id,
              customTargets = customTargets,
              createdBy = actor.id,
              createdAt = InMemoryState.now(),
              id = id
            )
            state.webhooks.put((org.id, id), webhook)
            val events = targetEvents.getOrElse(Nil)
            state.webhookSubscriptions.put((org.id, id), events)
            EitherT.rightT((webhook, events))
        }
    }
  }

  override def update(
    webhook: Webhook,
    targetEvents: Option[List[String]] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Webhook, Seq[String])] = {
    validate(
      webhook.apiUrl,
      webhook.imageUrl,
      webhook.description,
      webhook.secret,
      webhook.displayName,
      webhook.integrationUserId
    ) match {
      case Some(err) => EitherT.leftT(err)
      case None =>
        state.webhooks.get((org.id, webhook.id)) match {
          case None =>
            EitherT.leftT(NotFound(s"Webhook (${webhook.id})"): CoreError)
          case Some(_) =>
            // Mirror real-impl normalization: trim strings, slugify name from
            // displayName, drop blank imageUrl. Without this, "remove image
            // url" (sends Some("")) and "update display name" (which should
            // re-slugify the name) would round-trip incorrectly.
            val normalized = webhook.copy(
              apiUrl = webhook.apiUrl.trim,
              imageUrl = webhook.imageUrl.map(_.trim).filter(_.nonEmpty),
              description = webhook.description.trim,
              secret = webhook.secret.trim,
              displayName = webhook.displayName.trim,
              name = slugify(webhook.displayName)
            )
            val current =
              state.webhookSubscriptions.getOrElse((org.id, webhook.id), Nil)
            targetEvents match {
              case None =>
                state.webhooks.put((org.id, webhook.id), normalized)
                EitherT.rightT((normalized, current))
              case Some(Nil) =>
                state.webhooks.put((org.id, webhook.id), normalized)
                state.webhookSubscriptions.put((org.id, webhook.id), Nil)
                EitherT.rightT((normalized, Nil))
              case Some(events)
                  if events.exists(e => !ValidEvents.contains(e)) =>
                EitherT.leftT(
                  PredicateError(s"$events contains an unknown event name"): CoreError
                )
              case Some(events) =>
                state.webhooks.put((org.id, webhook.id), normalized)
                state.webhookSubscriptions.put((org.id, webhook.id), events)
                EitherT.rightT((normalized, events))
            }
        }
    }
  }

  override def get(
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[Webhook]] =
    EitherT.rightT(visibleWebhooks)

  override def getWithSubscriptions(
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[(Webhook, Seq[String])]] =
    EitherT.rightT(visibleWebhooks.map { w =>
      w -> state.webhookSubscriptions.getOrElse((org.id, w.id), Nil)
    })

  override def getWithSubscriptions(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Webhook, Seq[String])] =
    state.webhooks.get((org.id, id)) match {
      case None => EitherT.leftT(NotFound(s"Webhook ($id)"): CoreError)
      case Some(w) =>
        if (w.isPrivate && w.createdBy != actor.id && !actor.isSuperAdmin)
          EitherT.leftT(
            InvalidAction(
              s"user ${actor.id} does not have access to webhook ${w.id}"
            ): CoreError
          )
        else
          EitherT.rightT(
            w -> state.webhookSubscriptions.getOrElse((org.id, id), Nil)
          )
    }

  override def getWithPermissionCheck(
    webhookId: Int,
    withPermission: DBPermission = DBPermission.Read
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Webhook] =
    state.webhooks.get((org.id, webhookId)) match {
      case None => EitherT.leftT(NotFound(s"Webhook ($webhookId)"): CoreError)
      case Some(w) =>
        val orgPerm = state.orgUserPermissions.get((org.id, actor.id))
        val ok = actor.isSuperAdmin || w.createdBy == actor.id ||
          orgPerm.exists(_.value >= withPermission.value)
        if (ok) EitherT.rightT(w)
        else
          EitherT.leftT(
            PermissionError(
              actor.nodeId,
              withPermission,
              s"Webhook ($webhookId)"
            ): CoreError
          )
    }

  override def delete(
    webhook: Webhook
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] = {
    state.webhooks.remove((org.id, webhook.id)) match {
      case Some(_) =>
        state.webhookSubscriptions.remove((org.id, webhook.id))
        EitherT.rightT(1)
      case None => EitherT.rightT(0)
    }
  }

  override def getForIntegration(
    webhookId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Webhook] =
    state.webhooks.get((org.id, webhookId)) match {
      case Some(w) =>
        if (!w.isPrivate || actor.isSuperAdmin || w.createdBy == actor.id)
          EitherT.rightT(w)
        else
          EitherT.leftT(
            InvalidAction(
              s"user ${actor.nodeId} does not have dataset integration access to webhook ${w.id}"
            ): CoreError
          )
      case None => EitherT.leftT(NotFound(s"Webhook ($webhookId)"): CoreError)
    }

  private def visibleWebhooks: Seq[Webhook] =
    state.webhooks
      .collect {
        case ((orgId, _), w)
            if orgId == org.id && (!w.isPrivate || w.createdBy == actor.id) =>
          w
      }
      .toSeq
      .sortBy(_.id)
}
