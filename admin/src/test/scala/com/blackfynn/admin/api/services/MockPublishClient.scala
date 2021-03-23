package com.pennsieve.admin.api.services

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
import scala.concurrent.ExecutionContext.Implicits.global

class MockPublishClient(
  httpClient: HttpRequest => Future[HttpResponse],
  ec: ExecutionContext,
  mat: Materializer
) extends PublishClient("mock-discover-service-host")(httpClient, ec, mat) {

  override def getStatuses(
    organizationId: Int,
    headers: List[HttpHeader]
  ): EitherT[Future, Either[Throwable, HttpResponse], GetStatusesResponse] = {
    EitherT.rightT[Future, Either[Throwable, HttpResponse]](
      GetStatusesResponse.OK(
        IndexedSeq(
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
