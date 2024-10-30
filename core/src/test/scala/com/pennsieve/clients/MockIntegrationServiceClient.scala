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

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ HttpHeader, HttpRequest, HttpResponse }
import cats.data.EitherT
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
import net.ceedubs.ficus.Ficus._

import java.time.OffsetDateTime
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ ExecutionContext, Future }

trait MockIntegrationServiceContainer extends IntegrationServiceContainer {
  self: Container =>

  lazy val integrationServiceConfigPath = "integration_service"

  lazy val uploadServiceHost: String =
    config.as[String](s"$integrationServiceConfigPath.host")

  override val integrationServiceClient: IntegrationServiceClient =
    new LocalIntegrationServiceClient()
}

class MockIntegrationServiceClient(
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
}
