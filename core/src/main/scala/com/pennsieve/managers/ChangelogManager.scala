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
import com.pennsieve.core.utilities.FutureEitherHelpers
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.core.utilities.ContainerTypes.SnsTopic
import com.pennsieve.db._
import com.pennsieve.domain._
import com.pennsieve.models._
import com.pennsieve.core.utilities.checkOrErrorT
import com.pennsieve.traits.PostgresProfile.api._
import com.github.tminglei.slickpg.utils.PlainSQLUtils
import com.pennsieve.aws.sns.{ SNS, SNSClient }
import com.typesafe.scalalogging.LazyLogging
import io.circe._
import io.circe.parser.decode
import slick.jdbc.{ GetResult, PositionedParameters, SetParameter }
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.model.{
  PublishRequest,
  PublishResponse
}

import scala.concurrent.{ ExecutionContext, Future }
import java.time.{ LocalDate, ZonedDateTime }

/**
  * Users interact with the dataset changelog via a "timeline" view. Events in
  * the timeline are grouped by event type and time period. Recent events are
  * grouped by day, older events by week or month.
  *
  * Users only see event groups in the high level timeline, but can expand the
  * event group to see all events. These events are selected via API cursors
  * returned for each event group.
  *
  * Event groups with a single event are handled differently: the full event is
  * returned alongside the event group.
  *
  * https://blackfynn.atlassian.net/wiki/spaces/PM/pages/1861976109/Dataset+Changelog
  */
class ChangelogManager(
  val db: Database,
  val organization: Organization,
  val actor: User,
  val snsTopic: SnsTopic,
  val sns: SNSClient
) extends LazyLogging {

  lazy val changelogEventMapper = new ChangelogEventMapper(organization)

  lazy val changelogEventTypeMapper = new ChangelogEventTypeMapper(organization)

  def logEventDB(
    dataset: Dataset,
    detail: ChangelogEventDetail,
    timestamp: ZonedDateTime
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, ChangelogEventAndType] =
    db.run(
        changelogEventMapper
          .logEvent(dataset, detail, actor, timestamp)
          .transactionally
      )
      .toEitherT
      .subflatMap(ChangelogEventAndType.from(_).leftMap(ParseError(_)))

  def logEventSNS(
    dataset: Dataset,
    detail: ChangelogEventDetail,
    timestamp: ZonedDateTime
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, PublishResponse] = {
    sns.publish(snsTopic, detail.toString)
  }

  def logEvent(
    dataset: Dataset,
    detail: ChangelogEventDetail,
    timestamp: ZonedDateTime = ZonedDateTime.now()
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, ChangelogEventAndType] = {

    for {
      changelogEventAndType <- logEventDB(
        dataset: Dataset,
        detail: ChangelogEventDetail,
        timestamp: ZonedDateTime
      )
      _ <- logEventSNS(
        dataset: Dataset,
        detail: ChangelogEventDetail,
        timestamp: ZonedDateTime
      )
    } yield changelogEventAndType

  }

  def logEvents(
    dataset: Dataset,
    events: List[(ChangelogEventDetail, Option[ZonedDateTime])]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[ChangelogEventAndType]] =
    db.run(
        DBIO
          .sequence(events.map {
            case (eventDetail, maybeTimestamp) =>
              changelogEventMapper
                .logEvent(
                  dataset,
                  eventDetail,
                  actor,
                  maybeTimestamp.getOrElse(ZonedDateTime.now())
                )
          })
          .transactionally
      )
      .toEitherT
      .subflatMap(
        _.traverse(ChangelogEventAndType.from(_).leftMap(ParseError(_)))
      )

  def getEvents(
    dataset: Dataset,
    limit: Int = 25,
    cursor: ChangelogEventCursor
  )(implicit
    ec: ExecutionContext
  ): EitherT[
    Future,
    CoreError,
    (List[ChangelogEventAndType], Option[ChangelogEventCursor])
  ] =
    db.run(
        changelogEventMapper
          .filter(_.datasetId === dataset.id)
          .filterOpt(cursor.start)(_.createdAt >= _)
          .filterOpt(cursor.end) {
            case (events, (endTime, nextId)) =>
              // When multiple events have the same timestamp we need to include
              // the event ID for consistent ordering.  The naive approach would
              // be:
              //
              //   (events.createdAt <= end && events.id <= id)
              //
              // which breaks if, for any two events A and B, A.createdAt <=
              // B.createdAt and A.id > B.id. Hence the slightly convoluted
              // logic of only comparing by ID for timestamps exactly matching
              // this target end timestamp: it allows event IDs to not be
              // strictly chronological.
              (events.createdAt === endTime && events.id <= nextId) || (events.createdAt < endTime)
          }
          .filter(
            // Using an explicit sub-select here nudges Postgres to walk the
            // (dataset_id, event_type_id, created_at, id) index for this result.
            // No sorting required.
            _.eventTypeId === changelogEventTypeMapper
              .filter(_.name === cursor.eventType)
              .map(_.id)
              .take(1)
              .max // HACK: force Seq[Int] to Int
          )
          .filterOpt(cursor.userId)(_.userId === _)
          .join(changelogEventTypeMapper)
          .on(_.eventTypeId === _.id)
          .sortBy {
            case (e, _) =>
              (e.datasetId, e.eventTypeId, e.createdAt.desc, e.id.desc)
          }
          .take(limit + 1)
          .result
      )
      .toEitherT
      .subflatMap(
        _.toList.traverse(ChangelogEventAndType.from(_).leftMap(ParseError(_)))
      )
      .map(
        events =>
          (
            events.take(limit),
            events
              .drop(limit)
              .headOption
              .map(
                nextEvent =>
                  ChangelogEventCursor(
                    eventType = cursor.eventType,
                    start = cursor.start,
                    end = Some((nextEvent.createdAt, nextEvent.id)),
                    userId = cursor.userId
                  )
              )
          )
      )

  /**
    * In addition to each event group, either return a cursor to all events in
    * the group, or a raw event when the group only contains a single event.
    * Required so the frontend can easily display details without making another
    * request to load event details.
    */
  def getTimeline(
    dataset: Dataset,
    limit: Int = 25,
    cursor: Option[ChangelogEventGroupCursor] = None,
    category: Option[ChangelogEventCategory] = None,
    startDate: Option[LocalDate] = None,
    userId: Option[Int] = None,
    now: ZonedDateTime = ZonedDateTime.now() // needed for testing
  )(implicit
    ec: ExecutionContext
  ): EitherT[
    Future,
    CoreError,
    (
      List[
        (
          ChangelogEventGroup,
          Either[ChangelogEventAndType, ChangelogEventCursor]
        )
      ],
      Option[ChangelogEventGroupCursor]
    )
  ] = {

    val minDate: LocalDate = startDate.getOrElse(LocalDate.of(2000, 1, 1))
    val eventTypes: Option[List[String]] =
      category.map(_.eventTypes.map(_.entryName))

    val query = for {

      // Find the max event ID to consider. This is needed so that subsequent
      // calls with a cursor only consider events that exist at the moment of
      // the first request. If a cursor already specifies a value, use that.
      //
      // If events are added while someone is viewing the timeline, the
      // maxEventId prevents new events from "pulling" old events up into a
      // new event group.
      maxEventId <- cursor.map(_.maxEventId) match {
        case Some(maxEventId) => DBIO.successful(maxEventId)
        case None =>
          changelogEventMapper.map(_.id).max.result.map(_.getOrElse(0))
      }
      timeline <- (sql"""

        /**
         * CTE of all events grouped by day. Doing this pre-grouping reduces the
         * total number of rows that need to be sorted with the more complex
         * group condition below.
         */
        WITH events_by_day AS (
          SELECT
            dataset_id,
            created_at::date AS day,
            user_id,
            event_type_id,
            COUNT(*) as total_count
          FROM "#${organization.schemaId}".changelog_events
          WHERE dataset_id = ${dataset.id}
          AND created_at::date >= $minDate
          AND id <= $maxEventId
          GROUP BY dataset_id, day, event_type_id, user_id
        )

        /*
         * Now that we have all events by day, group them so that events in the
         * last week are grouped by day and older events are grouped by week. As
         * we merge together events by day we also aggregate events with
         * different users.
         */
        SELECT
          dataset_id,
          user_ids,
          event_types.id,
          event_types.name,
          total_count,
          time_bucket,
          time_period,
          first.created_at,
          last.created_at,
          last.detail,
          last.id

        FROM (
          SELECT
            dataset_id,
            array_agg(distinct user_id) AS user_ids,
            event_type_id,
            SUM(total_count) AS total_count,

            CASE
              -- Group events in the last week by day
              WHEN day >= $now::date - 7
              THEN date_trunc('day', day)::date

              -- Otherwise by week. BUT prevent event groups from crossing between
              -- months: Group date by beginning of month if, when truncated by week,
              -- this date would be in the previous month
              WHEN date_trunc('week', day )::date - date_trunc('month', day)::date <= 0
              THEN date_trunc('month', day)::date

              -- Otherwise by week
              ELSE date_trunc('week', day)::date
            END AS time_bucket,

            CASE
              WHEN day >= $now::date - 7 THEN 'DAY'
              ELSE 'WEEK'
            END AS time_period,
            MIN(day) as first_day,
            MAX(day) as last_day

          FROM events_by_day
          WHERE true
          """
        .opt(userId)(id => sql"AND user_id = $id")
        .opt(eventTypes)(e => sql"""
          AND event_type_id IN (
            SELECT id FROM "#${organization.schemaId}".changelog_event_types
            WHERE name = any($e)
          )
          """) ++ sql"""

          GROUP BY dataset_id, time_period, time_bucket, event_type_id

        ) event_groups

        /*
         * Finally, join additional details for the first and last events in
         * each event group.
         *
         * These joins are very efficient because they use the (dataset_id,
         * event_type_id, created_at, id) index.
         */
        CROSS JOIN LATERAL (
          SELECT
            created_at
          FROM "#${organization.schemaId}".changelog_events
          WHERE dataset_id = event_groups.dataset_id
          AND event_type_id = event_groups.event_type_id
          AND created_at >= event_groups.first_day
          AND user_id = any(event_groups.user_ids)
          AND id <= $maxEventId
          ORDER BY created_at ASC, id ASC
          LIMIT 1
        ) first

        CROSS JOIN LATERAL (
          SELECT
            detail,
            id,
            created_at
          FROM "#${organization.schemaId}".changelog_events
          WHERE dataset_id = event_groups.dataset_id
          AND event_type_id = event_groups.event_type_id
          AND created_at < event_groups.last_day + 1
          AND user_id = any(event_groups.user_ids)
          AND id <= $maxEventId
          ORDER BY created_at DESC, id DESC
          LIMIT 1
        ) last

        JOIN "#${organization.schemaId}".changelog_event_types event_types
        ON event_groups.event_type_id = event_types.id

        /*
         * Jump to next result in the cursor. Note: this query could be made
         * more efficient by pushing these conditions into the events_by_day CTE
         * to further limit the result set. In practice this is not necessary
         * because we still need to group by the remaining events.
         */
        """.opt(cursor)(c => sql"""
        WHERE
          (time_bucket = ${c.timeBucket} AND last.created_at = ${c.endTime} AND event_types.name >= ${c.eventType}) OR
          (time_bucket = ${c.timeBucket} AND last.created_at < ${c.endTime}) OR
          (time_bucket < ${c.timeBucket})
        """) ++ sql"""
        ORDER BY time_bucket DESC, last.created_at DESC, event_types.name ASC
        LIMIT ${limit + 1}
        """)
        .as[(ChangelogEventGroup, ChangelogEventAndType)]
        .map(_.map {
          case (eventGroup, event) =>
            (
              eventGroup,
              if (eventGroup.totalCount == 1) Left(event)
              else Right(ChangelogEventCursor(eventGroup, event, userId))
            )
        })
    } yield (timeline.toList, maxEventId: Int)

    db.run(query)
      .map {
        case (timeline, maxEventId) =>
          (
            timeline.take(limit),
            // Construct a cursor if there are any more events
            timeline
              .drop(limit)
              .headOption
              .map {
                case (eventGroup, _) =>
                  ChangelogEventGroupCursor(
                    eventType = eventGroup.eventType,
                    timeBucket = eventGroup.timeBucket,
                    endTime = eventGroup.timeRange.end,
                    maxEventId = maxEventId
                  )
              }
          )
      }
      .toEitherT
  }

  implicit val getEventGroupResult
    : GetResult[(ChangelogEventGroup, ChangelogEventAndType)] = {
    GetResult { p =>
      val datasetId = p.<<[Int]
      val userIds = p.<<[Seq[Int]].toList
      val eventTypeId = p.<<[Int]
      val eventTypeName = p.<<[ChangelogEventName]
      val totalCount = p.<<[Long]
      val timeBucket = p.<<[ZonedDateTime].toLocalDate
      val period = p.<<[EventGroupPeriod]
      val timeRange = ChangelogEventGroup
        .TimeRange(p.<<[ZonedDateTime], p.<<[ZonedDateTime])

      // Only return a nested ChangelogEvent if the event group contains a
      // single event.  Need to pull additional rows from the result set to
      // populate full details.
      val event = {
        val detail = p
          .<<[Json]
          .as(ChangelogEventDetail.decoder(eventTypeName))
          .fold(throw _, detail => detail)
        val id = p.<<[Int]

        ChangelogEventAndType(
          datasetId = datasetId,
          userId = userIds.head,
          eventType = eventTypeName,
          detail = detail,
          createdAt = timeRange.start,
          id = id
        )
      }

      val eventGroup = ChangelogEventGroup(
        datasetId,
        userIds,
        eventTypeName,
        totalCount,
        timeBucket,
        period,
        timeRange
      )

      (eventGroup, event)
    }
  }

  implicit val setChangelogEventNameParameter
    : SetParameter[ChangelogEventName] =
    new SetParameter[ChangelogEventName] {
      def apply(e: ChangelogEventName, pp: PositionedParameters) =
        pp.setString(e.entryName)
    }

  implicit val getChangelogEventNameResult: GetResult[ChangelogEventName] =
    GetResult { p =>
      ChangelogEventName.withName(p.<<[String])
    }

  implicit val getEventGroupPeriodResult: GetResult[EventGroupPeriod] =
    GetResult { p =>
      EventGroupPeriod.withName(p.<<[String])
    }
}
