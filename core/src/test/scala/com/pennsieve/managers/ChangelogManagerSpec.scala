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
import com.pennsieve.models._
import com.pennsieve.traits.PostgresProfile.api._
import org.scalatest.OptionValues._
import org.scalatest.EitherValues._
import org.scalatest.matchers.should.Matchers._

import java.util.UUID
import java.time.{ LocalDate, ZoneId, ZoneOffset, ZonedDateTime }

import scala.concurrent.ExecutionContext.Implicits.global

class ChangelogManagerSpec extends BaseManagerSpec {

  import EventGroupPeriod._

  val EST = ZoneId.of("-05:00")
  val Now = ZonedDateTime.of(2021, 2, 14, 9, 0, 0, 0, EST)

  def toUTC(z: ZonedDateTime): ZonedDateTime =
    ZonedDateTime.ofInstant(z.toInstant, ZoneOffset.UTC)

  def timeRange(start: ZonedDateTime, end: ZonedDateTime) =
    ChangelogEventGroup.TimeRange(toUTC(start), toUTC(end))

  def logEvent(
    detail: ChangelogEventDetail,
    date: ZonedDateTime
  )(implicit
    dataset: Dataset,
    cm: ChangelogManager
  ): ChangelogEventAndType =
    cm.logEvent(dataset, detail, date).await.value

  def logEvents(
    events: (ChangelogEventDetail, ZonedDateTime)*
  )(implicit
    dataset: Dataset,
    cm: ChangelogManager
  ): List[ChangelogEventAndType] =
    events.toList
      .traverse {
        case (e, d) => cm.logEvent(dataset, e, d)
      }
      .await
      .value

  "cursor" should "round trip base64 encode/decode" in {

    val cursor = ChangelogEventGroupCursor(
      Now.toLocalDate(),
      Now,
      ChangelogEventName.CREATE_PACKAGE,
      2538
    )

    val encoded = ChangelogEventGroupCursor.encodeBase64(cursor)

    val decoded =
      ChangelogEventGroupCursor.decodeBase64(encoded).value

    cursor shouldBe decoded
  }

  "changelog category" should "resolve event types in the category" in {

    ChangelogEventCategory.DATASET.eventTypes shouldBe List(
      ChangelogEventName.CREATE_DATASET,
      ChangelogEventName.UPDATE_METADATA,
      ChangelogEventName.UPDATE_NAME,
      ChangelogEventName.UPDATE_DESCRIPTION,
      ChangelogEventName.UPDATE_LICENSE,
      ChangelogEventName.ADD_TAG,
      ChangelogEventName.REMOVE_TAG,
      ChangelogEventName.UPDATE_README,
      ChangelogEventName.UPDATE_BANNER_IMAGE,
      ChangelogEventName.ADD_COLLECTION,
      ChangelogEventName.REMOVE_COLLECTION,
      ChangelogEventName.ADD_CONTRIBUTOR,
      ChangelogEventName.REMOVE_CONTRIBUTOR,
      ChangelogEventName.ADD_EXTERNAL_PUBLICATION,
      ChangelogEventName.REMOVE_EXTERNAL_PUBLICATION,
      ChangelogEventName.UPDATE_IGNORE_FILES,
      ChangelogEventName.UPDATE_STATUS
    )

    ChangelogEventCategory.MODELS_AND_RECORDS.eventTypes shouldBe List(
      ChangelogEventName.CREATE_MODEL,
      ChangelogEventName.UPDATE_MODEL,
      ChangelogEventName.DELETE_MODEL,
      ChangelogEventName.CREATE_MODEL_PROPERTY,
      ChangelogEventName.UPDATE_MODEL_PROPERTY,
      ChangelogEventName.DELETE_MODEL_PROPERTY,
      ChangelogEventName.CREATE_RECORD,
      ChangelogEventName.UPDATE_RECORD,
      ChangelogEventName.DELETE_RECORD
    )

  }

  "changelog" should "log events" in {

    val dm = datasetManager()
    val cm = changelogManager()

    val dataset = dm.create("test dataset").await.value

    cm.logEvent(
        dataset,
        ChangelogEventDetail.CreatePackage(433, None, None, None)
      )
      .await
      .value
  }

  "publication log" should "push events to changelog" in {

    val dm = datasetManager()
    val cm = changelogManager()
    val dpsm = datasetPublicationStatusManager()

    val dataset = dm.create("test dataset").await.value

    val status = dpsm
      .create(dataset, PublicationStatus.Requested, PublicationType.Embargo)
      .await
      .value

    val events = database
      .run(cm.changelogEventMapper.getEvents(dataset).result)
      .await
      .toList
      .traverse(ChangelogEventAndType.from(_))
      .value

    events.map(e => (e.userId, e.eventType, e.detail)) shouldBe List(
      (
        superAdmin.id,
        ChangelogEventName.REQUEST_EMBARGO,
        ChangelogEventDetail.RequestEmbargo(status.id)
      )
    )
  }

  "changelog" should "paginate events with a cursor" in {

    val dm = datasetManager()
    implicit val cm = changelogManager()
    implicit val dataset = dm.create("test dataset").await.value

    val e1 = logEvent(
      ChangelogEventDetail.CreatePackage(19, None, None, None),
      Now.minusHours(4)
    )
    val e2 = logEvent(
      ChangelogEventDetail.CreatePackage(20, None, None, None),
      Now.minusHours(3)
    )
    val e3 = logEvent(
      ChangelogEventDetail.CreatePackage(21, None, None, None),
      Now.minusHours(2)
    )
    val e4 = logEvent(
      ChangelogEventDetail.CreatePackage(22, None, None, None),
      Now.minusHours(1)
    )

    val (page1, cursor1) = cm
      .getEvents(
        dataset,
        limit = 3,
        ChangelogEventCursor(
          ChangelogEventName.CREATE_PACKAGE,
          None,
          None,
          None
        )
      )
      .await
      .value

    page1 shouldBe List(e4, e3, e2)
    cursor1 shouldBe defined

    val (page2, cursor2) =
      cm.getEvents(dataset, limit = 3, cursor = cursor1.get).await.value

    page2 shouldBe List(e1)
    cursor2 shouldBe empty
  }

  "changelog" should "paginate events with duplicate timestamps with a cursor" in {

    val dm = datasetManager()
    implicit val cm = changelogManager()
    implicit val dataset = dm.create("test dataset").await.value

    val e1 =
      logEvent(ChangelogEventDetail.CreatePackage(19, None, None, None), Now)
    val e2 =
      logEvent(ChangelogEventDetail.CreatePackage(20, None, None, None), Now)
    val e3 =
      logEvent(ChangelogEventDetail.CreatePackage(21, None, None, None), Now)
    val e4 =
      logEvent(ChangelogEventDetail.CreatePackage(22, None, None, None), Now)

    val (page1, cursor1) = cm
      .getEvents(
        dataset,
        limit = 3,
        ChangelogEventCursor(
          ChangelogEventName.CREATE_PACKAGE,
          None,
          None,
          None
        )
      )
      .await
      .value

    page1 shouldBe List(e4, e3, e2)
    cursor1 shouldBe defined

    val (page2, cursor2) =
      cm.getEvents(dataset, limit = 3, cursor = cursor1.get).await.value

    page2 shouldBe List(e1)
    cursor2 shouldBe empty
  }

  "changelog" should "filter events by user" in {

    val dm = datasetManager()
    implicit val cm = changelogManager()
    implicit val dataset = dm.create("test dataset").await.value

    val user1 = createUser()
    val user2 = createUser()

    val now = ZonedDateTime.now()

    logEvents(
      (
        ChangelogEventDetail.CreatePackage(100, None, None, None),
        now.minusDays(10)
      ),
      (
        ChangelogEventDetail.CreateModel(UUID.randomUUID(), "patient"),
        now.minusDays(15)
      )
    )(dataset, changelogManager(user = user1))

    logEvents(
      (
        ChangelogEventDetail.CreateRecord(UUID.randomUUID(), None, None),
        now.minusDays(6)
      ),
      (
        ChangelogEventDetail.CreatePackage(99, None, None, None),
        now.minusDays(13)
      )
    )(dataset, changelogManager(user = user2))

    val (events, _) = cm
      .getEvents(
        dataset,
        limit = 10,
        ChangelogEventCursor(
          ChangelogEventName.CREATE_PACKAGE,
          None,
          None,
          userId = Some(user2.id)
        )
      )
      .await
      .value

    events.map(_.eventType) shouldBe List(ChangelogEventName.CREATE_PACKAGE)
  }

  "changelog" should "return event for single-event groups" in {

    val dm = datasetManager()
    implicit val cm = changelogManager()
    implicit val dataset = dm.create("test dataset").await.value

    val event =
      logEvent(
        ChangelogEventDetail.CreatePackage(21, None, None, None),
        Now.minusHours(2)
      )

    val (timeline, _) = cm.getTimeline(dataset, now = Now).await.value
    timeline.map(_._2) shouldBe List(Left(event))
  }

  "changelog" should "return cursor for multi-event groups" in {

    val dm = datasetManager()
    implicit val cm = changelogManager()
    implicit val dataset = dm.create("test dataset").await.value

    val e1 = logEvent(
      ChangelogEventDetail.CreatePackage(21, None, None, None),
      Now.minusHours(2)
    )
    val e2 = logEvent(
      ChangelogEventDetail.CreatePackage(22, None, None, None),
      Now.minusHours(1)
    )

    val (timeline, _) = cm.getTimeline(dataset, now = Now).await.value
    timeline.map(_._2) shouldBe List(
      Right(
        ChangelogEventCursor(
          ChangelogEventName.CREATE_PACKAGE,
          toUTC(e1.createdAt).some,
          (toUTC(e2.createdAt), e2.id).some,
          None
        )
      )
    )
  }

  "changelog" should "aggregate events within the last week by day" in {

    val dm = datasetManager()
    implicit val cm = changelogManager()
    implicit val dataset = dm.create("test dataset").await.value

    logEvents(
      (ChangelogEventDetail.CreatePackage(21, None, None, None), Now),
      (
        ChangelogEventDetail.CreatePackage(34, None, None, None),
        Now.minusHours(2)
      ),
      (
        ChangelogEventDetail
          .MovePackage(
            35,
            None,
            None,
            oldParent = Some(ChangelogEventDetail.PackageDetail(23, None, None)),
            newParent = None
          ),
        Now.minusHours(3)
      ),
      (
        ChangelogEventDetail.CreateModel(UUID.randomUUID(), "patient"),
        Now.minusDays(1)
      ),
      (
        ChangelogEventDetail.DeleteModel(UUID.randomUUID(), "visit"),
        Now.minusDays(1).minusHours(1)
      ),
      (
        ChangelogEventDetail.CreatePackage(56, None, None, None),
        Now.minusDays(2)
      )
    )

    val (timeline, cursor) = cm.getTimeline(dataset, now = Now).await.value
    timeline.map(_._1) shouldBe List(
      ChangelogEventGroup(
        dataset.id,
        List(superAdmin.id),
        ChangelogEventName.CREATE_PACKAGE,
        2,
        Now.toLocalDate(),
        DAY,
        timeRange(Now.minusHours(2), Now)
      ),
      ChangelogEventGroup(
        dataset.id,
        List(superAdmin.id),
        ChangelogEventName.MOVE_PACKAGE,
        1,
        Now.toLocalDate(),
        DAY,
        timeRange(Now.minusHours(3), Now.minusHours(3))
      ),
      ChangelogEventGroup(
        dataset.id,
        List(superAdmin.id),
        ChangelogEventName.CREATE_MODEL,
        1,
        Now.toLocalDate().minusDays(1),
        DAY,
        timeRange(Now.minusDays(1), Now.minusDays(1))
      ),
      ChangelogEventGroup(
        dataset.id,
        List(superAdmin.id),
        ChangelogEventName.DELETE_MODEL,
        1,
        Now.toLocalDate().minusDays(1),
        DAY,
        timeRange(
          Now.minusDays(1).minusHours(1),
          Now.minusDays(1).minusHours(1)
        )
      ),
      ChangelogEventGroup(
        dataset.id,
        List(superAdmin.id),
        ChangelogEventName.CREATE_PACKAGE,
        1,
        Now.toLocalDate().minusDays(2),
        DAY,
        timeRange(Now.minusDays(2), Now.minusDays(2))
      )
    )
  }

  "changelog" should "aggregate events within the last month by week" in {

    val dm = datasetManager()
    implicit val cm = changelogManager()
    implicit val dataset = dm.create("test dataset").await.value

    createSampleEvents(Now)

    val (timeline, cursor) = cm.getTimeline(dataset, now = Now).await.value

    timeline.map(_._1) shouldBe List(
      ChangelogEventGroup(
        dataset.id,
        List(superAdmin.id),
        ChangelogEventName.CREATE_RECORD,
        1,
        Now.toLocalDate(),
        DAY,
        timeRange(Now, Now)
      ),
      ChangelogEventGroup(
        dataset.id,
        List(superAdmin.id),
        ChangelogEventName.CREATE_RECORD,
        1,
        Now.toLocalDate().minusDays(6),
        DAY,
        timeRange(Now.minusDays(6), Now.minusDays(6))
      ),
      ChangelogEventGroup(
        dataset.id,
        List(superAdmin.id),
        ChangelogEventName.CREATE_PACKAGE,
        2,
        LocalDate.of(2021, 2, 1),
        WEEK,
        timeRange(Now.minusDays(13), Now.minusDays(10))
      ),
      ChangelogEventGroup(
        dataset.id,
        List(superAdmin.id),
        ChangelogEventName.MOVE_PACKAGE,
        1,
        LocalDate.of(2021, 2, 1),
        WEEK,
        timeRange(Now.minusDays(11), Now.minusDays(11))
      ),
      ChangelogEventGroup(
        dataset.id,
        List(superAdmin.id),
        ChangelogEventName.CREATE_MODEL,
        2,
        LocalDate.of(2021, 1, 25),
        WEEK,
        timeRange(Now.minusDays(16), Now.minusDays(15))
      )
    )
  }

  "changelog" should "round event groups to beginning of month" in {

    val dm = datasetManager()
    implicit val cm = changelogManager()
    implicit val dataset = dm.create("test dataset").await.value

    val Now = ZonedDateTime.of(2021, 2, 16, 9, 0, 0, 0, EST)

    logEvent(
      ChangelogEventDetail.CreateRecord(UUID.randomUUID(), None, None),
      ZonedDateTime.of(2021, 2, 2, 0, 0, 0, 0, EST)
    )

    val (eventGroups, _) = cm
      .getTimeline(
        dataset,
        now = ZonedDateTime.of(2021, 2, 18, 9, 0, 0, 0, EST)
      )
      .await
      .value

    // Should round group to Feb 1st instead of January 28th, which would be the
    // group period if dates were purely selected by 7-day intervals from today.

    eventGroups
      .map(_._1)
      .map(g => (g.eventType, g.timeBucket)) shouldBe List(
      (ChangelogEventName.CREATE_RECORD, LocalDate.of(2021, 2, 1))
    )
  }

  "changelog" should "paginate timeline with cursor" in {

    val dm = datasetManager()
    implicit val cm = changelogManager()
    implicit val dataset = dm.create("test dataset").await.value

    createSampleEvents(Now)

    val (page1, cursor1) =
      cm.getTimeline(dataset, limit = 3, now = Now).await.value
    cursor1 shouldBe defined

    page1.map(_._1) shouldBe List(
      ChangelogEventGroup(
        dataset.id,
        List(superAdmin.id),
        ChangelogEventName.CREATE_RECORD,
        1,
        Now.toLocalDate(),
        DAY,
        timeRange(Now, Now)
      ),
      ChangelogEventGroup(
        dataset.id,
        List(superAdmin.id),
        ChangelogEventName.CREATE_RECORD,
        1,
        Now.toLocalDate().minusDays(6),
        DAY,
        timeRange(Now.minusDays(6), Now.minusDays(6))
      ),
      ChangelogEventGroup(
        dataset.id,
        List(superAdmin.id),
        ChangelogEventName.CREATE_PACKAGE,
        2,
        LocalDate.of(2021, 2, 1),
        WEEK,
        timeRange(Now.minusDays(13), Now.minusDays(10))
      )
    )

    // Should split events across the week boundary

    val (page2, cursor2) =
      cm.getTimeline(dataset, limit = 1, cursor1, now = Now).await.value

    cursor2 shouldBe defined

    page2.map(_._1) shouldBe List(
      ChangelogEventGroup(
        dataset.id,
        List(superAdmin.id),
        ChangelogEventName.MOVE_PACKAGE,
        1,
        LocalDate.of(2021, 2, 1),
        WEEK,
        timeRange(Now.minusDays(11), Now.minusDays(11))
      )
    )

    val (page3, cursor3) =
      cm.getTimeline(dataset, limit = 2, cursor2, now = Now).await.value

    cursor3 shouldBe None

    page3.map(_._1) shouldBe List(
      ChangelogEventGroup(
        dataset.id,
        List(superAdmin.id),
        ChangelogEventName.CREATE_MODEL,
        2,
        LocalDate.of(2021, 1, 25),
        WEEK,
        timeRange(Now.minusDays(16), Now.minusDays(15))
      )
    )
  }

  "changelog" should "filter timeline by event category" in {

    val dm = datasetManager()
    implicit val cm = changelogManager()
    implicit val dataset = dm.create("test dataset").await.value

    createSampleEvents(Now)

    val (packages, _) = cm
      .getTimeline(
        dataset,
        category = Some(ChangelogEventCategory.PACKAGES),
        now = Now
      )
      .await
      .value

    packages
      .map(_._1)
      .map(g => (g.eventType, g.totalCount)) shouldBe List(
      (ChangelogEventName.CREATE_PACKAGE, 2),
      (ChangelogEventName.MOVE_PACKAGE, 1)
    )

    val (page1, cursor) = cm
      .getTimeline(
        dataset,
        category = Some(ChangelogEventCategory.MODELS_AND_RECORDS),
        limit = 2,
        now = Now
      )
      .await
      .value

    page1
      .map(_._1)
      .map(g => (g.eventType, g.totalCount)) shouldBe List(
      (ChangelogEventName.CREATE_RECORD, 1),
      (ChangelogEventName.CREATE_RECORD, 1)
    )

    val (page2, _) = cm
      .getTimeline(
        dataset,
        category = Some(ChangelogEventCategory.MODELS_AND_RECORDS),
        cursor = cursor,
        limit = 2,
        now = Now
      )
      .await
      .value

    page2
      .map(_._1)
      .map(g => (g.eventType, g.totalCount)) shouldBe List(
      (ChangelogEventName.CREATE_MODEL, 2)
    )
  }

  "changelog" should "limit timeline to startDate" in {

    val dm = datasetManager()
    implicit val cm = changelogManager()
    implicit val dataset = dm.create("test dataset").await.value

    createSampleEvents(Now)

    val (page, _) = cm
      .getTimeline(
        dataset,
        startDate = Some(Now.toLocalDate().minusDays(14)),
        now = Now
      )
      .await
      .value

    page
      .map(_._1)
      .map(g => (g.eventType, g.totalCount)) shouldBe List(
      (ChangelogEventName.CREATE_RECORD, 1),
      (ChangelogEventName.CREATE_RECORD, 1),
      (ChangelogEventName.CREATE_PACKAGE, 2),
      (ChangelogEventName.MOVE_PACKAGE, 1)
    )
  }

  "changelog" should "filter timeline by user" in {

    val dm = datasetManager()
    val dataset = dm.create("test dataset").await.value
    val cm = changelogManager()

    val user1 = createUser()
    val user2 = createUser()

    logEvents(
      (ChangelogEventDetail.CreateRecord(UUID.randomUUID(), None, None), Now),
      (
        ChangelogEventDetail.CreatePackage(100, None, None, None),
        Now.minusDays(10)
      ),
      (
        ChangelogEventDetail.MovePackage(
          32,
          None,
          None,
          None,
          Some(ChangelogEventDetail.PackageDetail(12, None, None))
        ),
        Now.minusDays(11)
      ),
      (
        ChangelogEventDetail.CreateModel(UUID.randomUUID(), "patient"),
        Now.minusDays(15)
      )
    )(dataset, changelogManager(user = user1))

    logEvents(
      (
        ChangelogEventDetail.CreateRecord(UUID.randomUUID(), None, None),
        Now.minusDays(6)
      ),
      (
        ChangelogEventDetail.CreatePackage(99, None, None, None),
        Now.minusDays(13)
      ),
      (
        ChangelogEventDetail.CreateModel(UUID.randomUUID(), "visit"),
        Now.minusDays(16)
      )
    )(dataset, changelogManager(user = user2))

    cm.getTimeline(dataset, userId = Some(user1.id), now = Now)
      .await
      .value
      ._1
      .map(_._1)
      .map(g => (g.eventType, g.totalCount)) shouldBe List(
      (ChangelogEventName.CREATE_RECORD, 1),
      (ChangelogEventName.CREATE_PACKAGE, 1),
      (ChangelogEventName.MOVE_PACKAGE, 1),
      (ChangelogEventName.CREATE_MODEL, 1)
    )

    cm.getTimeline(dataset, userId = Some(user2.id), now = Now)
      .await
      .value
      ._1
      .map(_._1)
      .map(g => (g.eventType, g.totalCount)) shouldBe List(
      (ChangelogEventName.CREATE_RECORD, 1),
      (ChangelogEventName.CREATE_PACKAGE, 1),
      (ChangelogEventName.CREATE_MODEL, 1)
    )
  }

  "changelog" should "order event groups by timestamp of most recent event" in {

    val dm = datasetManager()
    implicit val cm = changelogManager()
    implicit val dataset = dm.create("test dataset").await.value

    logEvents(
      (ChangelogEventDetail.CreateRecord(UUID.randomUUID(), None, None), Now),
      (
        ChangelogEventDetail.CreateModel(UUID.randomUUID(), "patient"),
        Now.minusHours(1)
      ),
      (
        ChangelogEventDetail.CreateRecord(UUID.randomUUID(), None, None),
        Now.minusHours(2)
      )
    )

    cm.getTimeline(dataset, now = Now)
      .await
      .value
      ._1
      .map(_._1)
      .map(g => g.eventType) shouldBe List(
      ChangelogEventName.CREATE_RECORD,
      ChangelogEventName.CREATE_MODEL
    )
  }

  "changelog" should "maintain group ordering when new events are added" in {

    val dm = datasetManager()
    implicit val cm = changelogManager()
    implicit val dataset = dm.create("test dataset").await.value

    logEvents(
      (
        ChangelogEventDetail.CreateModel(UUID.randomUUID(), "patient"),
        Now.minusHours(1)
      ),
      (
        ChangelogEventDetail.CreateRecord(UUID.randomUUID(), None, None),
        Now.minusHours(2)
      )
    )
    val (page1, cursor1) =
      cm.getTimeline(dataset, limit = 1, now = Now).await.value

    page1
      .map(_._1)
      .map(g => (g.eventType, g.totalCount)) shouldBe List(
      (ChangelogEventName.CREATE_MODEL, 1)
    )

    cursor1 shouldBe defined

    // Log an extra event before pulling the first page. The new event is now
    // part of the same event group as the other CREATE_RECORD event, but should
    // not be returned because the cursor's maxEventId excludes it.

    logEvent(
      ChangelogEventDetail.CreateRecord(UUID.randomUUID(), None, None),
      Now
    )

    val (page2, cursor2) =
      cm.getTimeline(dataset, limit = 1, cursor = cursor1, now = Now)
        .await
        .value

    page2
      .map(_._1)
      .map(g => (g.eventType, g.totalCount)) shouldBe List(
      (ChangelogEventName.CREATE_RECORD, 1)
    )

    cursor2 shouldBe None
  }

  def createSampleEvents(
    now: ZonedDateTime
  )(implicit
    dataset: Dataset,
    cm: ChangelogManager
  ) = {

    logEvents(
      // These events still aggregated by day
      (ChangelogEventDetail.CreateRecord(UUID.randomUUID(), None, None), now),
      (
        ChangelogEventDetail.CreateRecord(UUID.randomUUID(), None, None),
        now.minusDays(6)
      ),
      // But these by week
      (
        ChangelogEventDetail.CreatePackage(100, None, None, None),
        now.minusDays(10)
      ),
      (
        ChangelogEventDetail.MovePackage(
          32,
          None,
          None,
          None,
          Some(ChangelogEventDetail.PackageDetail(23, None, None))
        ),
        now.minusDays(11)
      ),
      (
        ChangelogEventDetail.CreatePackage(99, None, None, None),
        now.minusDays(13)
      ),
      (
        ChangelogEventDetail.CreateModel(UUID.randomUUID(), "patient"),
        now.minusDays(15)
      ),
      (
        ChangelogEventDetail.CreateModel(UUID.randomUUID(), "visit"),
        now.minusDays(16)
      )
    )
  }
}
