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

package com.pennsieve.managers

import cats.data._
import cats.implicits._
import com.pennsieve.core.utilities.{
  checkOrError,
  checkOrErrorT,
  slugify,
  trimOptional,
  FutureEitherHelpers
}
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.db.{ WebhooksMapper, _ }
import com.pennsieve.domain.{ CoreError, _ }
import com.pennsieve.dtos.WebhookTargetDTO
import com.pennsieve.models._
import com.pennsieve.traits.PostgresProfile
import com.pennsieve.traits.PostgresProfile.api._

import java.time.ZonedDateTime
import scala.concurrent.{ ExecutionContext, Future }
import scala.collection.compat._

class WebhookManager(
  val db: PostgresProfile.api.Database,
  val actor: User,
  val webhooksMapper: WebhooksMapper,
  val webhookEventSubscriptionsMapper: WebhookEventSubscriptionsMapper,
  val webhookEventTypesMapper: WebhookEventTypesMapper
) {

  val organization: Organization = webhooksMapper.organization

  def validateWebhookValues(
    apiUrl: String,
    imageUrl: Option[String],
    description: String,
    secret: String,
    displayName: String,
    isPrivate: Boolean,
    isDefault: Boolean,
    isDisabled: Boolean,
    hasAccess: Boolean,
    integrationUserId: Int,
    customTargets: Option[List[WebhookTargetDTO]],
    createdBy: Int = actor.id,
    createdAt: ZonedDateTime = ZonedDateTime.now(),
    id: Int = 0
  ): Either[PredicateError, Webhook] = {
    val trimmedImageUrl = trimOptional(imageUrl)

    for {
      _ <- checkOrError(apiUrl.trim.length < 256 && apiUrl.trim.nonEmpty)(
        PredicateError("api url must be between 1 and 255 characters")
      )

      _ <- checkOrError(
        trimmedImageUrl.isEmpty || trimmedImageUrl.get.length < 256
      )(
        PredicateError("image url must be less than or equal to 255 characters")
      )

      _ <- checkOrError(
        description.trim.length < 200 && description.trim.nonEmpty
      )(PredicateError("description must be between 1 and 199 characters"))

      _ <- checkOrError(secret.trim.length < 256 && secret.trim.nonEmpty)(
        PredicateError("secret must be between 1 and 255 characters")
      )

      _ <- checkOrError(integrationUserId > 0)(
        PredicateError("integration user should be populated")
      )

      _ <- checkOrError(
        displayName.trim.length < 256 && displayName.trim.nonEmpty
      )(PredicateError("display name must be between 1 and 255 characters"))

      validated = Webhook(
        apiUrl.trim,
        trimmedImageUrl,
        description.trim,
        secret.trim,
        slugify(displayName),
        displayName.trim,
        isPrivate,
        isDefault,
        isDisabled,
        hasAccess,
        integrationUserId,
        customTargets,
        createdBy,
        createdAt,
        id
      )
    } yield validated
  }

  def validateWebhook(webhook: Webhook): Either[PredicateError, Webhook] = {
    validateWebhookValues(
      webhook.apiUrl,
      webhook.imageUrl,
      webhook.description,
      webhook.secret,
      webhook.displayName,
      webhook.isPrivate,
      webhook.isDefault,
      webhook.isDisabled,
      webhook.hasAccess,
      webhook.integrationUserId,
      webhook.customTargets,
      webhook.createdBy,
      webhook.createdAt,
      webhook.id
    )
  }

  def create(
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

    val insertQuery: DBIO[(Webhook, Seq[String])] = for {

      // insert the row in the webhook table
      webhookId: Int <- validateWebhookValues(
        apiUrl,
        imageUrl,
        description,
        secret,
        displayName,
        isPrivate,
        isDefault,
        isDisabled = false,
        hasAccess,
        integrationUser.id,
        customTargets
      ) match {
        case Right(webhook) => insertWebhook(webhook)
        case Left(error) => DBIO.failed(error)
      }

      // insert the subscriptions
      subscribedEvents <- updateEventsAction(webhookId, targetEvents)

      // return created webhook with populated Id
      createdWebhook: Webhook <- webhooksMapper
        .filter(_.id === webhookId)
        .result
        .head

    } yield (createdWebhook, subscribedEvents)

    // Run the SQL action sequence and return the webhook
    db.run(insertQuery.transactionally).toEitherT

  }

  def insertWebhook(row: Webhook): DBIO[Int] =
    webhooksMapper returning webhooksMapper.map(_.id) += row

  def insertSubscriptions(
    rows: Seq[WebhookEventSubcription]
  ): DBIO[Option[Int]] = webhookEventSubscriptionsMapper ++= rows

  def getSubscribedEventNames(
    webhookId: Int
  ): Query[Rep[String], String, Seq] = {
    for {
      (sub, event) <- webhookEventSubscriptionsMapper join webhookEventTypesMapper on (_.webhookEventTypeId === _.id)
      if sub.webhookId === webhookId
    } yield event.eventName
  }

  /**
    * Returns a [[DBIO]] that will update the event subscriptions of the webhook with id `webhookId` to match those
    * in `requestedEvents` if non-empty and then return the names of events currently subscribed to by
    * the webhook.
    *
    * Updates are performed by deleting any event subscriptions not listed in `requestedEvents`
    * and creating any subscriptions listed in `requestedEvents` which do not currently exist.
    *
    * If `requestedEvents` is [[None]] no subscription updates will be performed.
    *
    * If `requestedEvents` is [[Some(Nil)]] all subscriptions will be deleted.
    *
    * if `requestedEvents` contains unknown events the action will fail with a [[PredicateError]].
    *
    * @param webhookId       id of the webhook being updated
    * @param requestedEvents names of events
    * @param ec              execution context
    * @return the names of events to which the given webhook currently subscribes
    */
  def updateEventsAction(
    webhookId: Int,
    requestedEvents: Option[List[String]]
  )(implicit
    ec: ExecutionContext
  ): DBIO[Seq[String]] = {
    requestedEvents match {
      case None =>
        for (currentEventNames <- getSubscribedEventNames(webhookId).result)
          yield currentEventNames
      case Some(Nil) =>
        for {
          _ <- webhookEventSubscriptionsMapper
            .filter(_.webhookId === webhookId)
            .delete
        } yield Nil
      case Some(events) =>
        for {
          requestedEventIds <- webhookEventTypesMapper.getTargetEventIds(events)
          _ <- assert(requestedEventIds.toSet.size == events.toSet.size)(
            PredicateError(s"$events contains an unknown event name")
          )
          // Delete subscriptions not in requested events
          _ <- webhookEventSubscriptionsMapper
            .filter(_.webhookId === webhookId)
            .filterNot(_.id inSet requestedEventIds)
            .delete

          // Insert requested subscriptions that don't already exist
          existingSubscriptionEventIds <- webhookEventSubscriptionsMapper
            .filter(_.webhookId === webhookId)
            .map(_.webhookEventTypeId)
            .result
          _ <- insertSubscriptions(
            requestedEventIds
              .filterNot(existingSubscriptionEventIds contains _)
              .map(WebhookEventSubcription(webhookId, _))
          )
        } yield events
    }

  }

  def update(
    webhook: Webhook,
    targetEvents: Option[List[String]] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Webhook, Seq[String])] = {

    val updateWebhookAction: DBIO[Int] = validateWebhook(webhook) match {
      case Right(updates) =>
        webhooksMapper.filter(_.id === webhook.id).update(updates)
      case Left(error) => DBIO.failed(error)
    }

    val action = for {
      rowCount <- updateWebhookAction
      updatedWebhook <- if (rowCount == 0) {
        DBIO.failed(NotFound(s"Webhook (${webhook.id})"))
      } else {
        webhooksMapper.filter(_.id === webhook.id).result.head
      }
      currentEventNames <- updateEventsAction(webhook.id, targetEvents)
    } yield (updatedWebhook, currentEventNames)

    db.run(action.transactionally).toEitherT
  }

  /*
  Get all webhooks for user without subscriptions
   */
  def get(
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[Webhook]] =
    db.run(webhooksMapper.find(actor).result).toEitherT

  /*
  Get all webhooks for user and subscriptions
   */
  def getWithSubscriptions(
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[(Webhook, Seq[String])]] = {

    val query = for {
      ((w, _), t) <- webhooksMapper filter (
        x => (x.isPrivate === false || x.createdBy === actor.id)
      ) joinLeft
        webhookEventSubscriptionsMapper on (_.id === _.webhookId) joinLeft
        webhookEventTypesMapper on (_._2.map(_.webhookEventTypeId) === _.id)
    } yield (w, t)

    db.run(query.result)
      .map { results =>
        results
          .groupBy(_._1)
          .view
          .mapValues(_.map(_._2 match {
            case Some(p: WebhookEventType) => p.eventName
            case _ => null
          }))
          .toSeq
      }
      .toEitherT
  }

  def getWithSubscriptions(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Webhook, Seq[String])] = {

    val query = for {
      ((w, _), t) <- webhooksMapper joinLeft
        webhookEventSubscriptionsMapper on (_.id === _.webhookId) joinLeft
        webhookEventTypesMapper on (_._2.map(_.webhookEventTypeId) === _.id)

    } yield (w, t)

    for {
      // get the webhook with ID and associated subscriptions.,
      // throw 404 when no webhook with ID exists
      webhook <- db
        .run(query.result)
        .map { results =>
          results
            .filter(_._1.id === id)
            .groupBy(_._1)
            .view
            .mapValues(_.map(_._2 match {
              case Some(p: WebhookEventType) => p.eventName
              case _ => null
            }))
            .headOption
        }
        .whenNone[CoreError](NotFound(s"Webhook ($id)"))

      // Check if user has permission to see webhook
      // Throw 403 when user does not
      userId = actor.id

      _ <- checkOrErrorT[CoreError](
        !(webhook._1.createdBy != userId && webhook._1.isPrivate)
      )(
        InvalidAction(
          s"user ${userId} does not have access to webhook ${webhook._1.id}"
        )
      )

    } yield webhook
  }

  /**
    * Returns [[Webhook]] with the given id if this manager's actor is either superAdmin or
    * the [[Webhook]] creator or has organization permission >= the given permission.
    * Otherwise returns a [[PermissionError]].
    *
    * @param webhookId      id of the webhook to return
    * @param withPermission the minimum permission required to return the webhook
    * @param ec             execution context
    * @return a [[Webhook]] if permitted or a [[PermissionError]] if not
    */
  def getWithPermissionCheck(
    webhookId: Int,
    withPermission: DBPermission = DBPermission.Read
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Webhook] = {
    for {
      webhook <- db
        .run(webhooksMapper.getById(webhookId))
        .whenNone(NotFound(s"Webhook ($webhookId)"))
      organizationPermission <- db
        .run(
          OrganizationsMapper
            .getByNodeId(actor)(organization.nodeId)
            .result
            .headOption
        )
        .whenNone(NotFound(organization.nodeId))
      (_, userPermission) = organizationPermission
      _ <- FutureEitherHelpers.assert[CoreError](
        actor.isSuperAdmin || webhook.createdBy == actor.id || userPermission >= withPermission
      )(PermissionError(actor.nodeId, withPermission, s"Webhook ($webhookId)"))
    } yield webhook
  }

  def delete(
    webhook: Webhook
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] = {
    val query = webhooksMapper.filter(_.id === webhook.id).delete

    for {
      affectedRowCount <- db.run(query).toEitherT
    } yield affectedRowCount

  }

  /**
    * Returns [[Webhook]] with the given id if this manager's actor is either superAdmin or
    * the [[Webhook]] creator or if the webhook is public.
    * Otherwise returns a [[InvalidAction]].
    *
    * @param webhookId id of the webhook to return
    * @param ec        execution context
    * @return a [[Webhook]] if permitted or a [[InvalidAction]] if not
    */
  def getForIntegration(
    webhookId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Webhook] = {
    for {
      webhook <- db
        .run(webhooksMapper.getById(webhookId))
        .whenNone(NotFound(s"Webhook ($webhookId)"))
      _ <- FutureEitherHelpers.assert[CoreError](
        !webhook.isPrivate || actor.isSuperAdmin || webhook.createdBy == actor.id
      )(
        InvalidAction(
          s"user ${actor.nodeId} does not have dataset integration access to webhook ${webhook.id}"
        )
      )
    } yield webhook
  }
}
