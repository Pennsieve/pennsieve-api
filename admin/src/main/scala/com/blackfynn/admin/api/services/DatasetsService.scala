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

package com.pennsieve.admin.api.services
import akka.http.scaladsl.model.StatusCodes.{
  BadRequest,
  Forbidden,
  InternalServerError,
  NoContent,
  NotFound,
  Unauthorized
}
import akka.http.scaladsl.model.{ HttpResponse, StatusCode }
import akka.http.scaladsl.model.headers.{ Authorization, OAuth2BearerToken }
import akka.http.scaladsl.server.Directives.path
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import cats.implicits._
import com.pennsieve.admin.api.Router.SecureResourceContainer
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import com.pennsieve.akka.http.RouteService
import com.pennsieve.discover.client.publish.{
  GetStatusesResponse,
  PublishClient,
  RemoveDatasetSponsorResponse,
  SponsorDatasetResponse
}
import io.swagger.annotations.{
  Api,
  ApiImplicitParam,
  ApiImplicitParams,
  ApiOperation,
  ApiResponse,
  ApiResponses,
  Authorization => SwaggerAuthorization
}
import javax.ws.rs.Path
import akka.http.scaladsl.server.Directives.{ entity, _ }
import cats.data.EitherT
import com.pennsieve.admin.api.Settings
import com.pennsieve.auth.middleware.Jwt
import com.pennsieve.core.utilities.JwtAuthenticator
import com.pennsieve.discover.client.definitions.{
  DatasetPublishStatus,
  SponsorshipRequest,
  SponsorshipResponse
}
import com.pennsieve.domain.{
  CoreError,
  OrganizationPermissionError,
  PredicateError,
  ServiceError,
  UnauthorizedError,
  NotFound => BfNotFound
}

import scala.concurrent.duration._
import com.pennsieve.models.NodeCodes.{ nodeIdIsA, organizationCode }
import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

import scala.concurrent.{ ExecutionContext, Future }

@Path("/organizations")
@Api(
  value = "Datasets",
  produces = "application/json",
  authorizations = Array(new SwaggerAuthorization(value = "Bearer"))
)
class DatasetsService(
  container: SecureResourceContainer,
  publishClient: PublishClient
)(implicit
  ec: ExecutionContext,
  mat: ActorMaterializer
) extends RouteService {

  implicit val jwtConfig: Jwt.Config = new Jwt.Config {
    val key: String = Settings.jwtKey
  }

  override def routes: Route =
    pathPrefix("organizations") {
      getDatasets ~ sponsorDataset ~ removeDatasetSponsor
    }

  @Path("/{organizationId}/datasets")
  @ApiOperation(
    httpMethod = "GET",
    response = classOf[Seq[DatasetPublishStatus]],
    value = "Returns a list of all published datasets for the organization"
  )
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(
        name = "organizationId",
        required = true,
        dataType = "string",
        paramType = "path",
        value = "Organization ID"
      )
    )
  )
  @ApiResponses(
    Array(
      new ApiResponse(code = 403, message = "forbidden"),
      new ApiResponse(code = 401, message = "unauthorized")
    )
  )
  def getDatasets: Route =
    (path(Segment / "datasets") & get) { organizationId =>
      val token =
        JwtAuthenticator.generateServiceToken(1.minute, organizationId.toInt)

      val tokenHeader = Authorization(OAuth2BearerToken(token.value))

      val clientResponse
        : EitherT[Future, Either[Throwable, HttpResponse], GetStatusesResponse] =
        publishClient.getStatuses(organizationId.toInt, List(tokenHeader))

      val toCoreError: EitherT[Future, CoreError, Seq[DatasetPublishStatus]] =
        toServiceError(clientResponse).flatMap {
          _.fold[EitherT[Future, CoreError, Seq[DatasetPublishStatus]]](
            handleOK = response => EitherT.pure(response),
            handleInternalServerError = msg => EitherT.leftT(ServiceError(msg)),
            handleForbidden = _ =>
              EitherT.leftT(
                OrganizationPermissionError(
                  container.user.nodeId,
                  organizationId.toInt
                )
              ),
            handleUnauthorized = EitherT
              .leftT(UnauthorizedError("getPublishStatuses unauthorized"))
          )
        }

      onSuccess(toCoreError.value) {
        case Right(result) => {
          complete(result)
        }
        case Left(error) =>
          complete {
            HttpResponse(
              error match {
                case _: OrganizationPermissionError => Forbidden
                case _: UnauthorizedError => Unauthorized
                case _ => InternalServerError
              },
              entity =
                s"failed to retrieve datasets with error: ${error.getMessage}"
            )
          }
      }

    }

  @Path("{organizationId}/datasets/{datasetId}/sponsor")
  @ApiOperation(
    httpMethod = "POST",
    response = classOf[SponsorDatasetResponse],
    value =
      "Returns the internal dataset ID and sponsorship ID if the sponsor request is successful"
  )
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(
        name = "organizationId",
        required = true,
        dataType = "string",
        paramType = "path",
        value = "Organization ID"
      ),
      new ApiImplicitParam(
        name = "datasetId",
        required = true,
        dataType = "string",
        paramType = "path",
        value = "Dataset ID"
      )
    )
  )
  @ApiResponses(
    Array(
      new ApiResponse(code = 403, message = "forbidden"),
      new ApiResponse(code = 401, message = "unauthorized"),
      new ApiResponse(code = 404, message = "not found"),
      new ApiResponse(code = 400, message = "bad request")
    )
  )
  def sponsorDataset: Route =
    (path(Segment / "datasets" / Segment / "sponsor") & post & entity(
      as[SponsorshipRequest]
    )) { (organizationId, datasetId, body) =>
      {

        val token =
          JwtAuthenticator.generateServiceToken(
            1.minute,
            organizationId.toInt,
            Some(datasetId.toInt)
          )

        val tokenHeader = Authorization(OAuth2BearerToken(token.value))

        val clientResponse
          : EitherT[Future, Either[Throwable, HttpResponse], SponsorDatasetResponse] =
          publishClient.sponsorDataset(
            organizationId.toInt,
            datasetId.toInt,
            body,
            List(tokenHeader)
          )

        val toCoreError: EitherT[Future, CoreError, SponsorshipResponse] =
          toServiceError(clientResponse).flatMap {
            _.fold[EitherT[Future, CoreError, SponsorshipResponse]](
              handleCreated = response => EitherT.pure(response),
              handleInternalServerError =
                msg => EitherT.leftT(ServiceError(msg)),
              handleForbidden = _ =>
                EitherT.leftT(
                  OrganizationPermissionError(
                    container.user.nodeId,
                    organizationId.toInt
                  )
                ),
              handleUnauthorized = EitherT
                .leftT(UnauthorizedError("getPublishStatuses unauthorized")),
              handleNotFound = _ => EitherT.leftT(BfNotFound(datasetId)),
              handleBadRequest = msg => EitherT.leftT(PredicateError(msg))
            )
          }

        onSuccess(toCoreError.value) {
          case Right(result) => {
            complete(result)
          }
          case Left(error) =>
            complete {
              HttpResponse(
                error match {
                  case _: OrganizationPermissionError => Forbidden
                  case _: UnauthorizedError => Unauthorized
                  case _: BfNotFound => NotFound
                  case _: PredicateError => BadRequest
                  case _ => InternalServerError
                },
                entity =
                  s"failed to sponsor dataset with error: ${error.getMessage}"
              )
            }
        }

      }
    }

  @Path("{organizationId}/datasets/{datasetId}/sponsor")
  @ApiOperation(
    httpMethod = "DELETE",
    value = "Returns 204 if the deletion was successful"
  )
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(
        name = "organizationId",
        required = true,
        dataType = "string",
        paramType = "path",
        value = "Organization ID"
      ),
      new ApiImplicitParam(
        name = "datasetId",
        required = true,
        dataType = "string",
        paramType = "path",
        value = "Dataset ID"
      )
    )
  )
  @ApiResponses(
    Array(
      new ApiResponse(code = 403, message = "forbidden"),
      new ApiResponse(code = 401, message = "unauthorized"),
      new ApiResponse(code = 404, message = "not found"),
      new ApiResponse(code = 400, message = "bad request")
    )
  )
  def removeDatasetSponsor: Route =
    (path(Segment / "datasets" / Segment / "sponsor") & delete) {
      (organizationId, datasetId) =>
        {

          val token =
            JwtAuthenticator.generateServiceToken(
              1.minute,
              organizationId.toInt,
              Some(datasetId.toInt)
            )

          val tokenHeader = Authorization(OAuth2BearerToken(token.value))

          val clientResponse
            : EitherT[Future, Either[Throwable, HttpResponse], RemoveDatasetSponsorResponse] =
            publishClient.removeDatasetSponsor(
              organizationId.toInt,
              datasetId.toInt,
              List(tokenHeader)
            )

          val toCoreError
            : EitherT[Future, CoreError, RemoveDatasetSponsorResponse] =
            toServiceError(clientResponse).flatMap {
              _.fold[EitherT[Future, CoreError, RemoveDatasetSponsorResponse]](
                handleNoContent =
                  EitherT.pure(RemoveDatasetSponsorResponse.NoContent),
                handleInternalServerError =
                  msg => EitherT.leftT(ServiceError(msg)),
                handleForbidden = _ =>
                  EitherT.leftT(
                    OrganizationPermissionError(
                      container.user.nodeId,
                      organizationId.toInt
                    )
                  ),
                handleUnauthorized = EitherT
                  .leftT(UnauthorizedError("getPublishStatuses unauthorized")),
                handleNotFound = _ => EitherT.leftT(BfNotFound(datasetId)),
                handleBadRequest = msg => EitherT.leftT(PredicateError(msg))
              )
            }

          onSuccess(toCoreError.value) {
            case Right(_) => {
              complete(NoContent)
            }
            case Left(error) =>
              complete {
                HttpResponse(
                  error match {
                    case _: OrganizationPermissionError => Forbidden
                    case _: UnauthorizedError => Unauthorized
                    case _: BfNotFound => NotFound
                    case _: PredicateError => BadRequest
                    case _ => InternalServerError
                  },
                  entity =
                    s"failed to remove dataset sponsorship with error: ${error.getMessage}"
                )
              }
          }

        }
    }

  def toServiceError[T](
    clientResponse: EitherT[Future, Either[Throwable, HttpResponse], T]
  ): EitherT[Future, ServiceError, T] =
    clientResponse
      .leftSemiflatMap(
        _.fold(
          error => Future.successful(ServiceError(error.toString)),
          resp =>
            resp.entity.toStrict(5.seconds)(mat).map { entity =>
              ServiceError(s"HTTP ${resp.status}: ${entity.data.utf8String}")
            }
        )
      )

}
