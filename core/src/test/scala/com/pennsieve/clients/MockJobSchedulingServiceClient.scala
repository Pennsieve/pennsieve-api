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

package com.pennsieve.clients

import java.time.OffsetDateTime

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ HttpHeader, HttpRequest, HttpResponse }
import cats.data.EitherT
import cats.implicits._
import com.pennsieve.jobscheduling.clients.generated.definitions.{
  Job,
  UploadResult
}
import com.pennsieve.jobscheduling.clients.generated.jobs.{
  CompleteUploadResponse,
  CreateResponse,
  GetPackageStateResponse,
  JobsClient
}
import com.pennsieve.jobscheduling.commons.JobState.Running
import com.pennsieve.models.{ JobId, PackageState, Payload }
import com.pennsieve.utilities.Container

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ ExecutionContext, Future }

trait MockJobSchedulingServiceContainer extends JobSchedulingServiceContainer {
  self: Container =>

  lazy val jobSchedulingServiceConfigPath = "job_scheduling_service"

  import net.ceedubs.ficus.Ficus._
  lazy val jobSchedulingServiceHost: String =
    config.as[String](s"$jobSchedulingServiceConfigPath.host")
  lazy val jobSchedulingServiceQueueSize: Int =
    config.as[Int](s"$jobSchedulingServiceConfigPath.queue_size")
  lazy val jobSchedulingServiceRateLimit: Int =
    config.as[Int](s"$jobSchedulingServiceConfigPath.rate_limit")

  override val jobSchedulingServiceClient: JobSchedulingServiceClient =
    new LocalJobSchedulingServiceClient()(system, ec)

  override lazy val jobsClient: JobsClient = {
    implicit val httpClient: HttpRequest => Future[HttpResponse] = { _ =>
      Future.successful(HttpResponse())
    }
    new MockJobSchedulingServiceClient()(httpClient, ec, system)
  }
}

class MockJobSchedulingServiceClient(
  implicit
  httpClient: HttpRequest => Future[HttpResponse],
  ec: ExecutionContext,
  system: ActorSystem
) extends JobsClient("mock-job-scheduling-service-host") {

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
