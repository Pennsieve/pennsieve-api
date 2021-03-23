package com.pennsieve.jobs.types

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.implicits._
import com.pennsieve.audit.middleware.TraceId
import com.pennsieve.core.utilities.{ DatabaseContainer, InsecureContainer }
import com.pennsieve.db.{ DatasetsMapper, OrganizationsMapper, UserMapper }
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

import java.time.ZonedDateTime
import scala.concurrent.{ ExecutionContext, Future }

class DatasetChangelogEvent(
  insecureContainer: DatabaseContainer
)(implicit
  log: ContextLogger
) {
  val db: Database = insecureContainer.db

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
    val changelogManager = new ChangelogManager(db, organization, user)
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
      job.listEvents.map { e: EventInstance =>
        e.eventDetail
          .as[ChangelogEventDetail](ChangelogEventDetail.decoder(e.eventType))
          .map(ed => (ed, e.timestamp))
      }.sequence

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
                json = job.listEvents.toString(),
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
    config: Config
  )(implicit
    ec: ExecutionContext,
    system: ActorSystem,
    log: ContextLogger
  ): DatasetChangelogEvent =
    new DatasetChangelogEvent(
      new InsecureContainer(config) with DatabaseContainer
    )
}
