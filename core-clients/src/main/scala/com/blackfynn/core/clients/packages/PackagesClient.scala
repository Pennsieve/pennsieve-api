// Copyright (c) 2019 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.core.clients.packages

import akka.http.scaladsl.marshalling.{ Marshal, ToEntityMarshaller }
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.{ Unmarshal, Unmarshaller }
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import cats.data.EitherT
import cats.syntax.either._
import cats.instances.future._
import com.blackfynn.core.clients.AkkaHttpImplicits._
import com.blackfynn.models.NodeId

import scala.concurrent.{ ExecutionContext, Future }

object PackagesClient {

  def apply(
    host: String
  )(implicit
    httpClient: HttpRequest => Future[HttpResponse],
    executionContext: ExecutionContext,
    materializer: Materializer
  ): PackagesClient =
    new PackagesClient(host = host)(
      httpClient = httpClient,
      executionContext = executionContext,
      materializer = materializer
    )

  def httpClient(
    httpClient: HttpRequest => Future[HttpResponse],
    host: String
  )(implicit
    executionContext: ExecutionContext,
    materializer: Materializer
  ): PackagesClient =
    new PackagesClient(host = host)(
      httpClient = httpClient,
      executionContext = executionContext,
      materializer = materializer
    )
}

class PackagesClient(
  host: String
)(implicit
  httpClient: HttpRequest => Future[HttpResponse],
  executionContext: ExecutionContext,
  materializer: Materializer
) {

  val basePath: String = ""

  private[this] def makeRequest[T: ToEntityMarshaller](
    method: HttpMethod,
    uri: Uri,
    headers: scala.collection.immutable.Seq[HttpHeader],
    entity: T,
    protocol: HttpProtocol = HttpProtocols.`HTTP/1.1`
  ): EitherT[Future, Either[Throwable, HttpResponse], HttpRequest] = {
    EitherT(
      Marshal(entity)
        .to[RequestEntity]
        .map[Either[Either[Throwable, HttpResponse], HttpRequest]] { entity =>
          Right(
            HttpRequest(
              method = method,
              uri = uri,
              headers = headers,
              entity = entity,
              protocol = protocol
            )
          )
        }
        .recover {
          case t =>
            Left(Left(t))
        }
    )
  }

  val errorStringDecoder: Unmarshaller[HttpEntity, String] = {
    structuredJsonEntityUnmarshaller.flatMap(
      _ =>
        _ =>
          json =>
            io.circe
              .Decoder[String]
              .decodeJson(json)
              .fold(FastFuture.failed, FastFuture.successful)
    )
  }

  def uploadComplete(
    packageNodeIdOrIntId: Either[NodeId, Int],
    userId: Int,
    headers: List[HttpHeader] = Nil
  ): EitherT[Future, Either[Throwable, HttpResponse], UploadCompleteResponse] = {
    val allHeaders = headers ++ scala.collection.immutable
      .Seq[Option[HttpHeader]]()
      .flatten

    val id =
      packageNodeIdOrIntId.bimap(_.toString, _.toString).valueOr(identity)

    val uri: Uri = Uri(host + basePath + "/packages/" + id + "/upload-complete")
      .withQuery(Uri.Query("user_id" -> userId.toString))

    makeRequest[String](HttpMethods.PUT, uri, allHeaders, "").flatMap(
      req =>
        EitherT(
          httpClient(req)
            .flatMap(
              resp =>
                resp.status match {
                  case StatusCodes.BadRequest =>
                    Unmarshal(resp.entity)
                      .to[String](errorStringDecoder, implicitly, implicitly)
                      .map(x => Right(UploadCompleteResponse.BadRequest(x)))
                  case StatusCodes.NotFound =>
                    resp
                      .discardEntityBytes()
                      .future
                      .map(_ => Right(UploadCompleteResponse.NotFound))
                  case StatusCodes.InternalServerError =>
                    Unmarshal(resp.entity)
                      .to[String](errorStringDecoder, implicitly, implicitly)
                      .map(
                        x =>
                          Right(UploadCompleteResponse.InternalServerError(x))
                      )
                  case StatusCodes.Forbidden =>
                    resp
                      .discardEntityBytes()
                      .future
                      .map(_ => Right(UploadCompleteResponse.Forbidden))
                  case StatusCodes.OK =>
                    resp
                      .discardEntityBytes()
                      .future
                      .map(_ => Right(UploadCompleteResponse.OK))
                  case _ =>
                    FastFuture.successful(Left(Right(resp)))
                }
            )
            .recover({
              case e: Throwable =>
                Left(Left(e))
            })
        )
    )
  }
}

sealed abstract class UploadCompleteResponse {
  def fold[A](
    handleBadRequest: String => A,
    handleNotFound: => A,
    handleInternalServerError: String => A,
    handleForbidden: => A,
    handleOK: => A
  ): A = this match {
    case x: UploadCompleteResponse.BadRequest =>
      handleBadRequest(x.value)
    case UploadCompleteResponse.NotFound =>
      handleNotFound
    case x: UploadCompleteResponse.InternalServerError =>
      handleInternalServerError(x.value)
    case UploadCompleteResponse.Forbidden =>
      handleForbidden
    case UploadCompleteResponse.OK =>
      handleOK
  }
}
object UploadCompleteResponse {
  case class BadRequest(value: String) extends UploadCompleteResponse
  case object NotFound extends UploadCompleteResponse
  case class InternalServerError(value: String) extends UploadCompleteResponse
  case object Forbidden extends UploadCompleteResponse
  case object OK extends UploadCompleteResponse
}
