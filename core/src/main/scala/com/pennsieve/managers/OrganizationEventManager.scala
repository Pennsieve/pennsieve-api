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
import io.circe.Json
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
          .logEvent(actorUserId, subjectUserId, detail, timestamp)
      )
      .toEitherT

  def logEventSNS(
    event: OrganizationEvent
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, PublishResponse] =
    sns.publish(snsTopic, formatMessageForSNS(event))

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
      _ <- logEventSNS(event)
    } yield event

  /**
    * Best-effort wrapper around [[logEvent]]: organization-event logging is
    * a side signal, not the action it accompanies, so a DB or SNS failure
    * here must never fail the caller's (already-committed) request. Logs
    * the failure and resolves to `Unit` either way. Every call site should
    * use this rather than `logEvent` directly.
    *
    * Deliberate design choice, not an oversight: [[logEvent]]'s DB-then-SNS
    * sequencing means a DB failure here (e.g. a missing/un-migrated table)
    * silently drops the SNS publish too, with only this logged error as a
    * trace. That's correct for this system's priorities -- the real
    * operation these events describe (a publish, a membership change) must
    * succeed or fail entirely on its own, never on whether a signal about
    * it could also be sent. These events are not a dependency the real
    * operation's correctness rests on, so a lost signal is not grounds to couple
    * the real operation's outcome to this method's success, nor to
    * decouple the SNS publish from the DB write to work around it.
    *
    * That said, a lost signal must not go unnoticed: this method's
    * `logger.error` call is required to be wired to an alert/metric before
    * this ships, not optional ops hygiene -- see docs/changelog-events.md's
    * "Durability/atomicity of event logging" note.
    */
  def logEventBestEffort(
    actorUserId: Option[Int],
    subjectUserId: Option[Int],
    detail: OrganizationEventDetail,
    timestamp: ZonedDateTime = ZonedDateTime.now()
  )(implicit
    ec: ExecutionContext
  ): Future[Unit] =
    logEvent(actorUserId, subjectUserId, detail, timestamp).value
      .recover {
        case throwable =>
          Left(ThrowableError(throwable))
      }
      .map {
        case Left(error) =>
          logger
            .error(
              s"failed to log organization event (organizationId=${organization.id}, eventType=${detail.eventType}): $error"
            )
        case Right(_) => ()
      }

  def eventCategory(eventType: OrganizationEventName): String =
    eventType.category match {
      case OrganizationEventCategory.ORGANIZATION => "ORGANIZATION"
      case OrganizationEventCategory.PUBLISHING => "PUBLISHING"
    }

  /**
    * Wire format for the shared `pennsieve.changelog.sns_topic` SNS topic.
    * Built as a circe `Json` object (not a hand-assembled string) so free-text
    * fields inside `eventDetail` (dataset/team names, etc.) are JSON-escaped
    * correctly rather than run through a blanket `replaceAll` that would
    * corrupt them -- see docs/changelog-events.md's "Wire format" section.
    *
    * `id`/`createdAt` identify one logged event, i.e. one `organization_events`
    * row. `(organizationId, id)` therefore de-duplicates *transport-level*
    * duplicates only: the same message delivered more than once by SNS/SQS.
    *
    * It does NOT de-duplicate a re-run of the producing operation. If the
    * publish-complete callback is redelivered, `publishCompleteBody` runs
    * again and logs again: that inserts a NEW row, so the second broadcast
    * has a different `id`. For the three callback-driven Part 4 events
    * (`DATASET_PUBLISHED`, `DATASET_EMBARGOED`, `DATASET_EMBARGO_RELEASED`),
    * consumers that need to collapse those should key on
    * `(organizationId, eventType, datasetId, publishedVersion)`, all of which
    * are in `eventDetail` and are identical across redeliveries of the same
    * callback (`datasetId` is the workspace dataset's id, not Discover's
    * `publishedDatasetId`). `DATASET_REVISED` has no such key (repeat revisions of one
    * version are legitimate) but is emitted from a synchronous request, not a
    * redelivered callback. See docs/changelog-events.md open question 11.
    */
  def formatMessageForSNS(event: OrganizationEvent): String =
    Json
      .obj(
        "organizationId" -> organization.id.toString.asJson,
        "id" -> event.id.toString.asJson,
        "createdAt" -> event.createdAt.toString.asJson,
        "actorUserId" -> event.actorUserId.map(_.toString).asJson,
        "subjectUserId" -> event.subjectUserId.map(_.toString).asJson,
        "eventCategory" -> eventCategory(event.eventType).asJson,
        "eventType" -> event.eventType.toString.asJson,
        "eventDetail" -> event.detail
      )
      .noSpaces

  def getEvents(
    limit: Int = 25,
    offset: Int = 0
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[OrganizationEvent]] =
    db.run(
        organizationEventMapper
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
