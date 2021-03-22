package com.blackfynn.clients

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{
  headers,
  HttpMethods,
  HttpRequest,
  HttpResponse,
  Uri
}
import akka.stream.ActorMaterializer
import cats.data.EitherT
import cats.instances.future._
import cats.syntax.either._
import com.blackfynn.auth.middleware.Jwt
import com.blackfynn.utilities.Container
import com.blackfynn.models.FileHash
import com.typesafe.scalalogging.StrictLogging
import io.circe.Decoder
import io.circe.parser.decode
import io.circe.generic.semiauto.deriveDecoder

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.DurationInt
import scala.collection.mutable.ArrayBuffer

trait UploadServiceClient {
  val uploadServiceHost: String

  implicit val system: ActorSystem
  implicit val materializer: ActorMaterializer
  implicit val ec: ExecutionContext

  def getFileHash(
    importId: String,
    fileName: String,
    userId: Int,
    token: Jwt.Token
  ): EitherT[Future, Throwable, FileHash]
}

class LocalUploadServiceClient(
)(implicit
  override val system: ActorSystem,
  override val materializer: ActorMaterializer,
  override val ec: ExecutionContext
) extends UploadServiceClient {
  override val uploadServiceHost = "test-upload-service-url"

  val fileHashRequests: ArrayBuffer[(String, String, Int)] =
    ArrayBuffer.empty[(String, String, Int)]

  def getFileHash(
    importId: String,
    fileName: String,
    userId: Int,
    token: Jwt.Token
  ): EitherT[Future, Throwable, FileHash] = {
    fileHashRequests += ((importId, fileName, userId))
    EitherT.rightT[Future, Throwable](FileHash("hash"))
  }
}

class UploadServiceClientImpl(
  override val uploadServiceHost: String
)(implicit
  override val system: ActorSystem,
  override val materializer: ActorMaterializer,
  override val ec: ExecutionContext
) extends UploadServiceClient
    with StrictLogging {

  def makeRequest(
    req: HttpRequest,
    token: Jwt.Token
  ): EitherT[Future, Throwable, HttpResponse] = {
    EitherT(
      Http()
        .singleRequest(
          req.addHeader(
            headers.Authorization(headers.OAuth2BearerToken(token.value))
          )
        )
        .map(
          resp =>
            if (resp.status.isSuccess) {
              resp.asRight
            } else {
              val error = ServerError(
                s"Error communicating with the upload service: ${resp.toString}"
              )
              logger.error(error.message)
              Left[Throwable, HttpResponse](error)
            }
        )
    )
  }

  def getFileHash(
    importId: String,
    fileName: String,
    userId: Int,
    token: Jwt.Token
  ): EitherT[Future, Throwable, FileHash] =
    for {
      response <- makeRequest(
        HttpRequest(
          HttpMethods.GET,
          Uri(s"${uploadServiceHost}/hash/id/${importId}")
            .withQuery(
              Uri
                .Query(Map("fileName" -> fileName, "userId" -> userId.toString))
            )
        ),
        token
      )
      entity <- EitherT.right(response.entity.toStrict(5 seconds))
      fileHash <- decode[FileHash](entity.data.utf8String).toEitherT
        .leftMap(e => new Throwable(e))
    } yield fileHash

}

trait UploadServiceContainer { self: Container =>
  implicit val system: ActorSystem
  implicit val ec: ExecutionContext
  implicit def materializer: ActorMaterializer

  val uploadServiceHost: String
  val uploadServiceClient: UploadServiceClient
}

trait UploadServiceContainerImpl extends UploadServiceContainer {
  self: Container =>
  implicit val system: ActorSystem
  implicit val ec: ExecutionContext
  override implicit val materializer: ActorMaterializer

  val uploadServiceHost: String
  override lazy val uploadServiceClient: UploadServiceClient =
    new UploadServiceClientImpl(uploadServiceHost)
}

trait LocalUploadServiceContainer extends UploadServiceContainer {
  self: Container =>
  implicit val system: ActorSystem
  implicit val ec: ExecutionContext
  implicit val materializer: ActorMaterializer

  val uploadServiceHost: String
  override val uploadServiceClient: UploadServiceClient =
    new LocalUploadServiceClient
}
