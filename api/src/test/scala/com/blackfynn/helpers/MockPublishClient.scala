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

package com.pennsieve.helpers

import java.time.{ OffsetDateTime, ZoneOffset }

import akka.http.scaladsl.model.{ HttpHeader, HttpRequest, HttpResponse }
import akka.stream.Materializer
import cats.implicits._
import cats.data.EitherT
import com.pennsieve.discover.client.definitions
import com.pennsieve.discover.client.publish.{
  GetStatusResponse,
  GetStatusesResponse,
  PublishClient,
  PublishResponse,
  ReleaseResponse,
  ReviseResponse,
  UnpublishResponse
}
import com.pennsieve.models.PublishStatus
import com.pennsieve.discover.client.definitions.DatasetPublishStatus

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.mutable
import java.time.LocalDate

class MockPublishClient(
  httpClient: HttpRequest => Future[HttpResponse],
  ec: ExecutionContext,
  mat: Materializer
) extends PublishClient("mock-discover-service-host")(httpClient, ec, mat) {

  def clear: Unit = {
    nextGetStatusValue = None
    publishRequests.clear
    releaseRequests.clear
  }

  private var nextGetStatusValue: Option[PublishStatus] = None
  private def nextStatus(): PublishStatus = {
    val result = nextGetStatusValue.getOrElse(PublishStatus.PublishInProgress)
    nextGetStatusValue = None
    result
  }

  def withNextGetStatusValue(nextStatusValue: PublishStatus): Unit = {
    nextGetStatusValue = Some(nextStatusValue)
  }

  // (organization, dataset) -> (embargo, request)
  var publishRequests =
    mutable.Map.empty[
      (Int, Int),
      (Option[Boolean], Option[LocalDate], definitions.PublishRequest)
    ]

  override def publish(
    organizationId: Int,
    datasetId: Int,
    embargo: Option[Boolean],
    embargoReleaseDate: Option[LocalDate],
    body: definitions.PublishRequest,
    headers: List[HttpHeader]
  ): EitherT[Future, Either[Throwable, HttpResponse], PublishResponse] = {

    val error = body.name.contains("MOCK ERROR")

    publishRequests += ((organizationId, datasetId) -> (embargo, embargoReleaseDate, body))

    if (error) {
      EitherT.rightT[Future, Either[Throwable, HttpResponse]](
        PublishResponse.InternalServerError("mock error")
      )
    } else {
      EitherT.rightT[Future, Either[Throwable, HttpResponse]](
        PublishResponse.Created(
          DatasetPublishStatus(
            name = "PPMI",
            sourceOrganizationId = organizationId,
            sourceDatasetId = datasetId,
            publishedDatasetId = None,
            publishedVersionCount = 0,
            status = PublishStatus.PublishInProgress,
            lastPublishedDate = None,
            sponsorship = None
          )
        )
      )
    }
  }

  override def revise(
    organizationId: Int,
    datasetId: Int,
    body: definitions.ReviseRequest,
    headers: List[HttpHeader]
  ): EitherT[Future, Either[Throwable, HttpResponse], ReviseResponse] = {

    val error = body.name.contains("MOCK ERROR")

    if (error) {
      EitherT.rightT[Future, Either[Throwable, HttpResponse]](
        ReviseResponse.InternalServerError("mock error")
      )
    } else {
      EitherT.rightT[Future, Either[Throwable, HttpResponse]](
        ReviseResponse.Created(
          DatasetPublishStatus(
            name = "Revised PPMI",
            sourceOrganizationId = organizationId,
            sourceDatasetId = datasetId,
            publishedDatasetId = Some(38),
            publishedVersionCount = 3,
            status = PublishStatus.PublishSucceeded,
            lastPublishedDate = None,
            sponsorship = None
          )
        )
      )
    }
  }

  var releaseRequests =
    mutable.ArrayBuffer.empty[(Int, Int)]

  override def release(
    organizationId: Int,
    datasetId: Int,
    headers: List[HttpHeader]
  ): EitherT[Future, Either[Throwable, HttpResponse], ReleaseResponse] = {
    releaseRequests += ((organizationId, datasetId))

    EitherT.rightT[Future, Either[Throwable, HttpResponse]](
      ReleaseResponse.Accepted(
        DatasetPublishStatus(
          name = "PPMI",
          sourceOrganizationId = organizationId,
          sourceDatasetId = datasetId,
          publishedDatasetId = None,
          publishedVersionCount = 0,
          status = PublishStatus.ReleaseInProgress,
          lastPublishedDate = None,
          sponsorship = None
        )
      )
    )
  }

  override def unpublish(
    organizationId: Int,
    datasetId: Int,
    headers: List[HttpHeader]
  ): EitherT[Future, Either[Throwable, HttpResponse], UnpublishResponse] = {
    EitherT.rightT[Future, Either[Throwable, HttpResponse]](
      UnpublishResponse.OK(
        DatasetPublishStatus(
          name = "PPMI",
          sourceOrganizationId = organizationId,
          sourceDatasetId = datasetId,
          publishedDatasetId = None,
          publishedVersionCount = 0,
          status = PublishStatus.NotPublished,
          lastPublishedDate = None,
          sponsorship = None
        )
      )
    )
  }

  override def getStatus(
    organizationId: Int,
    datasetId: Int,
    headers: List[HttpHeader]
  ): EitherT[Future, Either[Throwable, HttpResponse], GetStatusResponse] = {
    EitherT.rightT[Future, Either[Throwable, HttpResponse]](
      GetStatusResponse.OK(
        DatasetPublishStatus(
          name = "PPMI",
          sourceOrganizationId = organizationId,
          sourceDatasetId = datasetId,
          publishedDatasetId = None,
          publishedVersionCount = 0,
          status = nextStatus(),
          lastPublishedDate = None,
          sponsorship = None
        )
      )
    )
  }

  override def getStatuses(
    organizationId: Int,
    headers: List[HttpHeader]
  ): EitherT[Future, Either[Throwable, HttpResponse], GetStatusesResponse] = {
    EitherT.rightT[Future, Either[Throwable, HttpResponse]](
      GetStatusesResponse.OK(
        IndexedSeq(
          DatasetPublishStatus(
            name = "PPMI",
            sourceOrganizationId = organizationId,
            sourceDatasetId = 1,
            publishedDatasetId = Some(10),
            publishedVersionCount = 2,
            status = PublishStatus.PublishInProgress,
            lastPublishedDate = Some(
              OffsetDateTime.of(2019, 2, 1, 10, 11, 12, 13, ZoneOffset.UTC)
            ),
            sponsorship = None
          ),
          DatasetPublishStatus(
            name = "TUSZ",
            sourceOrganizationId = organizationId,
            sourceDatasetId = 2,
            publishedDatasetId = Some(12),
            publishedVersionCount = 3,
            status = PublishStatus.PublishInProgress,
            lastPublishedDate = Some(
              OffsetDateTime.of(2019, 4, 1, 10, 11, 12, 13, ZoneOffset.UTC)
            ),
            sponsorship = None
          )
        )
      )
    )
  }

}
