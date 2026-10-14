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
import com.pennsieve.core.utilities.ContainerTypes.SnsTopic
import com.pennsieve.db._
import com.pennsieve.domain._
import com.pennsieve.models._
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.aws.sns.SNSClient
import com.typesafe.scalalogging.LazyLogging
import io.circe.syntax._
import software.amazon.awssdk.services.sns.model.PublishResponse

import scala.concurrent.{ ExecutionContext, Future }
import java.time.ZonedDateTime

/**
  * See docs/changelog-events.md Part 2 for the full design (pennsieve-api#405).
  *
  * Mirrors `ChangelogManager`'s DB-write + SNS-publish pair exactly, but for
  * `organization_events` -- a parallel capability, not an extension of the
  * dataset-scoped system. Deliberately does not port `ChangelogManager`'s
  * `getTimeline`/cursor machinery: there is no timeline UI requirement for
  * organization events in this design, only a flat event log (`getEvents`).
  *
  * Reuses the SAME SNS topic as `ChangelogManager` (`events_topic`,
  * `pennsieve.changelog.sns_topic`) -- organization events are a new
  * producer onto the existing topic, not a new topic. See the doc's
  * "Wire format" section.
  */
trait OrganizationEventManager extends LazyLogging {
  def db: Database
  def organization: Organization
  def snsTopic: SnsTopic
  def sns: SNSClient

  lazy val organizationEventMapper = new OrganizationEventMapper(organization)

  def logEventDB(
    actorUserId: Option[Int],
    subjectUserId: Option[Int],
    detail: OrganizationEventDetail,
    timestamp: ZonedDateTime
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, OrganizationEvent] =
    db.run(
        organizationEventMapper
          .logEvent(
            organization.id,
            actorUserId,
            subjectUserId,
            detail,
            timestamp
          )
          .transactionally
      )
      .toEitherT

  def logEventSNS(
    actorUserId: Option[Int],
    subjectUserId: Option[Int],
    detail: OrganizationEventDetail
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, PublishResponse] =
    sns.publish(
      snsTopic,
      formatMessageForSNS(actorUserId, subjectUserId, detail)
    )

  def logEvent(
    actorUserId: Option[Int],
    subjectUserId: Option[Int],
    detail: OrganizationEventDetail,
    timestamp: ZonedDateTime = ZonedDateTime.now()
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, OrganizationEvent] =
    for {
      event <- logEventDB(actorUserId, subjectUserId, detail, timestamp)
      _ <- logEventSNS(actorUserId, subjectUserId, detail)
    } yield event

  def eventCategory(detail: OrganizationEventDetail): String =
    detail.eventType.category match {
      case OrganizationEventCategory.ORGANIZATION => "ORGANIZATION"
      case OrganizationEventCategory.PUBLISHING => "PUBLISHING"
    }

  def formatMessageForSNS(
    actorUserId: Option[Int],
    subjectUserId: Option[Int],
    detail: OrganizationEventDetail
  ): String =
    f"""{
     |    "organizationId": "${organization.id}",
     |    "actorUserId": ${actorUserId
         .map(id => s""""$id"""")
         .getOrElse("null")},
     |    "subjectUserId": ${subjectUserId
         .map(id => s""""$id"""")
         .getOrElse("null")},
     |    "eventCategory": "${eventCategory(detail)}",
     |    "eventType": "${detail.eventType.toString}",
     |    "eventDetail": ${detail.asJson.noSpaces}
     |}""".stripMargin.replaceAll("\n", "").replaceAll(" +", " ")

  def getEvents(
    limit: Int = 25,
    offset: Int = 0
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[OrganizationEvent]] =
    db.run(
        organizationEventMapper
          .getEvents(organization.id)
          .sortBy(_.createdAt.desc)
          .drop(offset)
          .take(limit)
          .result
      )
      .toEitherT
      .map(_.toList)
}

class OrganizationEventManagerImpl(
  val db: Database,
  val organization: Organization,
  val snsTopic: SnsTopic,
  val sns: SNSClient
) extends OrganizationEventManager
