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

import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCodes.{ InternalServerError, NotFound }
import akka.http.scaladsl.server.Directives.{ entity, _ }
import akka.http.scaladsl.server.{ Route, ValidationRejection }
import akka.util.ByteString
import cats.data.EitherT
import cats.implicits._
import com.pennsieve.admin.api.Router.{
  InsecureResourceContainer,
  SecureResourceContainer
}
import com.pennsieve.admin.api.dtos.UserDTO
import com.pennsieve.akka.http.RouteService
import com.pennsieve.auth.middleware.Jwt
import com.pennsieve.clients.Quota
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.core.utilities.JwtAuthenticator
import com.pennsieve.db.OrganizationsMapper
import com.pennsieve.domain.{
  CoreError,
  PredicateError,
  NotFound => ModelNotFound
}
import com.pennsieve.managers.StorageManager
import com.pennsieve.models.DateVersion._
import com.pennsieve.models.NodeCodes.{ nodeIdIsA, organizationCode }
import com.pennsieve.models.SubscriptionStatus.PendingSubscription
import com.pennsieve.models.{
  DBPermission,
  Feature,
  FeatureFlag,
  Organization,
  Subscription
}
import com.typesafe.scalalogging.LazyLogging
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.syntax._
import io.circe.{ Decoder, Encoder }
import io.swagger.annotations.{ Authorization => SwaggerAuthorization, _ }

import java.time.{ ZoneOffset, ZonedDateTime }
import javax.ws.rs.Path
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

case class NewOrganization(
  name: String,
  slug: String,
  subscriptionType: Option[String] = None
)
object NewOrganization {
  implicit val encoder: Encoder[NewOrganization] =
    deriveEncoder[NewOrganization]
  implicit val decoder: Decoder[NewOrganization] =
    deriveDecoder[NewOrganization]
}

case class UpdateFeatureFlag(feature: Feature, enabled: Boolean)
object UpdateFeatureFlag {
  implicit val encoder: Encoder[UpdateFeatureFlag] =
    deriveEncoder[UpdateFeatureFlag]
  implicit val decoder: Decoder[UpdateFeatureFlag] =
    deriveDecoder[UpdateFeatureFlag]
}

case class UserWithPermission(user: UserDTO, permission: DBPermission)
object UserWithPermission {
  implicit val encoder: Encoder[UserWithPermission] =
    deriveEncoder[UserWithPermission]
  implicit val decoder: Decoder[UserWithPermission] =
    deriveDecoder[UserWithPermission]
}

case class UpdateOrganizationUserPermission(
  userId: String,
  permission: DBPermission
)
object UpdateOrganizationUserPermission {
  implicit val encoder: Encoder[UpdateOrganizationUserPermission] =
    deriveEncoder[UpdateOrganizationUserPermission]
  implicit val decoder: Decoder[UpdateOrganizationUserPermission] =
    deriveDecoder[UpdateOrganizationUserPermission]
}

case class SetSubscriptionType(isTrial: Boolean)
object SetSubscriptionType {
  implicit val encoder: Encoder[SetSubscriptionType] =
    deriveEncoder[SetSubscriptionType]
  implicit val decoder: Decoder[SetSubscriptionType] =
    deriveDecoder[SetSubscriptionType]
}

@Path("/organizations")
@Api(
  value = "Organizations",
  produces = "application/json",
  authorizations = Array(new SwaggerAuthorization(value = "Bearer"))
)
class OrganizationsService(
  container: SecureResourceContainer,
  insecureContainer: InsecureResourceContainer
)(implicit
  ec: ExecutionContext,
  system: ActorSystem
) extends RouteService
    with LazyLogging {

  val routes: Route =
    pathPrefix("organizations") {
      getOrganizations ~ getInactiveOrganizations ~ getOrganization ~ createOrganization ~
        updateOrganization ~ updateFeatureFlag ~
        getUsers ~ getOwners ~ setUserPermission ~
        getSubscriptionStatus ~ resetSubscriptionAccepted ~ updateStorage ~ setSubscriptionType ~ uploadCustomTermsOfService
    }

  @ApiOperation(
    httpMethod = "GET",
    response = classOf[Organization],
    value = "Returns a list of all Organizations",
    responseContainer = "Set"
  )
  def getOrganizations: Route =
    (pathEnd & get) {
      complete(container.organizationManager.getAll)
    }

  @ApiOperation(
    httpMethod = "GET",
    response = classOf[Organization],
    value =
      "Returns a list of organizations that have been inactive for at least 1 year",
    responseContainer = "Set"
  )
  def getInactiveOrganizations: Route =
    (path("inactive") & get) {
      onSuccess(container.organizationManager.getAll()) {
        organizations: Seq[Organization] =>
          {
            val isActive: Seq[Future[(Organization, Boolean)]] =
              organizations.map { organization: Organization =>
                container.db
                  .run(
                    OrganizationsMapper
                      .isActive(organization)
                      .map(active => (organization, active))
                  )
              }
            val activeOrgs: Future[Seq[Organization]] = Future
              .sequence(isActive)
              .map { activeOrgs: Seq[(Organization, Boolean)] =>
                {
                  activeOrgs
                    .filter { case (_, active) => !active }
                    .map {
                      case (organization, _) => organization
                    }
                }
              }
            complete {
              activeOrgs
            }
          }
      }
    }

  @Path("/{id}")
  @ApiOperation(
    httpMethod = "GET",
    response = classOf[Organization],
    value = "Returns the Organization with the provided ID"
  )
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(
        name = "id",
        required = true,
        dataType = "string",
        paramType = "path",
        value = "Organization ID"
      )
    )
  )
  @ApiResponses(
    Array(
      new ApiResponse(code = 400, message = "malformed organization id"),
      new ApiResponse(
        code = 404,
        message = "failed to retrieve an organization with error"
      )
    )
  )
  def getOrganization: Route =
    (path(Segment) & get) { organizationId =>
      validate(
        nodeIdIsA(organizationId, organizationCode),
        "malformed organization id"
      ) {
        onSuccess(
          container.organizationManager.getByNodeId(organizationId).value
        ) {
          case Right(organization) => complete(organization)
          case Left(error) =>
            complete {
              HttpResponse(
                NotFound,
                entity =
                  s"failed to retrieve organization with error: ${error.getMessage}"
              )
            }
        }
      }
    }

  @Path("/")
  @ApiOperation(
    httpMethod = "POST",
    response = classOf[Organization],
    value = "Returns the Organization that was created"
  )
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(
        value = "Organization name and slug",
        required = true,
        paramType = "body",
        dataType = "com.pennsieve.admin.api.services.NewOrganization"
      )
    )
  )
  @ApiResponses(
    Array(
      new ApiResponse(
        code = 400,
        message = "requirement failed: slug must be unique"
      ),
      new ApiResponse(
        code = 500,
        message = "failed to create organization with error"
      )
    )
  )
  def createOrganization: Route =
    (pathEnd & post & entity(as[NewOrganization])) { body =>
      logger.info(s"Creating organization $body")

      val create = for {
        createdOrganization <- container.organizationManager.create(
          name = body.name,
          slug = body.slug,
          subscriptionType = body.subscriptionType
        )
        _ = logger.info(s"Setting quota with JSS")
        jwtToken = JwtAuthenticator.generateServiceToken(
          10.minutes,
          createdOrganization.id
        )(new Jwt.Config { val key: String = container.jwtKey })
        _ <- container.jobSchedulingServiceClient.setOrganizationQuota(
          createdOrganization.id,
          Quota(container.quota),
          jwtToken
        )
      } yield createdOrganization

      onSuccess(create.value) {
        case Right(organization) => complete(organization)
        case Left(PredicateError(_)) =>
          reject(
            ValidationRejection(s"requirement failed: slug must be unique")
          )
        case Left(ModelNotFound(_)) =>
          reject(ValidationRejection(s"requirement failed: schema not found"))
        case Left(error) =>
          complete {
            // The error may be "Boxed" so unfold the error chain to get
            def unfold(
              error: Throwable,
              accum: List[Throwable] = List.empty
            ): List[Throwable] =
              if (error.getCause == null)
                (error :: accum).reverse
              else
                unfold(error.getCause, error :: accum)

            val msg =
              (s"failed to create organization with error: ${error.getMessage}" :: unfold(
                error
              )).mkString("\n").trim

            logger.error(msg)
            HttpResponse(InternalServerError, entity = msg)
          }
      }
    }

  @Path("/")
  @ApiOperation(
    httpMethod = "PUT",
    response = classOf[Organization],
    value = "Returns the Organization that was updated"
  )
  @ApiResponses(
    Array(
      new ApiResponse(
        code = 500,
        message = "failed to update organization with error"
      )
    )
  )
  def updateOrganization: Route =
    (pathEnd & put & entity(as[Organization])) { body =>
      onSuccess(container.organizationManager.update(body).value) {
        case Right(organization) => complete(organization)
        case Left(error) =>
          complete {
            HttpResponse(
              InternalServerError,
              entity =
                s"failed to update organization with error: ${error.getMessage}"
            )
          }
      }
    }

  @Path("{id}/feature")
  @ApiOperation(
    httpMethod = "PUT",
    response = classOf[Boolean],
    value = "Returns whether or not the FeatureFlag was successfully updated"
  )
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(
        name = "id",
        required = true,
        dataType = "string",
        paramType = "path",
        value = "Organization ID"
      ),
      new ApiImplicitParam(
        value = "Feature/enabled",
        required = true,
        paramType = "body",
        dataType = "com.pennsieve.admin.api.services.UpdateFeatureFlag"
      )
    )
  )
  @ApiResponses(
    Array(
      new ApiResponse(
        code = 500,
        message = "failed to update feature flag with error"
      )
    )
  )
  def updateFeatureFlag: Route =
    (pathPrefix(Segment) & path("feature") & put & entity(
      as[UpdateFeatureFlag]
    )) { (organizationId, body) =>
      val result = for {
        organization <- container.organizationManager.getByNodeId(
          organizationId
        )
        feature = FeatureFlag(organization.id, body.feature, body.enabled)
        update <- container.organizationManager.setFeatureFlag(feature)
      } yield update

      onSuccess(result.value) {
        case Right(success) => complete(success)
        case Left(error) =>
          complete {
            HttpResponse(
              InternalServerError,
              entity =
                s"failed to update feature flag with error: ${error.getMessage}"
            )
          }
      }
    }

  @Path("{id}/users")
  @ApiOperation(
    httpMethod = "GET",
    response = classOf[UserDTO],
    value = "Returns the Users that belong to the Organization",
    responseContainer = "Set"
  )
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(
        name = "id",
        required = true,
        dataType = "string",
        paramType = "path",
        value = "Organization ID"
      )
    )
  )
  @ApiResponses(
    Array(
      new ApiResponse(
        code = 500,
        message = "failed to retrieve users in organization with error"
      )
    )
  )
  def getUsers: Route =
    (pathPrefix(Segment) & path("users") & get) { organizationId =>
      val result = for {
        organization <- container.organizationManager.getByNodeId(
          organizationId
        )
        users <- container.organizationManager.getUsersWithPermission(
          organization
        )
        pennsieveTermsOfServiceMap <- container.pennsieveTermsOfServiceManager
          .getUserMap(users.map { case (user, permission) => user.id })
        customTermsOfServiceMap <- container.customTermsOfServiceManager
          .getUserMap(
            users.map { case (user, permission) => user.id },
            organization.id
          )
        usersDTO = users.map {
          case (u, permission) =>
            UserWithPermission(
              UserDTO(
                u,
                pennsieveTermsOfServiceMap.get(u.id).map(_.toDTO),
                customTermsOfServiceMap
                  .getOrElse(u.id, Seq.empty)
                  .map(_.toDTO(organization.nodeId))
              ),
              permission
            )
        }
      } yield usersDTO

      onSuccess(result.value) {
        case Right(success) => complete(success)
        case Left(error) =>
          complete {
            HttpResponse(
              NotFound,
              entity =
                s"failed to retrieve users in organization with error: ${error.getMessage}"
            )
          }
      }
    }

  @Path("{id}/owners")
  @ApiOperation(
    httpMethod = "GET",
    response = classOf[UserDTO],
    value = "Returns the Users designated as owners of the Organization",
    responseContainer = "Set"
  )
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(
        name = "id",
        required = true,
        dataType = "string",
        paramType = "path",
        value = "Organization ID"
      )
    )
  )
  def getOwners: Route =
    (pathPrefix(Segment) & path("owners") & get) { organizationId =>
      val owners: EitherT[Future, CoreError, Seq[UserDTO]] = for {
        organization <- container.organizationManager.getByNodeId(
          organizationId
        )
        results <- container.organizationManager.getOwnersAndAdministrators(
          organization
        )
        (owners, _) = results

        pennsieveTermsOfServiceMap <- container.pennsieveTermsOfServiceManager
          .getUserMap(owners.map(_.id))
        customTermsOfServiceMap <- container.customTermsOfServiceManager
          .getUserMap(owners.map(_.id), organization.id)
      } yield
        owners.map(
          owner =>
            UserDTO(
              owner,
              pennsieveTermsOfServiceMap.get(owner.id).map(_.toDTO),
              customTermsOfServiceMap
                .getOrElse(owner.id, Seq.empty)
                .map(_.toDTO(organization.nodeId))
            )
        )

      onSuccess(owners.value) {
        case Right(success) => complete(success)
        case Left(error) =>
          complete {
            HttpResponse(
              NotFound,
              entity =
                s"failed to retrieve owners for organization with error: ${error.getMessage}"
            )
          }
      }
    }

  @Path("{id}/users")
  @ApiOperation(
    httpMethod = "PUT",
    response = classOf[String],
    value = "Returns OK if the update was successful",
    responseContainer = "String"
  )
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(
        name = "id",
        required = true,
        dataType = "string",
        paramType = "path",
        value = "Organization ID"
      ),
      new ApiImplicitParam(
        value = "User ID, and requested Permission",
        required = true,
        paramType = "body",
        dataType =
          "com.pennsieve.admin.api.services.UpdateOrganizationUserPermission"
      )
    )
  )
  def setUserPermission: Route =
    (pathPrefix(Segment) & path("users") & entity(
      as[UpdateOrganizationUserPermission]
    ) & put) { (organizationId, update) =>
      val result = for {
        organization <- container.organizationManager.getByNodeId(
          organizationId
        )
        user <- container.userManager.getByNodeId(update.userId)
        _ <- container.organizationManager.updateUserPermission(
          organization,
          user,
          update.permission
        )
      } yield s"added ${user.email}"

      onSuccess(result.value) {
        case Right(success) => complete(success)
        case Left(error) =>
          complete {
            HttpResponse(
              InternalServerError,
              entity =
                s"problem updating organization owner: ${error.getMessage}"
            )
          }
      }
    }

  @Path("{id}/subscription")
  @ApiOperation(
    httpMethod = "DELETE",
    response = classOf[String],
    value = "Returns OK if the delete was successful",
    responseContainer = "String"
  )
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(
        name = "id",
        required = true,
        dataType = "string",
        paramType = "path",
        value = "Organization ID"
      )
    )
  )
  def resetSubscriptionAccepted: Route =
    (pathPrefix(Segment) & path("subscription") & delete) { organizationId =>
      val result = for {
        organization <- container.organizationManager.getByNodeId(
          organizationId
        )
        _ <- container.organizationManager.updateSubscription(
          subscription = Subscription(
            organization.id,
            status = PendingSubscription,
            None,
            None,
            None,
            None
          )
        )
      } yield ()

      onSuccess(result.value) {
        case Right(success) => complete(success)
        case Left(error) =>
          complete {
            HttpResponse(
              InternalServerError,
              entity =
                s"problem removing organization subscription: ${error.getMessage}"
            )
          }
      }
    }

  @Path("{id}/subscription")
  @ApiOperation(
    httpMethod = "GET",
    response = classOf[Subscription],
    value = "Returns OK if the delete was successful",
    responseContainer = "com.pennsieve.models.Subscription"
  )
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(
        name = "id",
        required = true,
        dataType = "string",
        paramType = "path",
        value = "Organization ID"
      )
    )
  )
  def getSubscriptionStatus: Route =
    (pathPrefix(Segment) & path("subscription") & get) { organizationId =>
      val result = for {
        organization <- container.organizationManager.getByNodeId(
          organizationId
        )
        subscription <- container.organizationManager.getSubscription(
          organization.id
        )
      } yield subscription

      onSuccess(result.value) {
        case Right(success) => complete(success)
        case Left(error) =>
          complete {
            HttpResponse(
              InternalServerError,
              entity =
                s"problem getting organization subscription: ${error.getMessage}"
            )
          }
      }

    }

  @Path("{id}/subscription")
  @ApiOperation(
    httpMethod = "PUT",
    response = classOf[Subscription],
    value = "Returns OK if subscription has been updated",
    responseContainer = "com.pennsieve.models.Subscription"
  )
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(
        name = "id",
        required = true,
        dataType = "string",
        paramType = "path",
        value = "Organization ID"
      ),
      new ApiImplicitParam(
        value = "isTrial subscription flag",
        required = true,
        paramType = "body",
        dataType = "com.pennsieve.admin.api.services.SetSubscriptionType"
      )
    )
  )
  def setSubscriptionType: Route =
    (pathPrefix(Segment) & path("subscription") & entity(
      as[SetSubscriptionType]
    ) & put) { (organizationId, request) =>
      val subscriptionType = if (request.isTrial) Some("Trial") else None
      val result = for {
        organization <- container.organizationManager.getByNodeId(
          organizationId
        )
        currentSubscription <- container.organizationManager.getSubscription(
          organization.id
        )
        updatedSubscription = currentSubscription.copy(
          `type` = subscriptionType
        )
        _ <- container.organizationManager.updateSubscription(
          updatedSubscription
        )

      } yield updatedSubscription

      onSuccess(result.value) {
        case Right(success) => complete(success)
        case Left(error) =>
          complete {
            HttpResponse(
              InternalServerError,
              entity =
                s"problem updating organization subscription: ${error.getMessage}"
            )
          }
      }
    }

  @Path("{id}/storage")
  @ApiOperation(
    httpMethod = "GET",
    response = classOf[String],
    value =
      "Returns OK if the storage cache population job has been added to the jobs queue",
    responseContainer = "String"
  )
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(
        name = "id",
        required = true,
        dataType = "string",
        paramType = "path",
        value = "Organization ID"
      )
    )
  )
  def updateStorage: Route =
    (pathPrefix(Segment) & path("storage") & get) { organizationNodeId =>
      val result = for {
        organization <- container.organizationManager.getByNodeId(
          organizationNodeId
        )
        message <- StorageManager.updateCache(organization)

        _ <- insecureContainer.sqs.send(
          queueUrl = insecureContainer.sqs_queue,
          message = message.asJson.noSpaces
        )
      } yield ()

      onSuccess(result.value) {
        case Right(_) =>
          complete(
            s"successfully queued storage cache population job for organization $organizationNodeId"
          )
        case Left(error) =>
          complete {
            HttpResponse(
              InternalServerError,
              entity =
                s"problem starting storage cache population job for organization $organizationNodeId"
            )
          }
      }
    }

  @Path("{id}/custom-terms-of-service")
  @ApiOperation(
    httpMethod = "PUT",
    response = classOf[String],
    value = "The version that was created",
    responseContainer = "Set"
  )
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(
        name = "id",
        required = true,
        dataType = "string",
        paramType = "path",
        value = "Organization ID"
      ),
      new ApiImplicitParam(
        name = "isNewVersion",
        required = false,
        dataType = "boolean",
        paramType = "query",
        value = "isNewVersion"
      ),
      new ApiImplicitParam(
        value = "HTML blob containing custom terms to upload",
        required = true,
        paramType = "body",
        dataType = "String"
      )
    )
  )
  @ApiResponses(
    Array(
      new ApiResponse(
        code = 500,
        message = "Failed to upload custom terms of service"
      )
    )
  )
  def uploadCustomTermsOfService: Route =
    (pathPrefix(Segment) & path("custom-terms-of-service") & parameters(
      'isNewVersion ? false
    ) & put) { (organizationNodeId, isNewVersion) => ctx =>
      {
        val result = for {
          html <- ctx.request.entity.dataBytes
            .runFold(ByteString.empty)(_ ++ _)
            .map(_.utf8String)
            .toEitherT
          version <- if (isNewVersion)
            Right(ZonedDateTime.now(ZoneOffset.UTC)).toEitherT[Future]
          else
            container.organizationManager.getCustomTermsOfServiceVersion(
              organizationNodeId
            )
          _ <- if (isNewVersion)
            container.organizationManager
              .updateCustomTermsOfServiceVersion(organizationNodeId, version)
          else Future.unit.toEitherT
          updateResult <- insecureContainer.customTermsOfServiceClient
            .updateTermsOfService(organizationNodeId, html, version)
            .toEitherT[Future]
          (returnedVersion, _, _) = updateResult
        } yield returnedVersion

        result.value.flatMap {
          case Right(returnedVersion) => ctx.complete(returnedVersion.toString)
          case Left(error) =>
            ctx.complete(
              HttpResponse(
                InternalServerError,
                entity = s"Failed to upload terms: ${error.getMessage}"
              )
            )
        }
      }
    }

}
