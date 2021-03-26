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

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ HttpHeader, HttpRequest, HttpResponse }
import cats.data.EitherT
import cats.implicits._
import com.pennsieve.doi.client.definitions._
import com.pennsieve.doi.client.doi._
import com.pennsieve.doi.models._
import io.circe.syntax._

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Random

class MockDoiClient(
  implicit
  httpClient: HttpRequest => Future[HttpResponse],
  ec: ExecutionContext,
  system: ActorSystem
) extends DoiClient("mock-doi-service-host") {

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
