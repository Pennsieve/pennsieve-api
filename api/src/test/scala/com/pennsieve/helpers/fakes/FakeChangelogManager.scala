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
  ChangelogEventDetail,
  ChangelogEventName,
  Dataset,
  Organization,
  User
}
import com.pennsieve.traits.PostgresProfile.api.Database

import java.time.ZonedDateTime
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
}
