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

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{
  ContentTypes,
  HttpEntity,
  HttpHeader,
  HttpRequest,
  HttpResponse,
  StatusCodes
}
import cats.data.EitherT
import cats.implicits._
import com.pennsieve.discover.client.definitions.{
  DatasetPublishStatus,
  DatasetsPage,
  UnpublishRequest
}
import com.pennsieve.discover.client.publish.{
  GetStatusResponse,
  GetStatusesResponse,
  PublishClient,
  UnpublishResponse
}
import com.pennsieve.discover.client.search.{
  SearchClient,
  SearchDatasetsResponse
}
import com.pennsieve.models.PublishStatus

import scala.concurrent.{ ExecutionContext, Future }

/**
  * Discover clients for an environment that runs no discover-service (clin:
  * nothing is published there, so pennsieve.discover_service.enabled=false).
  *
  * Reads answer the way an empty Discover would: no dataset is published, a
  * search finds nothing, and unpublishing is a no-op (so deleting a dataset
  * works). Everything else (publish, revise, release, sponsorship, ...) is
  * refused with a 503 naming the reason, instead of a connection error to a
  * host that does not exist.
  */
object DisabledDiscoverClients {

  val message = "Discover is not deployed in this environment"

  /** The HTTP client behind any call the disabled clients don't answer. */
  def refusingHttpClient: HttpRequest => Future[HttpResponse] =
    _ =>
      Future.successful(
        HttpResponse(
          StatusCodes.ServiceUnavailable,
          entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, message)
        )
      )
}

class DisabledPublishClient(
  defaultWorkflowId: Long
)(implicit
  ec: ExecutionContext,
  system: ActorSystem
) extends PublishClient("discover-disabled")(
      DisabledDiscoverClients.refusingHttpClient,
      ec,
      implicitly
    ) {

  private def notPublished(
    organizationId: Int,
    datasetId: Int
  ): DatasetPublishStatus =
    DatasetPublishStatus(
      name = "",
      sourceOrganizationId = organizationId,
      sourceDatasetId = datasetId,
      publishedDatasetId = None,
      publishedVersionCount = 0,
      status = PublishStatus.NotPublished,
      lastPublishedDate = None,
      sponsorship = None,
      workflowId = defaultWorkflowId,
      latestPublishedVersion = None
    )

  override def getStatuses(
    organizationId: Int,
    headers: List[HttpHeader]
  ): EitherT[Future, Either[Throwable, HttpResponse], GetStatusesResponse] =
    EitherT.rightT[Future, Either[Throwable, HttpResponse]](
      GetStatusesResponse.OK(Vector.empty)
    )

  override def getStatus(
    organizationId: Int,
    datasetId: Int,
    headers: List[HttpHeader]
  ): EitherT[Future, Either[Throwable, HttpResponse], GetStatusResponse] =
    EitherT.rightT[Future, Either[Throwable, HttpResponse]](
      GetStatusResponse.OK(notPublished(organizationId, datasetId))
    )

  override def unpublish(
    organizationId: Int,
    datasetId: Int,
    unpublishRequest: UnpublishRequest,
    headers: List[HttpHeader]
  ): EitherT[Future, Either[Throwable, HttpResponse], UnpublishResponse] =
    EitherT.rightT[Future, Either[Throwable, HttpResponse]](
      UnpublishResponse.NoContent
    )
}

class DisabledSearchClient(
  implicit
  ec: ExecutionContext,
  system: ActorSystem
) extends SearchClient("discover-disabled")(
      DisabledDiscoverClients.refusingHttpClient,
      ec,
      implicitly
    ) {

  override def searchDatasets(
    limit: Option[Int],
    offset: Option[Int],
    query: Option[String],
    organization: Option[String],
    organizationId: Option[Int],
    tags: Option[Iterable[String]],
    embargo: Option[Boolean],
    orderBy: Option[String],
    orderDirection: Option[String],
    headers: List[HttpHeader]
  ): EitherT[Future, Either[Throwable, HttpResponse], SearchDatasetsResponse] =
    EitherT.rightT[Future, Either[Throwable, HttpResponse]](
      SearchDatasetsResponse.OK(
        DatasetsPage(limit.getOrElse(10), offset.getOrElse(0), 0L, Vector.empty)
      )
    )
}
