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

package com.pennsieve.api

import akka.http.scaladsl.model.headers.{ Authorization, OAuth2BearerToken }
import cats.data._
import cats.implicits._
import com.pennsieve.domain.{
  CoreError,
  NotFound,
  ServiceError,
  UnauthorizedError
}
import com.pennsieve.auth.middleware.DatasetPermission
import com.pennsieve.doi.client.definitions.CitationDto
import com.pennsieve.doi.client.doi.{ DoiClient, GetCitationsResponse }
import com.pennsieve.helpers.APIContainers.{
  InsecureAPIContainer,
  SecureContainerBuilderType
}
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.helpers.Param
import com.pennsieve.helpers.ResultHandlers._
import com.pennsieve.helpers.either.EitherTErrorHandler.implicits._
import com.pennsieve.models._
import javax.servlet.http.HttpServletRequest
import org.scalatra._
import org.scalatra.swagger.Swagger
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder

import java.time.ZonedDateTime
import scala.concurrent._

case class ExternalPublicationDTO(
  doi: Doi,
  notFound: Boolean,
  relationshipType: RelationshipType,
  citation: Option[String],
  createdAt: ZonedDateTime,
  updatedAt: ZonedDateTime
)

case class CitationResult(citation: String)

object ExternalPublicationDTO {

  def apply(
    externalPublication: ExternalPublication,
    citations: Map[Doi, Option[CitationDto]]
  ): ExternalPublicationDTO = {
    val citation = citations
      .get(externalPublication.doi)
      .flatten
      .flatMap(_.citation)

    ExternalPublicationDTO(
      doi = externalPublication.doi,
      notFound = citation.isEmpty,
      relationshipType = externalPublication.relationshipType,
      citation = citation,
      createdAt = externalPublication.createdAt,
      updatedAt = externalPublication.updatedAt
    )
  }
}

/**
  * DOIs are passed to these endpoints as query parameters instead of as part of
  * the URL because DOIs can include multiple `/` characters.
  */
class ExternalPublicationController(
  val insecureContainer: InsecureAPIContainer,
  val secureContainerBuilder: SecureContainerBuilderType,
  doiClient: DoiClient,
  asyncExecutor: ExecutionContext
)(implicit
  val swagger: Swagger
) extends ScalatraFilter
    with AuthenticatedController {

  override protected implicit def executor: ExecutionContext = asyncExecutor

  implicit val relationshipTypeParam = Param.enumParam(RelationshipType)

  override val pennsieveSwaggerTag = "ExternalPublications"

  /**
    * Rough validation that:
    *
    *  - DOI starts with a valid prefix (10.xxx)
    *  - Contains a suffix. We don't care too much about the exact format of the suffix.
    *
    * See https://support.datacite.org/docs/doi-basics and
    * https://www.crossref.org/blog/dois-and-matching-regular-expressions/ for
    * some of the complexities of DOIs and regexes
    */
  val DoiRegex = """(10.[\d]+/.+)""".r

  def validateDoi(doi: String): Either[String, String] =
    doi.trim.toLowerCase match {
      case DoiRegex(doi) => Right(doi)
      case _ => Left("Invalid DOI")
    }

  /**
    * Extract and validate a DOI from a URL query parameter.
    */
  def doiParamT(
    doiParameterName: String
  )(implicit
    request: HttpServletRequest
  ): EitherT[Future, ActionResult, Doi] =
    for {
      doi <- paramT[String](doiParameterName, validateDoi(_))
    } yield Doi(doi.trim.toLowerCase)

  /**
    * Lookup citations in `doi-service` (proxied to Crosscite)
    */
  def getCitations(
    dois: Seq[Doi],
    request: HttpServletRequest
  ): EitherT[Future, CoreError, Map[Doi, Option[CitationDto]]] =
    dois match {
      case _ if dois.isEmpty => EitherT.rightT(Map.empty)
      case _ =>
        for {
          bearerToken <- AuthenticatedController
            .getBearerToken(request)
            .leftMap(_ => UnauthorizedError("Missing bearer token"))
            .toEitherT[Future]

          response <- doiClient
            .getCitations(
              dois.map(_.value),
              List(Authorization(OAuth2BearerToken(bearerToken)))
            )
            .leftMap[CoreError] {
              case Left(e) => ServiceError(e.getMessage)
              case Right(resp) => ServiceError(resp.toString)
            }
            .leftMap {
              case e =>
                logger.error("Error getting citations", e)
                e
            }

          citations <- EitherT.fromEither[Future] {
            response match {
              case GetCitationsResponse.MultiStatus(citations) =>
                Right(
                  dois
                    .map(doi => doi -> citations.find(_.doi == doi.value))
                    .toMap
                )
              case GetCitationsResponse.InternalServerError(e) =>
                logger.error("Internal error while getting citations", e)
                Left(ServiceError(e): CoreError)
            }
          }
        } yield citations
    }

  val getExternalPublicationsOperation
    : OperationBuilder = (apiOperation[List[ExternalPublicationDTO]](
    "getExternalPublications"
  )
    summary "return external publications linked to this dataset"
    parameters (
      pathParam[String]("id").description("data set id")
    ))

  get(
    "/datasets/:id/external-publications",
    operation(getExternalPublicationsOperation)
  ) {

    new AsyncResult {
      val result: EitherT[Future, ActionResult, Seq[ExternalPublicationDTO]] =
        for {
          datasetId <- paramT[String]("id")
          secureContainer <- getSecureContainer

          dataset <- secureContainer.datasetManager
            .getByNodeId(datasetId)
            .orNotFound

          _ <- secureContainer
            .authorizeDataset(Set(DatasetPermission.ViewExternalPublications))(
              dataset
            )
            .coreErrorToActionResult

          externalPublications <- secureContainer.externalPublicationManager
            .get(dataset)
            .coreErrorToActionResult

          citations <- getCitations(externalPublications.map(_.doi), request).coreErrorToActionResult

        } yield externalPublications.map(ExternalPublicationDTO(_, citations))

      override val is = result.value.map(OkResult(_))
    }
  }

  val getExternalPublicationsCitationOperation
    : OperationBuilder = (apiOperation[Option[String]](
    "get citation for external publication"
  )
    summary "get citation for external publication"
    parameters (
      queryParam[Boolean]("doi").required
        .description("the DOI of the publication")
      ))

  get(
    "/datasets/external-publications/citation",
    operation(getExternalPublicationsCitationOperation)
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, CitationResult] =
        for {
          doi <- doiParamT("doi")
          citationMap <- getCitations(List(doi), request).coreErrorToActionResult
          citation <- EitherT
            .fromEither[Future] {
              citationMap
                .get(doi)
                .flatten
                .flatMap(_.citation) match {
                case None => Left(NotFound(doi.value): CoreError)
                case Some(citationString) =>
                  Right(CitationResult(citationString))
              }
            }
            .coreErrorToActionResult
        } yield citation

      override val is = result.value.map(OkResult(_))
    }
  }

  val upsertExternalPublicationOperation
    : OperationBuilder = (apiOperation[ExternalPublicationDTO](
    "upsertExternalPublication"
  )
    summary "link an external publication to this dataset"
    parameters (
      pathParam[String]("id").description("data set id"),
      queryParam[Boolean]("doi").required
        .description("the DOI of the publication"),
      queryParam[RelationshipType]("relationshipType")
        .description("the type of relation of the publication")
  ))
  put(
    "/datasets/:id/external-publications",
    operation(upsertExternalPublicationOperation)
  ) {

    new AsyncResult {
      val result: EitherT[Future, ActionResult, ExternalPublicationDTO] =
        for {
          datasetId <- paramT[String]("id")
          doi <- doiParamT("doi")
          relationshipType <- paramT[RelationshipType](
            "relationshipType",
            RelationshipType.References
          )

          secureContainer <- getSecureContainer

          dataset <- secureContainer.datasetManager
            .getByNodeId(datasetId)
            .orNotFound

          _ <- secureContainer
            .authorizeDataset(
              Set(DatasetPermission.ManageExternalPublications)
            )(dataset)
            .coreErrorToActionResult

          externalPublication <- secureContainer.externalPublicationManager
            .createOrUpdate(dataset, doi, relationshipType)
            .coreErrorToActionResult

          _ <- secureContainer.changelogManager
            .logEvent(
              dataset,
              ChangelogEventDetail
                .AddExternalPublication(doi, relationshipType)
            )
            .coreErrorToActionResult

          _ <- secureContainer.datasetManager
            .touchUpdatedAtTimestamp(dataset)
            .coreErrorToActionResult

          citations <- getCitations(List(doi), request).coreErrorToActionResult

        } yield ExternalPublicationDTO(externalPublication, citations)

      override val is = result.value.map(OkResult(_))
    }
  }

  val deleteExternalPublicationOperation
    : OperationBuilder = (apiOperation[Unit]("deleteExternalPublication")
    summary "delete an external publication linked to this dataset"
    parameters (
      pathParam[String]("id").description("data set id"),
      queryParam[Boolean]("doi").description("the DOI of the publication"),
      queryParam[Boolean]("relationshipType")
        .description("the type of relation of the publication")
  ))

  delete(
    "/datasets/:id/external-publications",
    operation(getExternalPublicationsOperation)
  ) {

    new AsyncResult {
      val result: EitherT[Future, ActionResult, Unit] =
        for {
          datasetId <- paramT[String]("id")
          doi <- doiParamT("doi")
          relationshipType <- paramT[RelationshipType]("relationshipType")

          secureContainer <- getSecureContainer

          dataset <- secureContainer.datasetManager
            .getByNodeId(datasetId)
            .orNotFound

          _ <- secureContainer
            .authorizeDataset(
              Set(DatasetPermission.ManageExternalPublications)
            )(dataset)
            .coreErrorToActionResult

          _ <- secureContainer.externalPublicationManager
            .delete(dataset, doi, relationshipType)
            .coreErrorToActionResult

          _ <- secureContainer.changelogManager
            .logEvent(
              dataset,
              ChangelogEventDetail
                .RemoveExternalPublication(doi, relationshipType)
            )
            .coreErrorToActionResult

          _ <- secureContainer.datasetManager
            .touchUpdatedAtTimestamp(dataset)
            .coreErrorToActionResult

        } yield ()

      override val is = result.value.map(NoContentResult(_))
    }
  }
}
