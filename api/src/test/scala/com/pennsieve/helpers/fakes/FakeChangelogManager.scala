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
import com.pennsieve.aws.sns.{ MockSNS, SNSClient }
import com.pennsieve.core.utilities.ContainerTypes.SnsTopic
import com.pennsieve.domain.CoreError
import com.pennsieve.managers.ChangelogManager
import com.pennsieve.models.{
  ChangelogEventAndType,
  ChangelogEventCategory,
  ChangelogEventCursor,
  ChangelogEventDetail,
  ChangelogEventGroup,
  ChangelogEventGroupCursor,
  ChangelogEventName,
  Dataset,
  EventGroupPeriod,
  Organization,
  User
}
import com.pennsieve.traits.PostgresProfile.api.Database

import java.time.{ LocalDate, ZonedDateTime }
import scala.concurrent.{ ExecutionContext, Future }

class FakeChangelogManager(
  val state: InMemoryState,
  override val organization: Organization,
  val actor: User,
  val snsTopic: SnsTopic = "test-topic",
  val sns: SNSClient = new MockSNS
) extends ChangelogManager {

  def db: Database =
    sys.error(
      "FakeChangelogManager: a method not yet stubbed by your test tried to " +
        "use the database. Override the method on this fake."
    )

  override def logEvent(
    dataset: Dataset,
    detail: ChangelogEventDetail,
    timestamp: ZonedDateTime = ZonedDateTime.now()
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, ChangelogEventAndType] = {
    val id = state.newId()
    val event = ChangelogEventAndType(
      datasetId = dataset.id,
      userId = actor.id,
      eventType = detail.eventType,
      detail = detail,
      createdAt = timestamp,
      id = id
    )
    state.changelogEvents.put((organization.id, id), event)
    EitherT.rightT(event)
  }

  override def logEvents(
    dataset: Dataset,
    events: List[(ChangelogEventDetail, Option[ZonedDateTime])]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[ChangelogEventAndType]] = {
    import cats.implicits._
    events.traverse {
      case (d, ts) => logEvent(dataset, d, ts.getOrElse(ZonedDateTime.now()))
    }
  }

  /** Group events by (eventType, day) for last-week events; (eventType, week)
    * for older. Returns groups newest-first with optional cursor. */
  override def getTimeline(
    dataset: Dataset,
    limit: Int = 25,
    cursor: Option[ChangelogEventGroupCursor] = None,
    category: Option[ChangelogEventCategory] = None,
    startDate: Option[LocalDate] = None,
    userId: Option[Int] = None,
    now: ZonedDateTime = ZonedDateTime.now()
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
    val allEvents = state.changelogEvents.values
      .filter(_.datasetId == dataset.id)
      .toList
      .filter(e => category.forall(_.eventTypes.contains(e.eventType)))
      .filter(e => userId.forall(_ == e.userId))
      .filter(e => startDate.forall(d => !e.createdAt.toLocalDate.isBefore(d)))
    val maxId = cursor
      .map(_.maxEventId)
      .getOrElse(if (allEvents.isEmpty) 0 else allEvents.map(_.id).max)
    val filtered = allEvents.filter(_.id <= maxId)
    val oneWeekAgo = now.toLocalDate.minusDays(7)

    case class Bucket(
      timeBucket: LocalDate,
      period: EventGroupPeriod,
      eventType: ChangelogEventName,
      events: List[ChangelogEventAndType]
    )
    val buckets: List[Bucket] = filtered
      .groupBy { e =>
        val day = e.createdAt.toLocalDate
        val (bucket, period) =
          if (!day.isBefore(oneWeekAgo)) (day, EventGroupPeriod.DAY)
          else {
            val monday =
              day.minusDays(day.getDayOfWeek.getValue.toLong - 1)
            (monday, EventGroupPeriod.WEEK)
          }
        (bucket, period, e.eventType)
      }
      .map {
        case ((bucket, period, eventType), events) =>
          Bucket(bucket, period, eventType, events)
      }
      .toList
      .sortBy { b =>
        // Newest bucket first; within bucket, last-event time desc
        (
          -b.timeBucket.toEpochDay,
          -b.events.map(_.createdAt.toEpochSecond).max,
          b.eventType.entryName
        )
      }

    val skipped = cursor match {
      case Some(c) =>
        buckets.dropWhile { b =>
          val cmpBucket = b.timeBucket.compareTo(c.timeBucket)
          if (cmpBucket > 0) true
          else if (cmpBucket == 0) {
            val maxTime = b.events.map(_.createdAt).max
            val cmpTime = maxTime.compareTo(c.endTime)
            if (cmpTime > 0) true
            else if (cmpTime == 0) {
              b.eventType.entryName.compareTo(c.eventType.entryName) < 0
            } else false
          } else false
        }
      case None => buckets
    }

    val page = skipped.take(limit + 1)
    val visible = page.take(limit)

    val rows = visible.map { b =>
      val sortedEvents = b.events.sortBy(_.createdAt.toEpochSecond)
      val first = sortedEvents.head
      val last = sortedEvents.last
      val group = ChangelogEventGroup(
        datasetId = dataset.id,
        userIds = sortedEvents.map(_.userId).distinct,
        eventType = b.eventType,
        totalCount = b.events.size.toLong,
        timeBucket = b.timeBucket,
        period = b.period,
        timeRange =
          ChangelogEventGroup.TimeRange(first.createdAt, last.createdAt)
      )
      val payload: Either[ChangelogEventAndType, ChangelogEventCursor] =
        if (b.events.size == 1) Left(b.events.head)
        else
          Right(
            ChangelogEventCursor(
              eventType = b.eventType,
              start = Some(first.createdAt),
              end = Some((last.createdAt, last.id)),
              userId = userId
            )
          )
      (group, payload)
    }

    val nextCursor = page.drop(limit).headOption.map { next =>
      val maxTime = next.events.map(_.createdAt).max
      ChangelogEventGroupCursor(
        timeBucket = next.timeBucket,
        endTime = maxTime,
        eventType = next.eventType,
        maxEventId = maxId
      )
    }

    EitherT.rightT((rows, nextCursor))
  }

  override def getEvents(
    dataset: Dataset,
    limit: Int = 25,
    cursor: ChangelogEventCursor
  )(implicit
    ec: ExecutionContext
  ): EitherT[
    Future,
    CoreError,
    (List[ChangelogEventAndType], Option[ChangelogEventCursor])
  ] = {
    val all = state.changelogEvents.values
      .filter(e => e.datasetId == dataset.id && e.eventType == cursor.eventType)
      .filter(e => cursor.userId.forall(_ == e.userId))
      .filter(e => cursor.start.forall(s => !e.createdAt.isBefore(s)))
      .filter(
        e =>
          cursor.end.forall {
            case (endTime, nextId) =>
              (e.createdAt == endTime && e.id <= nextId) ||
                e.createdAt.isBefore(endTime)
          }
      )
      .toList
      .sortBy(e => (-e.createdAt.toEpochSecond, -e.id))
    val page = all.take(limit + 1)
    val visible = page.take(limit)
    val nextCursor = page.drop(limit).headOption.map { next =>
      ChangelogEventCursor(
        eventType = cursor.eventType,
        start = cursor.start,
        end = Some((next.createdAt, next.id)),
        userId = cursor.userId
      )
    }
    EitherT.rightT((visible, nextCursor))
  }
}
