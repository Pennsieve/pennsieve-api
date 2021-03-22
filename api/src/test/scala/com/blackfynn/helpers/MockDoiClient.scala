package com.blackfynn.helpers

import java.time.{ OffsetDateTime, ZoneOffset }

import akka.http.scaladsl.model.{ HttpHeader, HttpRequest, HttpResponse }
import akka.stream.Materializer
import cats.data.EitherT
import cats.implicits._
import com.blackfynn.doi.client.definitions._
import com.blackfynn.doi.client.doi._
import com.blackfynn.doi.models._
import io.circe.syntax._

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Random

class MockDoiClient(
  httpClient: HttpRequest => Future[HttpResponse],
  ec: ExecutionContext,
  mat: Materializer
) extends DoiClient("mock-doi-service-host")(httpClient, ec, mat) {

  val doisCreated: ArrayBuffer[DoiDTO] =
    ArrayBuffer.empty[DoiDTO]

  def createMockDoi(
    organizationId: Int,
    datasetId: Int,
    body: CreateDraftDoiRequest
  ): DoiDTO = {
    val doi = s"bfPrefix/${body.suffix.getOrElse(randomString())}"
    val dto = DoiDTO(
      organizationId = organizationId,
      datasetId = datasetId,
      doi = doi,
      title = body.title,
      url = Some(s"https://doi.org/$doi"),
      createdAt = Some("4/18/2019"),
      publicationYear = body.publicationYear,
      state = Some(DoiState.Draft)
    )
    doisCreated += dto
    dto
  }

  def getMockDoi(organizationId: Int, datasetId: Int): Option[DoiDTO] = {
    doisCreated
      .filter(_.organizationId === organizationId)
      .find(_.datasetId === datasetId)
  }

  override def createDraftDoi(
    organizationId: Int,
    datasetId: Int,
    body: CreateDraftDoiRequest,
    headers: List[HttpHeader]
  ): EitherT[Future, Either[Throwable, HttpResponse], CreateDraftDoiResponse] = {
    EitherT.rightT[Future, Either[Throwable, HttpResponse]](
      CreateDraftDoiResponse
        .Created(createMockDoi(organizationId, datasetId, body).asJson)
    )
  }

  override def getLatestDoi(
    organizationId: Int,
    datasetId: Int,
    headers: List[HttpHeader]
  ): EitherT[Future, Either[Throwable, HttpResponse], GetLatestDoiResponse] = {
    val result = getMockDoi(organizationId, datasetId) match {
      case Some(doi) => GetLatestDoiResponse.OK(doi.asJson)
      case None =>
        GetLatestDoiResponse.NotFound(
          s"doi for organizationId=$organizationId datasetId=$datasetId"
        )
    }

    EitherT.rightT[Future, Either[Throwable, HttpResponse]](result)
  }

  override def getCitations(
    doi: Iterable[String],
    headers: List[HttpHeader]
  ): EitherT[Future, Either[Throwable, HttpResponse], GetCitationsResponse] = {

    EitherT.rightT[Future, Either[Throwable, HttpResponse]](
      GetCitationsResponse.MultiStatus(
        doi
          .map(
            doi =>
              doi match {
                case "10.21397/jili-ef5r" => // Hardcoded for tests
                  CitationDTO(
                    status = 200,
                    doi = doi,
                    citation = Some("A citation")
                  )

                case _ => CitationDTO(status = 404, doi = doi, citation = None)
              }
          )
          .toIndexedSeq
      )
    )
  }

  def randomString(length: Int = 8): String =
    Random.alphanumeric take length mkString
}
