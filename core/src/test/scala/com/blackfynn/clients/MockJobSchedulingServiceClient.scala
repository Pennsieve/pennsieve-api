package com.blackfynn.clients

import java.time.OffsetDateTime

import akka.http.scaladsl.model.{ HttpHeader, HttpRequest, HttpResponse }
import akka.stream.{ ActorMaterializer, Materializer }
import cats.data.EitherT
import cats.implicits._
import com.blackfynn.jobscheduling.clients.generated.definitions.{
  Job,
  UploadResult
}
import com.blackfynn.jobscheduling.clients.generated.jobs.{
  CompleteUploadResponse,
  CreateResponse,
  GetPackageStateResponse,
  JobsClient
}
import com.blackfynn.jobscheduling.commons.JobState.Running
import com.blackfynn.models.{ JobId, PackageState, Payload }
import com.blackfynn.utilities.Container

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ ExecutionContext, Future }

trait MockJobSchedulingServiceContainer extends JobSchedulingServiceContainer {
  self: Container =>

  lazy val jobSchedulingServiceConfigPath = "job_scheduling_service"

  import net.ceedubs.ficus.Ficus._
  override lazy val materializer: ActorMaterializer =
    ActorMaterializer()
  lazy val jobSchedulingServiceHost: String =
    config.as[String](s"$jobSchedulingServiceConfigPath.host")
  lazy val jobSchedulingServiceQueueSize: Int =
    config.as[Int](s"$jobSchedulingServiceConfigPath.queue_size")
  lazy val jobSchedulingServiceRateLimit: Int =
    config.as[Int](s"$jobSchedulingServiceConfigPath.rate_limit")

  override val jobSchedulingServiceClient: JobSchedulingServiceClient =
    new LocalJobSchedulingServiceClient()(system, ec)

  override lazy val jobsClient: JobsClient = {
    val httpClient: HttpRequest => Future[HttpResponse] = { _ =>
      Future.successful(HttpResponse())
    }
    new MockJobSchedulingServiceClient(httpClient, ec, materializer)
  }
}

class MockJobSchedulingServiceClient(
  httpClient: HttpRequest => Future[HttpResponse],
  ec: ExecutionContext,
  mat: Materializer
) extends JobsClient("mock-job-scheduling-service-host")(httpClient, ec, mat) {

  val payloadsSent: ArrayBuffer[Payload] = ArrayBuffer.empty[Payload]
  val availableJobs: ArrayBuffer[JobId] = ArrayBuffer.empty[JobId]
  val notProcessingJobs: ArrayBuffer[JobId] = ArrayBuffer.empty[JobId]

  override def create(
    organizationId: Int,
    jobId: String,
    payload: Payload,
    headers: List[HttpHeader]
  ): EitherT[Future, Either[Throwable, HttpResponse], CreateResponse] = {
    payloadsSent += payload
    availableJobs += JobId(java.util.UUID.fromString(jobId))
    val job = Job(
      java.util.UUID.fromString(jobId),
      organizationId,
      Some(payload.userId),
      1,
      Running,
      OffsetDateTime.now(),
      OffsetDateTime.now()
    )
    EitherT.rightT[Future, Either[Throwable, HttpResponse]](
      CreateResponse.Created(job)
    )
  }

  override def completeUpload(
    organizationId: Int,
    jobId: String,
    uploadResult: UploadResult,
    headers: List[HttpHeader]
  ): EitherT[Future, Either[Throwable, HttpResponse], CompleteUploadResponse] = {
    notProcessingJobs += JobId(java.util.UUID.fromString(jobId))
    EitherT.rightT[Future, Either[Throwable, HttpResponse]](
      CompleteUploadResponse.OK
    )
  }

  override def getPackageState(
    organizationId: Int,
    datasetId: Int,
    packageId: Int,
    headers: List[HttpHeader]
  ): EitherT[Future, Either[Throwable, HttpResponse], GetPackageStateResponse] =
    datasetId match {
      case 2 =>
        EitherT.rightT[Future, Either[Throwable, HttpResponse]](
          GetPackageStateResponse.NotFound
        )
      case _ =>
        EitherT.rightT[Future, Either[Throwable, HttpResponse]](
          GetPackageStateResponse.OK(PackageState.PROCESSING)
        )
    }

}
