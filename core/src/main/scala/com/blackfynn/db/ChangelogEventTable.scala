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

import com.pennsieve.models._
import com.pennsieve.traits.PostgresProfile.api._
import java.time.ZonedDateTime

import cats.Semigroup
import cats.implicits._
import com.rms.miu.slickcats.DBIOInstances._
import io.circe.Json
import io.circe.syntax._
import io.circe.parser.decode
import slick.lifted.Case._

import com.pennsieve.domain.SqlError
import com.pennsieve.traits.PostgresProfile

import scala.concurrent.ExecutionContext

final class ChangelogEventTable(schema: String, tag: Tag)
    extends Table[ChangelogEvent](tag, Some(schema), "changelog_events") {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def datasetId = column[Int]("dataset_id")

  // TODO: nullable? to handle the system user?
  def userId = column[Int]("user_id")
  def eventTypeId = column[Int]("event_type_id")
  def detail = column[Json]("detail")
  def createdAt = column[ZonedDateTime]("created_at")

  def * =
    (datasetId, userId, eventTypeId, detail, createdAt, id)
      .mapTo[ChangelogEvent]
}

class ChangelogEventMapper(val organization: Organization)
    extends TableQuery(new ChangelogEventTable(organization.schemaId, _)) {

  lazy val changelogEventTypeMapper = new ChangelogEventTypeMapper(organization)

  def getEvents(dataset: Dataset)(implicit ec: ExecutionContext): Query[
    (ChangelogEventTable, ChangelogEventTypeTable),
    (ChangelogEvent, ChangelogEventType),
    Seq
  ] =
    this
      .filter(_.datasetId === dataset.id)
      .join(changelogEventTypeMapper)
      .on(_.eventTypeId === _.id)

  def logEvent(
    dataset: Dataset,
    detail: ChangelogEventDetail,
    user: User,
    timestamp: ZonedDateTime = ZonedDateTime.now()
  )(implicit
    ec: ExecutionContext
  ): DBIO[(ChangelogEvent, ChangelogEventType)] =
    for {
      eventType <- changelogEventTypeMapper.getOrCreate(detail.eventType)

      event <- (this returning this) +=
        ChangelogEvent(
          datasetId = dataset.id,
          userId = user.id,
          eventTypeId = eventType.id,
          detail = detail.asJson,
          createdAt = timestamp
        )
    } yield (event, eventType)
}
