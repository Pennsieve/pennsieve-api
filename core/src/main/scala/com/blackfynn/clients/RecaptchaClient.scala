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

package com.blackfynn.clients

import akka.actor.ActorSystem
import akka.http.scaladsl.HttpExt
import akka.http.scaladsl.model.{
  ContentType,
  HttpEntity,
  HttpMethods,
  HttpRequest,
  HttpResponse,
  MediaTypes,
  StatusCodes
}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.util.ByteString
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.parser._
import io.circe.syntax._
import io.circe.{ Decoder, Encoder }

import scala.concurrent.{ ExecutionContext, Future }

case class RecaptchaVerifyRequest(secret: String, response: String)

object RecaptchaVerifyRequest {
  implicit val encoder: Encoder[RecaptchaVerifyRequest] =
    deriveEncoder[RecaptchaVerifyRequest]
}

case class RecaptchaResponse(success: Boolean)

object RecaptchaResponse {
  implicit val decoder: Decoder[RecaptchaResponse] =
    deriveDecoder[RecaptchaResponse]
}

trait AntiSpamChallengeClient {
  def verifyToken(responseToken: String): Future[Boolean]
}

class RecaptchaClient(
  httpClient: HttpExt,
  verifyUrl: String,
  secretKey: String
)(implicit
  executionContext: ExecutionContext,
  system: ActorSystem
) extends AntiSpamChallengeClient {
  override def verifyToken(responseToken: String): Future[Boolean] = {
    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = verifyUrl,
      entity =
        HttpEntity
          .Strict(
            ContentType(MediaTypes.`application/json`),
            ByteString(
              RecaptchaVerifyRequest(
                secret = secretKey,
                response = responseToken
              ).asJson.noSpaces
            )
          )
    )
    for {
      response <- httpClient.singleRequest(request)
      shortUrl <- response match {
        case HttpResponse(StatusCodes.OK | StatusCodes.Created, _, entity, _) =>
          Unmarshal(entity)
            .to[String]
            .map(decode[RecaptchaResponse](_))
            .flatMap(_.fold(Future.failed, Future.successful))
            .map(_.success)
        case error =>
          Unmarshal(error.entity)
            .to[String]
            .flatMap(msg => Future.failed(new Exception(msg)))
      }
    } yield shortUrl
  }
}
