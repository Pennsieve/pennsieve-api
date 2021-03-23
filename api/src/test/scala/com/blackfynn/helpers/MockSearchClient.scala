package com.pennsieve.helpers

import akka.http.scaladsl.model.{ HttpHeader, HttpRequest, HttpResponse }
import akka.stream.Materializer
import cats.implicits._
import cats.data.EitherT
import com.pennsieve.discover.client.definitions
import com.pennsieve.discover.client.search.{
  SearchClient,
  SearchDatasetsResponse
}
import com.pennsieve.discover.client.definitions.{
  DatasetsPage,
  PublicDatasetDTO
}
import com.pennsieve.models.{ Dataset, Organization, PublishStatus, User }

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

class MockSearchClient(
  httpClient: HttpRequest => Future[HttpResponse],
  ec: ExecutionContext,
  mat: Materializer
) extends SearchClient("mock-discover-service-host")(httpClient, ec, mat) {

  def clear: Unit = {
    publishedDatasets.clear
  }

  var publishedDatasets: ArrayBuffer[PublicDatasetDTO] =
    ArrayBuffer.empty[PublicDatasetDTO]

  def toMockPublicDatasetDTO(
    dataset: Dataset,
    organization: Organization,
    owner: User,
    embargo: Boolean = false
  ): PublicDatasetDTO = {
    PublicDatasetDTO(
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
      tags = dataset.tags.toIndexedSeq,
      version = 1,
      size = 1234,
      modelCount = IndexedSeq(),
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
          finalResult.toIndexedSeq
        )
      )
    )
  }
}
