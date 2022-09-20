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

package com.pennsieve.jobs.types

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.implicits._
import com.pennsieve.audit.middleware.TraceId
import com.pennsieve.aws.sns.{ SNS, SNSClient, SNSContainer }
import com.pennsieve.core.utilities.ContainerTypes.SnsTopic
import com.pennsieve.core.utilities.{ DatabaseContainer, InsecureContainer }
import com.pennsieve.db.{ DatasetsMapper, OrganizationsMapper, UserMapper }
import com.pennsieve.jobs.container.Container
import com.pennsieve.jobs.contexts.ChangelogEventContext
import com.pennsieve.jobs.{
  ExceptionError,
  JSONParseFailException,
  JobException
}
import com.pennsieve.managers.ChangelogManager
import com.pennsieve.messages.{ DatasetChangelogEventJob, EventInstance }
import com.pennsieve.models.{
  ChangelogEventAndType,
  ChangelogEventDetail,
  Dataset,
  Organization,
  User
}
import com.pennsieve.service.utilities.{ ContextLogger, LogContext, Tier }
import com.pennsieve.traits.PostgresProfile.api._
import com.typesafe.config.Config
import io.circe._
import io.circe.syntax._
import software.amazon.awssdk.services.sns.SnsAsyncClient

import java.time.ZonedDateTime
import scala.concurrent.{ ExecutionContext, Future }

class DatasetChangelogEvent(
  insecureContainer: DatabaseContainer with SNSContainer,
  eventsTopic: SnsTopic
)(implicit
  log: ContextLogger
) {
  val db: Database = insecureContainer.db
  val sns: SNSClient = insecureContainer.sns

  implicit val tier: Tier[DatasetChangelogEvent] =
    Tier[DatasetChangelogEvent]

  def logEvents(
    traceId: TraceId,
    organization: Organization,
    user: User,
    dataset: Dataset,
    eventDetails: List[(ChangelogEventDetail, Option[ZonedDateTime])]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, JobException, List[ChangelogEventAndType]] = {
    implicit val context: LogContext = ChangelogEventContext(
      organizationId = organization.id,
      userId = user.nodeId,
      traceId = traceId,
      datasetId = dataset.id
    )

    val changelogManager =
      new ChangelogManager(db, organization, user, eventsTopic, sns)
    for ((eventDetail, _) <- eventDetails) {
      log.tierContext.info(s"event = ${eventDetail.eventType.asJson.noSpaces}")
    }
    changelogManager
      .logEvents(dataset, eventDetails)
      .leftMap(e => ExceptionError(e): JobException)
  }

  private def getEventContext(
    organizationId: Int,
    datasetId: Int,
    userId: String
  )(implicit
    ec: ExecutionContext
  ): Future[(Organization, User, Dataset)] = {
    for {
      organizationAndUser <- db
        .run(
          OrganizationsMapper.get(organizationId) zip UserMapper
            .getByNodeId(userId)
        )
        .flatMap {
          case (Some(organization), Some(user)) =>
            Future.successful((organization, user))
          case _ =>
            Future
              .failed(
                new Throwable(
                  s"Invalid organization: ${organizationId} or user: ${userId}"
                )
              )

        }
      (organization, user) = organizationAndUser
      datasetMapper = new DatasetsMapper(organization)
      dataset <- db.run(datasetMapper.getDataset(datasetId))
    } yield (organization, user, dataset)
  }

  def run(
    job: DatasetChangelogEventJob
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, JobException, Unit] = {
    val eventDetails: Either[DecodingFailure, List[
      (ChangelogEventDetail, Option[ZonedDateTime])
    ]] =
      job
        .listEvents()
        .map { e: EventInstance =>
          e.eventDetail
            .as[ChangelogEventDetail](ChangelogEventDetail.decoder(e.eventType))
            .map(ed => (ed, e.timestamp))
        }
        .sequence

    for {
      context <- (eventDetails match {
        case Left(err) =>
          EitherT
            .leftT[
              Future,
              (
                Organization,
                User,
                Dataset,
                List[(ChangelogEventDetail, Option[ZonedDateTime])]
              )
            ](
              JSONParseFailException(
                json = job.listEvents().toString(),
                error = err.message
              ): JobException
            )
        case Right(eventDetails) => {
          EitherT {
            getEventContext(
              organizationId = job.organizationId,
              datasetId = job.datasetId,
              userId = job.userId
            ).map {
              case (org, user, ds) =>
                Right((org, user, ds, eventDetails)): Either[
                  JobException,
                  (
                    Organization,
                    User,
                    Dataset,
                    List[(ChangelogEventDetail, Option[ZonedDateTime])]
                  )
                ]
            }
          }
        }
      })
      (organization, user, dataset, eventDetails) = context
      _ <- logEvents(job.traceId, organization, user, dataset, eventDetails)
    } yield ()
  }
}

object DatasetChangelogEvent {
  def apply(
    config: Config,
    eventsTopic: SnsTopic
  )(implicit
    ec: ExecutionContext,
    system: ActorSystem,
    log: ContextLogger,
    container: Container
  ): DatasetChangelogEvent = {
    println("DatasetChangelogEvent:Apply   " + container.sns)
    new DatasetChangelogEvent(
      insecureContainer = new InsecureContainer(config) with DatabaseContainer
      with SNSContainer {
        override val sns: SNSClient = container.sns
      },
      eventsTopic = eventsTopic
    )
  }

}
