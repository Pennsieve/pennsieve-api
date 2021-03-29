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

package com.pennsieve.clients

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{
  headers,
  HttpMethods,
  HttpRequest,
  HttpResponse,
  Uri
}
import cats.data.EitherT
import cats.instances.future._
import cats.syntax.either._
import com.pennsieve.auth.middleware.Jwt
import com.pennsieve.utilities.Container
import com.pennsieve.models.FileHash
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

  val uploadServiceHost: String
  val uploadServiceClient: UploadServiceClient
}

trait UploadServiceContainerImpl extends UploadServiceContainer {
  self: Container =>
  implicit val system: ActorSystem
  implicit val ec: ExecutionContext

  val uploadServiceHost: String
  override lazy val uploadServiceClient: UploadServiceClient =
    new UploadServiceClientImpl(uploadServiceHost)
}

trait LocalUploadServiceContainer extends UploadServiceContainer {
  self: Container =>
  implicit val system: ActorSystem
  implicit val ec: ExecutionContext

  val uploadServiceHost: String
  override val uploadServiceClient: UploadServiceClient =
    new LocalUploadServiceClient
}
