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

package com.pennsieve.db

import cats._

import cats.implicits._

import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.models.{ ModelProperty, _ }
import slick.lifted.MappedToBase.mappedToIsomorphism

import com.pennsieve.models.WebhookTarget

import java.time.ZonedDateTime
import scala.concurrent.{ ExecutionContext, Future }

final class WebhooksTable(schema: String, tag: Tag)
    extends Table[Webhook](tag, Some(schema), "webhooks") {

  // set by the database
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)

  def apiUrl = column[String]("api_url")

  def imageUrl = column[Option[String]]("image_url")

  def description = column[String]("description")

  def secret = column[String]("secret")

  def name = column[String]("name")

  def displayName = column[String]("display_name")

  def isPrivate = column[Boolean]("is_private")

  def isDefault = column[Boolean]("is_default")

  def isDisabled = column[Boolean]("is_disabled")

  def hasAccess = column[Boolean]("has_access")

  def integrationUserId = column[Int]("integration_user_id")

  def customTargets =
    column[Option[List[WebhookTarget]]]("targets")

  def createdBy = column[Int]("created_by")

  def createdAt =
    column[ZonedDateTime]("created_at", O.AutoInc) // set by the database on insert

  def * =
    (
      apiUrl,
      imageUrl,
      description,
      secret,
      name,
      displayName,
      isPrivate,
      isDefault,
      isDisabled,
      hasAccess,
      integrationUserId,
      customTargets,
      createdBy,
      createdAt,
      id
    ).mapTo[Webhook]
}

class WebhooksMapper(val organization: Organization)
    extends TableQuery(new WebhooksTable(organization.schemaId, _)) {
  def getById(id: Int): DBIO[Option[Webhook]] =
    this.filter(_.id === id).result.headOption

  def get(id: Int): Query[WebhooksTable, Webhook, Seq] =
    this.filter(_.id === id)

  def find(user: User): Query[WebhooksTable, Webhook, Seq] =
    this.filter(x => (x.isPrivate === false || x.createdBy === user.id))

  def getDefaults(
    user: User,
    includedIds: Option[Set[Int]] = None,
    excludedIds: Option[Set[Int]] = None
  ): Query[WebhooksTable, Webhook, Seq] = {
    this
      .filter(
        wh =>
          wh.isDefault
            || (wh.id inSetBind includedIds
              .getOrElse(Set.empty))
      )
      .filterNot(_.id inSetBind excludedIds.getOrElse(Set.empty))
      .filter(
        wh => !wh.isPrivate || user.isSuperAdmin || wh.createdBy === user.id
      )
  }
}
