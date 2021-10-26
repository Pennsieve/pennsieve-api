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
import com.pennsieve.models._
import com.pennsieve.traits.PostgresProfile
import com.pennsieve.traits.PostgresProfile.api._

import scala.concurrent.{ ExecutionContext, Future }

class WebhookManager(
  val db: PostgresProfile.api.Database,
  val actor: User,
  val webhooksMapper: WebhooksMapper,
  val webhookEventSubscriptionsMapper: WebhookEventSubscriptionsMapper,
  val webhookEventTypesMapper: WebhookEventTypesMapper
) {

  val organization: Organization = webhooksMapper.organization

  def validate(
    apiUrl: String,
    imageUrl: Option[String],
    description: String,
    secret: String,
    displayName: String,
    isPrivate: Boolean,
    isDefault: Boolean,
    isDisabled: Boolean
  ): Either[PredicateError, Webhook] = {

    val validated = validateTuple(
      apiUrl,
      imageUrl,
      description,
      secret,
      displayName,
      isPrivate,
      isDefault,
      isDisabled
    )

    validated.map {
      case (
          apiUrl,
          imageUrl,
          description,
          secret,
          name,
          displayName,
          isPrivate,
          isDefault,
          isDisabled
          ) =>
        Webhook(
          apiUrl = apiUrl,
          imageUrl = imageUrl,
          description = description,
          secret = secret,
          name = name,
          displayName = displayName,
          isPrivate = isPrivate,
          isDefault = isDefault,
          isDisabled = isDisabled,
          createdBy = actor.id
        )
    }
  }

  def validateTuple(
    apiUrl: String,
    imageUrl: Option[String],
    description: String,
    secret: String,
    displayName: String,
    isPrivate: Boolean,
    isDefault: Boolean,
    isDisabled: Boolean
  ): Either[
    PredicateError,
    (
      String,
      Option[String],
      String,
      String,
      String,
      String,
      Boolean,
      Boolean,
      Boolean
    )
  ] = {
    val trimmedImageUrl = trimOptional(imageUrl)

    for {
      _ <- checkOrError(apiUrl.trim.length < 256 && apiUrl.trim.length > 0)(
        PredicateError("api url must be between 1 and 255 characters")
      )

      _ <- checkOrError(
        trimmedImageUrl.isEmpty || trimmedImageUrl.get.length < 256
      )(
        PredicateError("image url must be less than or equal to 255 characters")
      )

      _ <- checkOrError(
        description.trim.length < 200 && description.trim.length > 0
      )(PredicateError("description must be between 1 and 199 characters"))

      _ <- checkOrError(secret.trim.length < 256 && secret.trim.length > 0)(
        PredicateError("secret must be between 1 and 255 characters")
      )

      _ <- checkOrError(
        displayName.trim.length < 256 && displayName.trim.length > 0
      )(PredicateError("display name must be between 1 and 255 characters"))

      row = (
        apiUrl.trim,
        trimmedImageUrl,
        description.trim,
        secret.trim,
        slugify(displayName),
        displayName.trim,
        isPrivate,
        isDefault,
        isDisabled
      )
    } yield row
  }

  def validateWebhook(webhook: Webhook): Either[PredicateError, Webhook] = {
    val validated = validateTuple(
      webhook.apiUrl,
      webhook.imageUrl,
      webhook.description,
      webhook.secret,
      webhook.displayName,
      webhook.isPrivate,
      webhook.isDefault,
      webhook.isDisabled
    )

    validated.map {
      case (
          apiUrl,
          imageUrl,
          description,
          secret,
          name,
          displayName,
          isPrivate,
          isDefault,
          isDisabled
          ) =>
        webhook.copy(
          apiUrl = apiUrl,
          imageUrl = imageUrl,
          description = description,
          secret = secret,
          name = name,
          displayName = displayName,
          isPrivate = isPrivate,
          isDefault = isDefault,
          isDisabled = isDisabled
        )
    }
  }

  def create(
    apiUrl: String,
    imageUrl: Option[String],
    description: String,
    secret: String,
    displayName: String,
    isPrivate: Boolean,
    isDefault: Boolean,
    targetEvents: Option[List[String]]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Webhook, Seq[String])] = {

    for {

      row <- (validate(
        apiUrl,
        imageUrl,
        description,
        secret,
        displayName,
        isPrivate,
        isDefault,
        isDisabled = false
      ) match {
        case Left(error) => Future.failed(error)
        case Right(webhook) => Future.successful(webhook)
      }).toEitherT

      /* Creating a SQL Action Sequence that inserts row for webhook and
      a row for each event that it is subscribed to.
       */

      insertQuery: DBIO[Webhook] = for {

        allTypes <- webhookEventTypesMapper.result

        _ <- assert(
          targetEvents match {
            case Some(targetEvents) =>
              targetEvents.forall(allTypes.map {
                _.eventName
              }.contains)
            case None => {
              true
            }
          }
        )(PredicateError(s"Target events must be one of ${allTypes.toString}."))

        // insert the row in the webhook table
        webhookId: Int <- insertWebhook(row)

        // insert one row per subscription in the event subscription table
        _ <- insertSubscriptions(
          for {
            target <- targetEvents.getOrElse(List.empty)

          } yield
            WebhookEventSubcription(
              webhookId,
              allTypes.filter(_.eventName == target).head.id
            )
        )

        // return created webhook with populated Id
        createdWebhook: Webhook <- webhooksMapper
          .filter(_.id === webhookId)
          .result
          .head

      } yield createdWebhook

      // Run the SQL action sequence and return the webhook
      webhook <- db.run(insertQuery.transactionally).toEitherT
    } yield (webhook, targetEvents.getOrElse(List.empty))
  }

  def insertWebhook(row: Webhook): DBIO[Int] =
    webhooksMapper returning webhooksMapper.map(_.id) += row

  def insertSubscriptions(
    rows: Seq[WebhookEventSubcription]
  ): DBIO[Option[Int]] = webhookEventSubscriptionsMapper ++= rows

  def getEventNames(webhookId: Int): Query[Rep[String], String, Seq] = {
    for {
      (sub, event) <- webhookEventSubscriptionsMapper join webhookEventTypesMapper on (_.webhookEventTypeId === _.id)
      if (sub.webhookId === webhookId)
    } yield event.eventName
  }

  def update(
    webhook: Webhook,
    targetEvents: Option[List[String]] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Webhook, Seq[String])] = {

    val validated = validateWebhook(webhook)

    val updateWebhookAction = validated match {
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
      eventNames <- getEventNames(webhook.id).result
    } yield (updatedWebhook, eventNames)

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
