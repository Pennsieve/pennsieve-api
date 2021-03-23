package com.pennsieve.akka.consumer

import cats.data.EitherT
import cats.implicits._
import com.pennsieve.models.{ PayloadType, Upload }
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.db.{ OrganizationsMapper, PackagesMapper }
import com.pennsieve.models
import com.pennsieve.models.PackageState
import com.pennsieve.traits.PostgresProfile.api.Database
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.{ ExecutionContext, Future }

// TODO: move this to `uploads-consumer`

object TriggerUtilities extends LazyLogging {

  def getJob(manifest: models.Manifest): Either[Throwable, Upload] =
    manifest.content match {
      case job: Upload => job.asRight
      case _ =>
        Left(new Exception("invalid job-type sent to consumer"))
    }

  def failManifest(
    manifest: models.Manifest
  )(implicit
    executionContext: ExecutionContext,
    db: Database
  ): EitherT[Future, Throwable, PayloadType] =
    for {
      organization <- EitherT(
        db.run(OrganizationsMapper.get(manifest.organizationId))
          .map(
            _.toRight(
              new Exception(s"Invalid org id: ${manifest.organizationId}")
            )
          )
      )
      packagesMapper = new PackagesMapper(organization)
      job <- getJob(manifest).toEitherT[Future]

      _ = logger.error(s"Erroring manifest in DLQ: $manifest")

      _ <- db
        .run(
          packagesMapper.updateState(job.packageId, PackageState.UPLOAD_FAILED)
        )
        .toEitherT
        .flatMap[Throwable, Unit] {
          case 1 => EitherT.rightT[Future, Throwable](())
          case _ =>
            EitherT.leftT[Future, Unit](
              new Exception(s"Failed to error package: ${job.packageId}")
            )
        }
    } yield manifest.`type`
}
