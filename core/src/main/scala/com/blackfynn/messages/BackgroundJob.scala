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

package com.pennsieve.messages

import com.pennsieve.audit.middleware.TraceId
import com.pennsieve.models.ChangelogEventName
import io.circe.generic.extras.semiauto.{
  deriveUnwrappedDecoder,
  deriveUnwrappedEncoder
}
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.{ Decoder, Encoder, Json }

import java.time.ZonedDateTime
import java.util.UUID

sealed trait BackgroundJob {
  val id: String
}

object BackgroundJob {
  val queueName: String = "background_job_queue"

  implicit val encoder: Encoder[BackgroundJob] = deriveEncoder[BackgroundJob]
  implicit val decoder: Decoder[BackgroundJob] = deriveDecoder[BackgroundJob]

  implicit val traceIdEncoder: Encoder[TraceId] =
    deriveUnwrappedEncoder[TraceId]
  implicit val traceIdDecoder: Decoder[TraceId] =
    deriveUnwrappedDecoder[TraceId]
}

case class CachePopulationJob(
  dryRun: Boolean,
  organizationId: Option[Int] = None,
  id: String = UUID.randomUUID().toString,
  deleteRedisData: Option[Boolean] = None
) extends BackgroundJob

sealed trait CatalogDeleteJob extends BackgroundJob {
  val userId: String
  val organizationId: Int
  val traceId: TraceId
}

case class DeletePackageJob(
  packageId: Int,
  organizationId: Int,
  userId: String,
  traceId: TraceId,
  id: String = UUID.randomUUID().toString
) extends CatalogDeleteJob

case class DeleteDatasetJob(
  datasetId: Int,
  organizationId: Int,
  userId: String,
  traceId: TraceId,
  id: String = UUID.randomUUID().toString
) extends CatalogDeleteJob

case class EventInstance(
  eventType: ChangelogEventName,
  eventDetail: Json,
  timestamp: Option[ZonedDateTime] = None
)

object EventInstance {
  implicit val encoder: Encoder[EventInstance] = deriveEncoder[EventInstance]
  implicit val decoder: Decoder[EventInstance] = deriveDecoder[EventInstance]
}

case class DatasetChangelogEventJob(
  organizationId: Int,
  datasetId: Int,
  userId: String,
  traceId: TraceId,
  events: Option[List[EventInstance]] = None,
  eventType: Option[ChangelogEventName] = None,
  eventDetail: Option[Json] = None,
  id: String = UUID.randomUUID().toString
) extends BackgroundJob {
  def listEvents(): List[EventInstance] = {
    (events, eventType, eventDetail) match {
      case (Some(events), _, _) => events
      case (None, Some(eventType), Some(eventDetail)) =>
        List(EventInstance(eventType = eventType, eventDetail = eventDetail))
      case _ => List.empty
    }
  }
}
