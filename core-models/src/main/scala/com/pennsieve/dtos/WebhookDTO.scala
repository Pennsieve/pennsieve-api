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

package com.pennsieve.dtos

import com.pennsieve.models.{
  Token,
  TokenSecret,
  Webhook,
  WebhookEventSubcription
}
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.{ Decoder, Encoder }

import java.time.ZonedDateTime

final case class WebhookDTO(
  id: Int,
  apiUrl: String,
  imageUrl: Option[String],
  description: String,
  name: String,
  displayName: String,
  isPrivate: Boolean,
  isDefault: Boolean,
  isDisabled: Boolean,
  hasAccess: Boolean,
  eventTargets: Option[Seq[String]],
  tokenSecret: Option[APITokenSecretDTO],
  customTargets: Option[List[WebhookTargetDTO]],
  createdBy: Int,
  createdAt: ZonedDateTime
)

object WebhookDTO {

  implicit val encoder: Encoder[WebhookDTO] = deriveEncoder[WebhookDTO]
  implicit val decoder: Decoder[WebhookDTO] = deriveDecoder[WebhookDTO]

  def apply(webhook: Webhook): WebhookDTO = {

    WebhookDTO(
      id = webhook.id,
      apiUrl = webhook.apiUrl,
      imageUrl = webhook.imageUrl,
      description = webhook.description,
      name = webhook.name,
      displayName = webhook.displayName,
      isPrivate = webhook.isPrivate,
      isDefault = webhook.isDefault,
      isDisabled = webhook.isDisabled,
      hasAccess = webhook.hasAccess,
      eventTargets = None,
      tokenSecret = None,
      customTargets = webhook.customTargets,
      createdBy = webhook.createdBy,
      createdAt = webhook.createdAt
    )
  }

  def apply(webhook: Webhook, target: Seq[String]): WebhookDTO = {
//    val targetsStr: Option[Seq[Int]] = Some(target.map(x => x.id))

    WebhookDTO(
      id = webhook.id,
      apiUrl = webhook.apiUrl,
      imageUrl = webhook.imageUrl,
      description = webhook.description,
      name = webhook.name,
      displayName = webhook.displayName,
      isPrivate = webhook.isPrivate,
      isDefault = webhook.isDefault,
      isDisabled = webhook.isDisabled,
      hasAccess = webhook.hasAccess,
      eventTargets = Option(target).filter(_.nonEmpty),
      tokenSecret = None,
      customTargets = webhook.customTargets,
      createdBy = webhook.createdBy,
      createdAt = webhook.createdAt
    )
  }

  def apply(
    webhook: Webhook,
    target: Seq[String],
    tokenSecretDTO: Option[APITokenSecretDTO]
  ): WebhookDTO = {
    //    val targetsStr: Option[Seq[Int]] = Some(target.map(x => x.id))

    tokenSecretDTO match {
      case Some(tokenSecretDTO) =>
        WebhookDTO(
          id = webhook.id,
          apiUrl = webhook.apiUrl,
          imageUrl = webhook.imageUrl,
          description = webhook.description,
          name = webhook.name,
          displayName = webhook.displayName,
          isPrivate = webhook.isPrivate,
          isDefault = webhook.isDefault,
          isDisabled = webhook.isDisabled,
          hasAccess = webhook.hasAccess,
          eventTargets = Option(target).filter(_.nonEmpty),
          customTargets = webhook.customTargets,
          tokenSecret = Some(tokenSecretDTO),
          createdBy = webhook.createdBy,
          createdAt = webhook.createdAt
        )
      case _ =>
        WebhookDTO(
          id = webhook.id,
          apiUrl = webhook.apiUrl,
          imageUrl = webhook.imageUrl,
          description = webhook.description,
          name = webhook.name,
          displayName = webhook.displayName,
          isPrivate = webhook.isPrivate,
          isDefault = webhook.isDefault,
          isDisabled = webhook.isDisabled,
          hasAccess = webhook.hasAccess,
          eventTargets = Option(target).filter(_.nonEmpty),
          customTargets = webhook.customTargets,
          tokenSecret = None,
          createdBy = webhook.createdBy,
          createdAt = webhook.createdAt
        )
    }

  }
}
