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
import akka.http.scaladsl.model.FormData
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.unmarshalling.Unmarshal
import com.pennsieve.models.OrcidAuthorization
import akka.http.scaladsl.HttpExt
import akka.http.scaladsl.model.headers.{ Authorization, OAuth2BearerToken }
import com.pennsieve.domain.PredicateError
import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._
import io.circe.parser.decode

import scala.concurrent.{ ExecutionContext, Future }

trait OrcidClient {

  def getToken(authorizationCode: String): Future[OrcidAuthorization]
  def verifyOrcid(orcid: Option[String]): Future[Boolean]
}

case class OrcidClientConfig(
  clientId: String,
  clientSecret: String,
  tokenUrl: String,
  redirectUrl: String,
  readPublicToken: String,
  getRecordBaseUrl: String
)

object OrcidClientConfig {

  def apply(config: Config): OrcidClientConfig = {
    OrcidClientConfig(
      clientId = config.as[String]("orcidClient.clientId"),
      clientSecret = config.as[String]("orcidClient.clientSecret"),
      tokenUrl = config.as[String]("orcidClient.tokenUrl"),
      redirectUrl = config.as[String]("orcidClient.redirectUrl"),
      readPublicToken = config.as[String]("orcidClient.readPublicToken"),
      getRecordBaseUrl = config.as[String]("orcidClient.getRecordBaseUrl")
    )
  }
}

class OrcidClientImpl(
  httpClient: HttpExt,
  orcidClientConfig: OrcidClientConfig
)(implicit
  executionContext: ExecutionContext,
  system: ActorSystem
) extends OrcidClient {

  def createBody(authorizationCode: String, orcidConfig: OrcidClientConfig) =
    FormData(
      "client_id" -> orcidConfig.clientId,
      "client_secret" -> orcidConfig.clientSecret,
      "grant_type" -> "authorization_code",
      "redirect_uri" -> orcidConfig.redirectUrl,
      "code" -> authorizationCode
    )

  override def getToken(
    authorizationCode: String
  ): Future[OrcidAuthorization] = {
    val tokenRequest = HttpRequest(
      method = HttpMethods.POST,
      uri = orcidClientConfig.tokenUrl,
      entity = createBody(authorizationCode, orcidClientConfig).toEntity,
      headers = List(Accept(MediaTypes.`application/json`))
    )
    println(s"orcidClientConfig : $orcidClientConfig")
    println(s"authorizationCode : $authorizationCode")
    httpClient
      .singleRequest(tokenRequest)
      .flatMap {
        case HttpResponse(StatusCodes.OK, _, entity, _) =>
          Unmarshal(entity)
            .to[String]
            .map(decode[OrcidAuthorization](_))
            .flatMap(_.fold(Future.failed, Future.successful))
        case error =>
          Unmarshal(error.entity)
            .to[String]
            .flatMap(
              failureMessage => Future.failed(new Throwable(failureMessage))
            )
      }
  }

  override def verifyOrcid(orcid: Option[String]): Future[Boolean] = {

    orcid match {
      case None => Future(true)
      case Some(orcid) =>
        val getRecordRequest = HttpRequest(
          method = HttpMethods.GET,
          uri = orcidClientConfig.getRecordBaseUrl + orcid + "/record",
          headers = List(
            Accept(MediaTypes.`application/json`),
            Authorization(OAuth2BearerToken(orcidClientConfig.readPublicToken))
          )
        )

        httpClient
          .singleRequest(getRecordRequest)
          .flatMap {
            case HttpResponse(StatusCodes.OK, _, _, _) =>
              Future.successful(true)
            case error =>
              Future.failed(PredicateError("ORCID not found"))
          }
    }

  }

}
