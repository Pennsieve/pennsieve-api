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

import akka.stream.ActorMaterializer
import com.pennsieve.admin.api.Router.{
  InsecureResourceContainer,
  SecureResourceContainer
}
import com.pennsieve.akka.http.RouteService
import com.pennsieve.models.NodeCodes.{ nodeIdIsA, packageCode }
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives.{ entity, _ }
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCodes.NotFound
import cats.data.EitherT
import cats.implicits._
import com.pennsieve.admin.api.Settings
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.java8.time._
import io.circe.syntax._
import io.swagger.annotations._
import io.swagger.annotations.{ Authorization => SwaggerAuthorization }
import javax.ws.rs.Path

import scala.concurrent.{ ExecutionContext, Future }
import com.pennsieve.admin.api.dtos.{
  JobDTO,
  SimpleDatasetDTO,
  SimpleOrganizationDTO,
  UserDTO
}
import com.pennsieve.auth.middleware.Jwt
import com.pennsieve.clients.Quota
import com.pennsieve.core.utilities.JwtAuthenticator
import com.pennsieve.domain.{ CoreError, InvalidId }
import com.pennsieve.dtos.PackageDTO
import com.pennsieve.models.User
import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

case class PackageResponse(
  `package`: PackageDTO,
  organization: SimpleOrganizationDTO,
  user: UserDTO,
  dataset: SimpleDatasetDTO,
  job: JobDTO
)
object PackageResponse {
  implicit val encoder: Encoder[PackageResponse] =
    deriveEncoder[PackageResponse]
  implicit val decoder: Decoder[PackageResponse] =
    deriveDecoder[PackageResponse]
}

@Path("/packages")
@Api(
  value = "Packages",
  produces = "application/json",
  authorizations = Array(new SwaggerAuthorization(value = "Bearer"))
)
class PackageService(
  container: SecureResourceContainer,
  insecureContainer: InsecureResourceContainer
)(implicit
  ec: ExecutionContext,
  mat: ActorMaterializer
) extends RouteService {

  def getS3Path(user: User, importId: String) = {
    s"s3://${Settings.s3Bucket}/${user.email}/data/$importId"
  }

  def getLogUrl(importId: String) = {
    s"https://elk.pennsieve.io/app/kibana#/discover?_g=(refreshInterval:(pause:!t,value:0),time:(from:now-1y,mode:quick,to:now))&_a=(columns:!(pennsieve.tier,message),filters:!(('$$state':(store:appState),meta:(alias:!n,disabled:!f,index:'982ea520-5eb7-11e8-9747-6b75a1731072',key:pennsieve.import_id,negate:!f,params:(query:'${importId}',type:phrase),type:phrase,value:'${importId}'),query:(match:(pennsieve.import_id:(query:'${importId}',type:phrase))))),index:'982ea520-5eb7-11e8-9747-6b75a1731072',interval:auto,query:(language:lucene,query:''),sort:!('@timestamp',desc))"
  }

  val routes: Route =
    pathPrefix("packages") {
      getPackage
    }

  @Path("/{id}")
  @ApiOperation(
    httpMethod = "GET",
    response = classOf[PackageResponse],
    value = "Returns the Package with the provided ID"
  )
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(
        name = "id",
        required = true,
        dataType = "string",
        paramType = "path",
        value = "Package ID"
      )
    )
  )
  @ApiResponses(
    Array(
      new ApiResponse(code = 400, message = "malformed package id"),
      new ApiResponse(
        code = 404,
        message = "failed to retrieve a package with error"
      )
    )
  )
  def getPackage: Route =
    (path(Segment) & get) { packageId =>
      validate(nodeIdIsA(packageId, packageCode), "malformed package id") {
        val response: EitherT[Future, CoreError, PackageResponse] = for {
          pkg <- container.packageManager.getByNodeId(packageId)
          ds <- container.datasetManager.get(pkg.datasetId)
          singleSource <- container.fileManager.getSingleSource(pkg)
          owner <- pkg.ownerId.traverse(u => container.userManager.get(u))
          user <- Either
            .fromOption[CoreError, User](
              owner,
              InvalidId("Package owner not found")
            )
            .toEitherT[Future]
        } yield
          PackageResponse(
            `package` = PackageDTO.simple(
              `package` = pkg,
              dataset = ds,
              storage = None,
              withExtension = singleSource.flatMap(_.fileExtension)
            ),
            organization = SimpleOrganizationDTO(container.organization),
            user = UserDTO(user),
            dataset = SimpleDatasetDTO(ds),
            job = JobDTO(
              importId = pkg.importId,
              s3Path = getS3Path(
                user,
                pkg.importId
                  .map(_.toString)
                  .getOrElse("")
              ),
              logs = getLogUrl(
                pkg.importId
                  .map(_.toString)
                  .getOrElse("")
              )
            )
          )

        onSuccess(response.value) {
          case Right(result) => {
            complete(result)
          }
          case Left(error) =>
            complete {
              HttpResponse(
                NotFound,
                entity =
                  s"failed to retrieve package with error: ${error.getMessage}"
              )
            }
        }
      }
    }

}
