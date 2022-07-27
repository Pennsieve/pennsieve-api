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
import akka.http.scaladsl.model.{ HttpHeader, HttpRequest, HttpResponse }
import cats.implicits._
import cats.data.EitherT
import com.pennsieve.discover.client.definitions
import com.pennsieve.discover.client.search.{
  SearchClient,
  SearchDatasetsResponse
}
import com.pennsieve.discover.client.definitions.{
  DatasetsPage,
  PublicDatasetDto
}
import com.pennsieve.models.{ Dataset, Organization, PublishStatus, User }

import scala.concurrent.{ ExecutionContext, Future }
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

class MockSearchClient(
  implicit
  httpClient: HttpRequest => Future[HttpResponse],
  ec: ExecutionContext,
  system: ActorSystem
) extends SearchClient("mock-discover-service-host") {

  def clear: Unit = {
    publishedDatasets.clear
  }

  var publishedDatasets: ArrayBuffer[PublicDatasetDto] =
    ArrayBuffer.empty[PublicDatasetDto]

  def toMockPublicDatasetDTO(
    dataset: Dataset,
    organization: Organization,
    owner: User,
    embargo: Boolean = false
  ): PublicDatasetDto = {
    PublicDatasetDto(
      id = Random.nextInt(100),
      sourceDatasetId = Some(dataset.id),
      name = dataset.name,
      description = dataset.description.getOrElse(""),
      ownerFirstName = owner.firstName,
      ownerLastName = owner.lastName,
      ownerOrcid = "",
      organizationName = organization.name,
      organizationId = Some(organization.id),
      license = dataset.license.get,
      tags = dataset.tags.toVector,
      version = 1,
      size = 1234,
      modelCount = Vector(),
      fileCount = 1,
      recordCount = 1,
      uri = "uri",
      arn = "arn",
      status = PublishStatus.PublishSucceeded,
      doi = "doi",
      createdAt = dataset.createdAt.toOffsetDateTime,
      updatedAt = dataset.updatedAt.toOffsetDateTime,
      embargo = Some(embargo)
    )
  }

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
  ): EitherT[Future, Either[Throwable, HttpResponse], SearchDatasetsResponse] = {
    val limitOrDefault = limit.getOrElse(10)
    val offsetOrDefault = offset.getOrElse(0)

    val sortedPublishedDatasets =
      if (orderBy == Some("date")) {
        publishedDatasets.sortBy(_.createdAt)
      } else if (orderBy == Some("name")) {
        publishedDatasets.sortBy(_.name)
      } else publishedDatasets

    val orderedPublishedDatasets =
      if (orderDirection == Some("Desc")) sortedPublishedDatasets.reverse
      else sortedPublishedDatasets

    val finalResult =
      orderedPublishedDatasets.slice(offsetOrDefault, limitOrDefault + 1)
    EitherT.rightT[Future, Either[Throwable, HttpResponse]](
      SearchDatasetsResponse.OK(
        DatasetsPage(
          limitOrDefault,
          offsetOrDefault,
          finalResult.size.toLong,
          finalResult.toVector
        )
      )
    )
  }
}
