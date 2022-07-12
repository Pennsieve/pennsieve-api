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

package com.pennsieve.admin.api.services

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ HttpHeader, HttpRequest, HttpResponse }
import cats.implicits._
import cats.data.EitherT
import com.pennsieve.discover.client.definitions
import com.pennsieve.discover.client.publish.{
  GetStatusResponse,
  GetStatusesResponse,
  PublishClient,
  PublishResponse,
  RemoveDatasetSponsorResponse,
  SponsorDatasetResponse,
  UnpublishResponse
}
import com.pennsieve.models.PublishStatus
import com.pennsieve.discover.client.definitions.{
  DatasetPublishStatus,
  SponsorshipRequest,
  SponsorshipResponse
}

import scala.concurrent.{ ExecutionContext, Future }

class MockPublishClient(
)(implicit
  httpClient: HttpRequest => Future[HttpResponse],
  ec: ExecutionContext,
  system: ActorSystem
) extends PublishClient("mock-discover-service-host") {

  override def getStatuses(
    organizationId: Int,
    headers: List[HttpHeader]
  ): EitherT[Future, Either[Throwable, HttpResponse], GetStatusesResponse] = {
    EitherT.rightT[Future, Either[Throwable, HttpResponse]](
      GetStatusesResponse.OK(
        Vector(
          DatasetPublishStatus(
            "PPMI",
            organizationId,
            1,
            None,
            0,
            PublishStatus.PublishInProgress,
            None,
            Some(SponsorshipRequest(Some("foo"), Some("bar"), Some("baz")))
          ),
          DatasetPublishStatus(
            "TUSZ",
            organizationId,
            2,
            None,
            0,
            PublishStatus.PublishInProgress,
            None
          )
        )
      )
    )
  }

  override def sponsorDataset(
    sourceOrganizationId: Int,
    sourceDatasetId: Int,
    body: SponsorshipRequest,
    headers: List[HttpHeader]
  ): EitherT[Future, Either[Throwable, HttpResponse], SponsorDatasetResponse] = {

    EitherT.rightT[Future, Either[Throwable, HttpResponse]](
      if (sourceDatasetId === 1) {
        SponsorDatasetResponse.Created(SponsorshipResponse(1, 1))
      } else {
        SponsorDatasetResponse.NotFound(
          s"Expected mock datasetId with value 1 but got ${sourceDatasetId}"
        )
      }
    )

  }

  override def removeDatasetSponsor(
    sourceOrganizationId: Int,
    sourceDatasetId: Int,
    headers: List[HttpHeader]
  ): EitherT[Future, Either[Throwable, HttpResponse], RemoveDatasetSponsorResponse] = {

    EitherT.rightT[Future, Either[Throwable, HttpResponse]](
      if (sourceDatasetId === 1) {
        RemoveDatasetSponsorResponse.NoContent
      } else {
        RemoveDatasetSponsorResponse.NotFound(
          s"Expected mock datasetId with value 1 but got ${sourceDatasetId}"
        )
      }
    )

  }

}
