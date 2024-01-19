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
import com.pennsieve.models.{
  OrcidAuthorization,
  OrcidExternalId,
  OrcidTitle,
  OrcidTitleValue,
  OrcidWork,
  OricdExternalIds
}
import akka.http.scaladsl.HttpExt
import akka.http.scaladsl.model.headers.{ Authorization, OAuth2BearerToken }
import com.pennsieve.domain.PredicateError
import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._
import io.circe.parser.decode
import io.circe.syntax._

import scala.concurrent.{ ExecutionContext, Future }

case class OrcidWorkPublishing(
  orcidId: String,
  accessToken: String,
  orcidPutCode: Option[String],
  publishedDatasetId: Int,
  title: String,
  subTitle: String,
  doi: Option[String]
)

trait OrcidClient {

  def getToken(authorizationCode: String): Future[OrcidAuthorization]
  def verifyOrcid(orcid: Option[String]): Future[Boolean]
  def publishWork(work: OrcidWorkPublishing): Future[Option[String]]
}

case class OrcidClientConfig(
  clientId: String,
  clientSecret: String,
  tokenUrl: String,
  redirectUrl: String,
  readPublicToken: String,
  getRecordBaseUrl: String,
  updateProfileBaseUrl: String
)

object OrcidClientConfig {

  def apply(config: Config): OrcidClientConfig = {
    OrcidClientConfig(
      clientId = config.as[String]("orcidClient.clientId"),
      clientSecret = config.as[String]("orcidClient.clientSecret"),
      tokenUrl = config.as[String]("orcidClient.tokenUrl"),
      redirectUrl = config.as[String]("orcidClient.redirectUrl"),
      readPublicToken = config.as[String]("orcidClient.readPublicToken"),
      getRecordBaseUrl = config.as[String]("orcidClient.getRecordBaseUrl"),
      updateProfileBaseUrl =
        config.as[String]("orcidClient.updateProfileBaseUrl")
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

  override def publishWork(
    work: OrcidWorkPublishing
  ): Future[Option[String]] = {
    import MediaTypes._
    import HttpCharsets._

    val workRequest = OrcidWork(
      title = OrcidTitle(
        title = OrcidTitleValue(value = work.title),
        subtitle = OrcidTitleValue(value = work.subTitle)
      ),
      `type` = "data-set",
      externalIds =
        OricdExternalIds(externalId = work.doi match {
          case Some(doi) =>
            Seq(
              OrcidExternalId(
                externalIdType = "doi",
                externalIdValue = doi,
                externalIdUrl =
                  OrcidTitleValue(value = s"https://doi.org/${doi}"),
                externalIdRelationship = "self"
              )
            )
          case None => List.empty[OrcidExternalId]
        }),
      url = OrcidTitleValue(
        value =
          s"https://discover.pennsieve.io/datasets/${work.publishedDatasetId}"
      )
    )

    val request = work.orcidPutCode match {
      case Some(putCode: String) =>
        HttpRequest(
          method = HttpMethods.PUT,
          uri = orcidClientConfig.updateProfileBaseUrl + work.orcidId + "/work" + "/" + putCode,
          headers = List(
            Accept(MediaTypes.`application/json`),
            Authorization(OAuth2BearerToken(work.accessToken))
          ),
          entity = HttpEntity(`application/json`, workRequest.asJson.toString)
        )
      case None =>
        HttpRequest(
          method = HttpMethods.POST,
          uri = orcidClientConfig.updateProfileBaseUrl + work.orcidId + "/work",
          headers = List(
            Accept(MediaTypes.`application/json`),
            Authorization(OAuth2BearerToken(work.accessToken))
          ),
          entity = HttpEntity(`application/json`, workRequest.asJson.toString)
        )
    }

    httpClient
      .singleRequest(request)
      .flatMap {
        case HttpResponse(StatusCodes.OK, headers, _, _) =>
          val headersMap = headers
            .map(header => header.name().toLowerCase -> header.value())
            .toMap
          val putCode = headersMap.get("location") match {
            case Some(location: String) =>
              Some(location.split("/").last)
            case None => None
          }
          Future.successful(putCode)
        case _ =>
          Future.failed(PredicateError("ORCID not found"))
      }
  }

}
