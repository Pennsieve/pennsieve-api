// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.admin.api.services

import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCodes.InternalServerError
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.data._
import cats.implicits._
import com.blackfynn.admin.api.Router.{
  InsecureResourceContainer,
  SecureResourceContainer
}
import com.blackfynn.admin.api.Settings
import com.blackfynn.akka.http.RouteService
import com.blackfynn.models.UserInvite
import com.blackfynn.models.DBPermission.{ Delete, Owner }
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.java8.time._
import io.circe.{ Decoder, Encoder, Json }
import io.circe.syntax._
import io.swagger.annotations._
import io.swagger.annotations.{ Authorization => SwaggerAuthorization }
import java.time.Duration

import com.blackfynn.domain.CoreError
import javax.ws.rs.Path
import com.blackfynn.managers.OrganizationManager.Invite

import scala.concurrent.{ ExecutionContext, Future }

case class InviteRequest(
  inviterFullName: String,
  organizationId: String,
  email: String,
  firstName: String,
  lastName: String,
  isOwner: Boolean
)

object InviteRequest {
  implicit val encoder: Encoder[InviteRequest] = deriveEncoder[InviteRequest]
  implicit val decoder: Decoder[InviteRequest] = deriveDecoder[InviteRequest]
}

@Path("/users")
@Api(
  value = "Users",
  produces = "application/json",
  authorizations = Array(new SwaggerAuthorization(value = "Bearer"))
)
class UserService(
  container: SecureResourceContainer,
  insecureContainer: InsecureResourceContainer
)(implicit
  ec: ExecutionContext
) extends RouteService {

  val routes: Route =
    pathPrefix("users") {
      generateUserInvite
    }

  @Path("/invite")
  @ApiOperation(
    httpMethod = "POST",
    response = classOf[UserInvite],
    value = "Returns the NewUserToken that was created",
    responseContainer = "Set"
  )
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(
        value =
          "User email, Organization Id and if they will be an owner of the given organization",
        required = true,
        paramType = "body",
        dataType = "com.blackfynn.admin.api.services.InviteRequest"
      )
    )
  )
  @ApiResponses(
    Array(
      new ApiResponse(
        code = 400,
        message = "requirement failed: email must be unique"
      ),
      new ApiResponse(
        code = 500,
        message = "failed to create new user token with error"
      )
    )
  )
  def generateUserInvite: Route =
    (path("invite") & post & entity(as[InviteRequest])) { body =>
      val permission = if (body.isOwner) {
        Owner
      } else {
        Delete
      }

      val result: EitherT[Future, CoreError, List[Json]] = for {
        organization <- container.organizationManager.getByNodeId(
          body.organizationId
        )
        created <- container.organizationManager
          .inviteMember(
            organization = organization,
            invite = Invite(body.email, body.firstName, body.lastName),
            ttl = Duration.ofSeconds(Settings.newUserTokenTTL),
            permission = permission
          )(
            container.userManager,
            container.userInviteManager,
            insecureContainer.cognitoClient,
            container.emailer,
            container.messageTemplates,
            ec
          )
      } yield List(created.asJson)

      onSuccess(result.value) {
        case Right(userInvite) =>
          complete(userInvite)
        case Left(error) =>
          complete {
            HttpResponse(
              InternalServerError,
              entity =
                s"failed to invite new user with error: ${error.getMessage}"
            )
          }
      }
    }
}
