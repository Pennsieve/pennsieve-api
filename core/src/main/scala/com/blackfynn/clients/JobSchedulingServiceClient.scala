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

import java.net.URI

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.util.ByteString
import cats.data.EitherT
import cats.instances.future._
import cats.syntax.either._
import com.pennsieve.auth.middleware.Jwt
import com.pennsieve.models.{ JobId, Payload }
import com.pennsieve.utilities.Container
import com.typesafe.scalalogging.StrictLogging
import io.circe.syntax._
import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import com.pennsieve.jobscheduling.clients.generated.jobs.JobsClient
import com.pennsieve.service.utilities.QueueHttpResponder

import scala.concurrent.{ ExecutionContext, Future }
import scala.collection.mutable.ArrayBuffer

case class Quota(slotsAllowed: Int)

object Quota {
  implicit def encoder: Encoder[Quota] = deriveEncoder[Quota]
  implicit def decoder: Decoder[Quota] = deriveDecoder[Quota]
}

trait JobSchedulingServiceClient {
  val jobSchedulingServiceHost: String

  implicit val system: ActorSystem
  implicit val ec: ExecutionContext

  def setOrganizationQuota(
    organizationId: Int,
    quota: Quota,
    token: Jwt.Token
  ): EitherT[Future, Throwable, HttpResponse]
}

class LocalJobSchedulingServiceClient(
)(implicit
  override val system: ActorSystem,
  override val ec: ExecutionContext
) extends JobSchedulingServiceClient {
  override val jobSchedulingServiceHost = "test-job-scheduling-service-url"

  val organizationQuotas: ArrayBuffer[(Int, Quota)] =
    ArrayBuffer.empty[(Int, Quota)]

  def setOrganizationQuota(
    organizationId: Int,
    quota: Quota,
    token: Jwt.Token
  ): EitherT[Future, Throwable, HttpResponse] = {
    organizationQuotas += organizationId -> quota
    EitherT.rightT[Future, Throwable](HttpResponse(201))
  }
}

class JobSchedulingClientImpl(
  override val jobSchedulingServiceHost: String
)(implicit
  override val system: ActorSystem,
  override val ec: ExecutionContext
) extends JobSchedulingServiceClient
    with StrictLogging {

  def makeRequest(
    req: HttpRequest,
    token: Jwt.Token
  ): EitherT[Future, Throwable, HttpResponse] = {
    EitherT(
      Http()
        .singleRequest(
          req.addHeader(
            headers.Authorization(headers.OAuth2BearerToken(token.value))
          )
        )
        .map(
          resp =>
            if (resp.status.isSuccess) {
              resp.asRight
            } else {
              val error = ServerError(
                s"Error communicating with the job scheduling service: ${resp.toString}"
              )
              logger.error(error.message)
              Left[Throwable, HttpResponse](error)
            }
        )
    )
  }

  def setOrganizationQuota(
    organizationId: Int,
    quota: Quota,
    token: Jwt.Token
  ): EitherT[Future, Throwable, HttpResponse] = {
    val requestData = ByteString(quota.asJson.noSpaces)
    makeRequest(
      HttpRequest(
        HttpMethods.POST,
        s"$jobSchedulingServiceHost/organizations/${organizationId}/set/quota",
        entity = HttpEntity
          .Strict(ContentType(MediaTypes.`application/json`), requestData)
      ),
      token
    )
  }
}

trait JobSchedulingServiceContainer { self: Container =>
  implicit val system: ActorSystem
  implicit val ec: ExecutionContext

  val jobSchedulingServiceHost: String
  val jobSchedulingServiceQueueSize: Int
  val jobSchedulingServiceRateLimit: Int
  val jobSchedulingServiceClient: JobSchedulingServiceClient
  val jobsClient: JobsClient
}

trait JobSchedulingServiceContainerImpl extends JobSchedulingServiceContainer {
  self: Container =>
  implicit val system: ActorSystem
  implicit val ec: ExecutionContext

  val jobSchedulingServiceHost: String
  val jobSchedulingServiceQueueSize: Int
  val jobSchedulingServiceRateLimit: Int
  override lazy val jobSchedulingServiceClient: JobSchedulingServiceClient =
    new JobSchedulingClientImpl(jobSchedulingServiceHost)
  override lazy val jobsClient: JobsClient = {
    val host = URI.create(jobSchedulingServiceHost).getHost
    val queuedRequestHttpClient: HttpRequest => Future[HttpResponse] =
      QueueHttpResponder(
        host,
        jobSchedulingServiceQueueSize,
        jobSchedulingServiceRateLimit
      ).responder

    JobsClient.httpClient(queuedRequestHttpClient, jobSchedulingServiceHost)
  }
}

trait LocalJobSchedulingServiceContainer extends JobSchedulingServiceContainer {
  self: Container =>
  implicit val system: ActorSystem
  implicit val ec: ExecutionContext

  val jobSchedulingServiceHost: String
  val jobSchedulingServiceQueueSize: Int
  val jobSchedulingServiceRateLimit: Int
  override val jobSchedulingServiceClient: JobSchedulingServiceClient =
    new LocalJobSchedulingServiceClient
  override lazy val jobsClient: JobsClient = {
    val host = jobSchedulingServiceHost
    val queuedRequestHttpClient: HttpRequest => Future[HttpResponse] =
      QueueHttpResponder(
        host,
        jobSchedulingServiceQueueSize,
        jobSchedulingServiceRateLimit
      ).responder

    JobsClient.httpClient(queuedRequestHttpClient, host)
  }
}
