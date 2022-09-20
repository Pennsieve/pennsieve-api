package com.pennsieve.helpers

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import cats.data.EitherT
import com.pennsieve.discover.client.file.{FileClient, GetFileFromSourcePackageIdResponse}

import scala.concurrent.{ExecutionContext, Future}

class MockDiscoverFileClient(
  implicit
  httpClient: HttpRequest => Future[HttpResponse],
  ec: ExecutionContext,
  system: ActorSystem
) extends FileClient(host = "mock-discover-service-host") {
  override def getFileFromSourcePackageId(
    sourcePackageId: String,
    limit: Option[Int],
    offset: Option[Int],
    headers: List[HttpHeader]): EitherT[Future, Either[Throwable, HttpResponse], GetFileFromSourcePackageIdResponse] = {
    super.getFileFromSourcePackageId(sourcePackageId, limit, offset, headers)
  }
}
