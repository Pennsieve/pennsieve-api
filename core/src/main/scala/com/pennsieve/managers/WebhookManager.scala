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
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.core.utilities.{ checkOrErrorT, slugify }
import com.pennsieve.db._
import com.pennsieve.domain._
import com.pennsieve.models._
import com.pennsieve.traits.PostgresProfile.api._

import scala.concurrent.{ ExecutionContext, Future }

class WebhookManager(
  val db: Database,
  val actor: User,
  val webhooksMapper: WebhooksMapper
) {

  val organization: Organization = webhooksMapper.organization

  def create(
    apiUrl: String,
    imageUrl: Option[String],
    description: String,
    secret: String,
    displayName: String,
    isPrivate: Boolean,
    isDefault: Boolean,
    createdBy: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Webhook] = {
    for {
      _ <- checkOrErrorT(apiUrl.trim.length < 256 && apiUrl.trim.length > 0)(
        PredicateError("api url must be between 1 and 255 characters")
      )

      trimmedImageUrl = imageUrl match {
        case None => None
        case Some(url) if url.trim.isEmpty => None
        case Some(url) =>
          checkOrErrorT(url.trim.length < 256)(
            PredicateError(
              "image url must be less than or equal to 255 characters"
            )
          )
          Some(url.trim)
      }

      _ <- checkOrErrorT(
        description.trim.length < 200 && description.trim.length > 0
      )(PredicateError("description must be between 1 and 200 characters"))

      _ <- checkOrErrorT(secret.trim.length < 256 && secret.trim.length > 0)(
        PredicateError("secret must be between 1 and 255 characters")
      )

      _ <- checkOrErrorT(
        displayName.trim.length < 256 && displayName.trim.length > 0
      )(PredicateError("display name must be between 1 and 255 characters"))

      row = Webhook(
        apiUrl.trim,
        trimmedImageUrl,
        description.trim,
        secret.trim,
        slugify(displayName),
        displayName.trim,
        isPrivate,
        isDefault,
        true,
        Some(createdBy)
      )

      createdWebhook = (webhooksMapper returning webhooksMapper) += row

      webhook <- db.run(createdWebhook.transactionally).toEitherT
    } yield webhook
  }

  def get(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Webhook] =
    db.run(webhooksMapper.get(id).result.headOption)
      .whenNone(NotFound(s"Webhook ($id)"))
}
