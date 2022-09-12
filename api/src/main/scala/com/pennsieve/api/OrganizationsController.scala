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

import com.pennsieve.dtos.{
  Builders,
  DatasetStatusDTO,
  OrganizationDTO,
  TeamDTO,
  UserDTO,
  UserInviteDTO
}
import com.pennsieve.helpers.APIContainers.{
  InsecureAPIContainer,
  SecureAPIContainer,
  SecureContainerBuilderType
}
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.helpers.either.EitherErrorHandler.implicits._
import com.pennsieve.helpers.either.EitherTErrorHandler.implicits._
import com.pennsieve.helpers.ResultHandlers._
import com.pennsieve.managers.{
  DatasetStatusManager,
  OrganizationManager,
  SecureOrganizationManager,
  StorageManager,
  StorageServiceClientTrait,
  UpdateOrganization
}
import com.pennsieve.models._
import com.pennsieve.models.DateVersion._
import com.pennsieve.models.DBPermission.{ Administer, Delete }
import com.pennsieve.core.utilities.checkOrErrorT
import com.pennsieve.domain.InvalidAction
import com.pennsieve.domain.StorageAggregation.{ sorganizations, susers }
import com.pennsieve.web.Settings
import cats.data._
import cats.implicits._
import java.time.Duration
import shapeless._

import com.pennsieve.audit.middleware.Auditor
import com.pennsieve.aws.cognito.CognitoClient
import com.pennsieve.aws.email.Email
import com.pennsieve.clients.CustomTermsOfServiceClient
import com.pennsieve.domain.{
  CoreError,
  InvalidDateVersion,
  MissingCustomTermsOfService
}
import com.pennsieve.managers.OrganizationManager.Invite

import java.time.ZonedDateTime
import scala.concurrent.{ ExecutionContext, Future }
import org.scalatra._
import org.scalatra.swagger.Swagger
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder

import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;

case class CreateGroupRequest(name: String)
case class UpdateGroupRequest(name: String)
case class AddToOrganizationRequest(
  invites: Set[Invite], // TODO only accept a single invite
  role: Option[Role] = Some(Role.Editor)
)
case class AddToTeamRequest(ids: List[String])

case class UpdateMemberRequest(
  firstName: Option[String],
  lastName: Option[String],
  credential: Option[String],
  organization: Option[String],
  url: Option[String],
  permission: Option[DBPermission]
)

case class ExpandedOrganizationResponse(
  organization: OrganizationDTO,
  administrators: Set[UserDTO],
  isAdmin: Boolean,
  owners: Set[UserDTO],
  isOwner: Boolean
)

case class ExpandedTeamResponse(
  team: TeamDTO,
  administrators: Set[UserDTO],
  isAdmin: Boolean,
  memberCount: Int
)

case class GetOrganizationsResponse(
  organizations: Set[ExpandedOrganizationResponse]
)

case class AddUserResponse(
  success: Boolean,
  message: String,
  email: Email,
  invite: Option[UserInviteDTO] = None,
  user: Option[UserDTO] = None
)

object AddUserResponse {

  def apply(
    organization: Organization
  )(
    r: OrganizationManager.AddEmailResult
  ): AddUserResponse = r match {
    case OrganizationManager.AddEmailResult.AddedExistingUser(user) =>
      AddUserResponse(
        success = true,
        message = s"Added existing user to organization: ${organization.name}",
        email = Email(user.email),
        user = Some(
          Builders.userDTO(
            user,
            organizationNodeId = None,
            storage = None,
            pennsieveTermsOfService = None,
            customTermsOfService = Seq.empty
          )
        )
      )
    case OrganizationManager.AddEmailResult.InvitedNewUser(invite) =>
      AddUserResponse(
        success = true,
        email = Email(invite.email),
        message = s"Invited new user to organization: ${organization.name}",
        invite = Some(UserInviteDTO(invite))
      )
  }
}

case class DatasetStatusRequest(displayName: String, color: String)

case class DataUseAgreementDTO(
  id: Int,
  name: String,
  description: String,
  body: String,
  isDefault: Boolean,
  createdAt: ZonedDateTime
)

object DataUseAgreementDTO {
  def apply(agreement: DataUseAgreement): DataUseAgreementDTO =
    DataUseAgreementDTO(
      id = agreement.id,
      name = agreement.name,
      description = agreement.description,
      body = agreement.body,
      isDefault = agreement.isDefault,
      createdAt = agreement.createdAt
    )
}

case class CreateDataUseAgreementRequest(
  name: String,
  body: String,
  isDefault: Boolean = false,
  description: Option[String] = None
)

case class UpdateDataUseAgreementRequest(
  name: Option[String] = None,
  body: Option[String] = None,
  description: Option[String] = None,
  isDefault: Option[Boolean] = None
)

class OrganizationsController(
  val insecureContainer: InsecureAPIContainer,
  val secureContainerBuilder: SecureContainerBuilderType,
  auditLogger: Auditor,
  customTermsOfServiceClient: CustomTermsOfServiceClient,
  cognitoClient: CognitoClient,
  asyncExecutor: ExecutionContext
)(implicit
  val swagger: Swagger
) extends ScalatraServlet
    with AuthenticatedController {

  override protected implicit def executor: ExecutionContext = asyncExecutor

  override val pennsieveSwaggerTag = "Organizations"

  val addToOrganizationOperation
    : OperationBuilder = (apiOperation[Map[String, AddUserResponse]](
    "addToOrganization"
  )
    summary "adds members to an organization, notifies them over email"
    parameters (
      pathParam[String]("id").description("organization id"),
      bodyParam[AddToOrganizationRequest]
  ))

  post("/:id/members", operation(addToOrganizationOperation)) {
    new AsyncResult {
      val result = for {
        secureContainer <- getSecureContainer()
        traceId <- getTraceId(request)
        administrator = secureContainer.user

        inviteRequests <- extractOrErrorT[AddToOrganizationRequest](parsedBody)

        organizationId <- paramT[String]("id")
        organization <- secureContainer.organizationManager
          .getByNodeId(organizationId, DBPermission.Administer)
          .coreErrorToActionResult()

        // TODO: only process a single invite per request
        results <- inviteRequests.invites.toList
          .traverse(
            invite =>
              secureContainer.organizationManager
                .inviteMember(
                  organization,
                  invite,
                  Duration.ofSeconds(Settings.newUserTokenTTL),
                  DBPermission.fromRole(inviteRequests.role)
                )(
                  insecureContainer.userManager,
                  insecureContainer.userInviteManager,
                  cognitoClient,
                  insecureContainer.emailer,
                  insecureContainer.messageTemplates,
                  executor
                )
          )
          .coreErrorToActionResult()

        _ <- auditLogger
          .message()
          .append("organization-node-id", organization.id)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult()

      } yield
        results
          .map(AddUserResponse(organization)(_))
          .map(dto => (dto.email.toString, dto)) // Backwards-compatible response
          .toMap
      val is = result.value.map(OkResult)
    }
  }

  def sanitizeUsers(
    users: List[User],
    fetch: Boolean = true
  )(implicit
    storageManager: StorageServiceClientTrait
  ): EitherT[Future, ActionResult, List[UserDTO]] = {
    if (fetch) {
      for {
        storageMap <- storageManager
          .getStorage(susers, users.map(_.id))
          .orError()
        dto <- users
          .traverse(
            user =>
              Builders.userDTO(
                user,
                storage = storageMap.get(user.id).flatten,
                pennsieveTermsOfService = None,
                customTermsOfService = Seq.empty
              )(insecureContainer.organizationManager, executor)
          )
          .coreErrorToActionResult()
      } yield dto
    } else {
      EitherT.right(Future.successful(List.empty[UserDTO]))
    }
  }

  val getOrganizationsOperation
    : OperationBuilder = (apiOperation[GetOrganizationsResponse](
    "getOrganizations"
  )
    summary "get a logged in user's organizations"
    parameter queryParam[Boolean]("includeAdmins")
      .description("whether or not to include owners & admins")
      .defaultValue(true))

  def expandOrganization(
    expand: Boolean,
    user: User,
    organization: Organization
  )(implicit
    secureContainer: SecureAPIContainer
  ): EitherT[Future, ActionResult, ExpandedOrganizationResponse] =
    for {
      ownersAndAdministrators <- secureContainer.organizationManager
        .getOwnersAndAdministrators(organization)
        .coreErrorToActionResult()
      (owners, administrators) = ownersAndAdministrators
      storageManager = StorageManager.create(secureContainer, organization)
      sanitizedOwners <- sanitizeUsers(owners.toList, expand)(storageManager)
      sanitizedAdministrators <- sanitizeUsers(administrators.toList, expand)(
        storageManager
      )
      storageMap <- storageManager
        .getStorage(sorganizations, List(organization.id))
        .orError()
      storage = storageMap.get(organization.id).flatten
      organizationDTO <- Builders
        .organizationDTO(organization, storage = storage)(
          secureContainer.organizationManager,
          executor
        )
        .coreErrorToActionResult()
    } yield
      ExpandedOrganizationResponse(
        organization = organizationDTO,
        administrators = sanitizedAdministrators.toSet,
        isAdmin = administrators.exists(_.id == user.id),
        owners = sanitizedOwners.toSet,
        isOwner = owners.exists(_.id == user.id)
      )

  get("/", operation(getOrganizationsOperation)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, GetOrganizationsResponse] =
        for {
          secureContainer <- getSecureContainer()
          traceId <- getTraceId(request)
          user = secureContainer.user

          includeAdmins <- param[Boolean]("includeAdmins")
            .orElse(Right(true))
            .toEitherT[Future]

          //note insecure for performance reasons
          //this is OK because we fetch the organization securely, so we should also be allowed to access the organization details
          organizations <- insecureContainer.userManager
            .getOrganizations(user)
            .coreErrorToActionResult()

          response <- organizations.traverse(
            expandOrganization(includeAdmins, user, _)(secureContainer)
          )

          _ <- auditLogger
            .message()
            .append("organization-ids", organizations.map(_.id): _*)
            .append("organization-node-ids", organizations.map(_.nodeId): _*)
            .log(traceId)
            .toEitherT
            .coreErrorToActionResult()

        } yield GetOrganizationsResponse(response.toSet)

      val is = result.value.map(OkResult(_))
    }
  }

  val getOrganizationOperation
    : OperationBuilder = (apiOperation[Option[ExpandedOrganizationResponse]](
    "getOrganization"
  )
    summary "get an organization"
    parameters
      pathParam[String]("id").description("organization id"))

  get("/:id", operation(getOrganizationOperation)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, ExpandedOrganizationResponse] =
        for {
          secureContainer <- getSecureContainer()
          traceId <- getTraceId(request)
          user = secureContainer.user

          organizationId <- param[String]("id").toEitherT[Future]

          organization <- secureContainer.organizationManager
            .getByNodeId(organizationId)
            .coreErrorToActionResult()

          ownersAndAdministrators <- secureContainer.organizationManager
            .getOwnersAndAdministrators(organization)
            .coreErrorToActionResult()
          (owners, administrators) = ownersAndAdministrators

          storageManager = StorageManager.create(secureContainer, organization)
          administrators <- sanitizeUsers(administrators.toList)(storageManager)
          owners <- sanitizeUsers(owners.toList)(storageManager)
          storageMap <- storageManager
            .getStorage(sorganizations, List(organization.id))
            .orError()
          storage = storageMap.get(organization.id).flatten
          dto <- Builders
            .organizationDTO(organization, storage = storage)(
              secureContainer.organizationManager,
              executor
            )
            .coreErrorToActionResult()

          _ <- auditLogger
            .message()
            .append("organization-id", organization.id)
            .append("organization-node-id", organization.nodeId)
            .log(traceId)
            .toEitherT
            .coreErrorToActionResult()

        } yield
          ExpandedOrganizationResponse(
            organization = dto,
            administrators = administrators.toSet,
            isAdmin = administrators.exists(_.id == user.nodeId),
            owners = owners.toSet,
            isOwner = owners.exists(_.id == user.nodeId)
          )

      val is = result.value.map(OkResult)
    }
  }

  val updateOrganizationOperation
    : OperationBuilder = (apiOperation[Option[ExpandedOrganizationResponse]](
    "updateOrganization"
  )
    summary "updates an organization"
    parameters (
      pathParam[String]("id").description("organization id"),
      bodyParam[UpdateOrganization]("organization")
        .description("organization to update")
  ))

  put("/:id", operation(updateOrganizationOperation)) {
    new AsyncResult {
      val result = for {
        secureContainer <- getSecureContainer()
        traceId <- getTraceId(request)
        user = secureContainer.user

        organizationToSave <- extractOrErrorT[UpdateOrganization](parsedBody)
        organizationId <- paramT[String]("id")

        _ <- assertNotDemoOrganization(secureContainer)

        updatedOrganization <- secureContainer.organizationManager
          .update(organizationId, organizationToSave)
          .coreErrorToActionResult()
        ownerAdmins <- secureContainer.organizationManager
          .getOwnersAndAdministrators(updatedOrganization)
          .coreErrorToActionResult()
        (owners, administrators) = ownerAdmins

        ownerDTOs = owners.map(
          Builders.userDTO(
            _,
            organizationNodeId = None,
            storage = None,
            pennsieveTermsOfService = None,
            customTermsOfService = Seq.empty
          )
        )
        administratorDTOs = administrators.map(
          Builders.userDTO(
            _,
            organizationNodeId = None,
            storage = None,
            pennsieveTermsOfService = None,
            customTermsOfService = Seq.empty
          )
        )

        storageManager = StorageManager.create(
          secureContainer,
          updatedOrganization
        )
        storageMap <- storageManager
          .getStorage(sorganizations, List(updatedOrganization.id))
          .orError()
        storage = storageMap.get(updatedOrganization.id).flatten
        dto <- Builders
          .organizationDTO(updatedOrganization, storage = storage)(
            secureContainer.organizationManager,
            executor
          )
          .coreErrorToActionResult()

        _ <- auditLogger
          .message()
          .append("organization-node-id", organizationId)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult()

      } yield
        ExpandedOrganizationResponse(
          organization = dto,
          administrators = administratorDTOs.toSet,
          isAdmin = administratorDTOs.exists(_.id == user.nodeId),
          owners = ownerDTOs.toSet,
          isOwner = ownerDTOs.exists(_.id == user.nodeId)
        )

      val is = result.value.map(OkResult)
    }
  }

  def getTeamsResponseHelper(
    team: Team,
    organizationTeam: OrganizationTeam,
    user: User,
    organization: Organization
  )(implicit
    secureContainer: SecureAPIContainer
  ): EitherT[Future, ActionResult, ExpandedTeamResponse] =
    for {
      administrators <- secureContainer.teamManager
        .getAdministrators(team)
        .coreErrorToActionResult()
      storageManager = StorageManager.create(secureContainer, organization)
      sanitizedAdministrators <- sanitizeUsers(administrators.toList)(
        storageManager
      )
      members <- secureContainer.teamManager
        .getUsers(team)
        .coreErrorToActionResult()
    } yield
      ExpandedTeamResponse(
        TeamDTO((team, organizationTeam)),
        sanitizedAdministrators.toSet,
        administrators.exists(_.nodeId == user.nodeId),
        members.size
      )

  val getTeamsOperation
    : OperationBuilder = (apiOperation[Set[ExpandedTeamResponse]]("getTeams")
    summary "get the teams that belong to an organization"
    parameters
      pathParam[String]("organizationId").description("organization id"))

  get("/:organizationId/teams", operation(getTeamsOperation)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, List[ExpandedTeamResponse]] =
        for {

          secureContainer <- getSecureContainer()
          traceId <- getTraceId(request)
          user = secureContainer.user

          organizationId <- paramT[String]("organizationId")

          organization <- secureContainer.organizationManager
            .getByNodeId(organizationId)
            .coreErrorToActionResult()

          teams <- secureContainer.organizationManager
            .getTeams(organization)
            .coreErrorToActionResult()
          expandedTeams <- teams.traverse {
            case (team, orgTeam) =>
              getTeamsResponseHelper(team, orgTeam, user, organization)(
                secureContainer
              )
          }

          _ <- auditLogger
            .message()
            .append("team-ids", teams.map(_._1.id): _*)
            .append("team-node-ids", teams.map(_._1.nodeId): _*)
            .log(traceId)
            .toEitherT
            .coreErrorToActionResult()

        } yield expandedTeams

      val is = result.value.map(OkResult(_))
    }
  }

  val getTeamOperation
    : OperationBuilder = (apiOperation[Option[ExpandedTeamResponse]]("getTeam")
    summary "gets the team for the organization"
    parameters (
      pathParam[String]("organizationId").description("organization id"),
      pathParam[String]("id").description("team id")
  ))

  get("/:organizationId/teams/:id", operation(getTeamOperation)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, ExpandedTeamResponse] = for {
        secureContainer <- getSecureContainer()
        traceId <- getTraceId(request)
        user = secureContainer.user

        organizationId <- paramT[String]("organizationId")
        teamId <- paramT[String]("id")

        organization <- secureContainer.organizationManager
          .getByNodeId(organizationId)
          .coreErrorToActionResult()

        team <- secureContainer.organizationManager
          .getTeamWithOrganizationTeamByNodeId(organization, teamId)
          .coreErrorToActionResult()
        teamDTO = TeamDTO(team)

        administrators <- secureContainer.teamManager
          .getAdministrators(team._1)
          .coreErrorToActionResult()

        storageManager = StorageManager.create(secureContainer, organization)
        sanitizedAdministrators <- sanitizeUsers(administrators.toList)(
          storageManager
        )
        members <- secureContainer.teamManager
          .getUsers(team._1)
          .coreErrorToActionResult()

        _ <- auditLogger
          .message()
          .append("team-id", team._1.id)
          .append("team-node-id", team._1.nodeId)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult()

      } yield
        ExpandedTeamResponse(
          teamDTO,
          sanitizedAdministrators.toSet,
          administrators.exists(_.id == user.id),
          members.size
        )

      val is = result.value.map(OkResult)
    }
  }

  val createTeamOperation
    : OperationBuilder = (apiOperation[Option[ExpandedTeamResponse]](
    "createTeam"
  )
    summary "creates a new team in an organization"
    parameters (
      pathParam[String]("organizationId").description("organization id"),
      bodyParam[CreateGroupRequest]("team").description("team to create")
  ))

  post("/:organizationId/teams", operation(createTeamOperation)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, ExpandedTeamResponse] = for {
        secureContainer <- getSecureContainer()
        traceId <- getTraceId(request)
        user = secureContainer.user

        teamToCreate <- extractOrErrorT[CreateGroupRequest](parsedBody)
        organizationId <- paramT[String]("organizationId")

        organization <- secureContainer.organizationManager
          .getByNodeId(organizationId, DBPermission.Administer)
          .coreErrorToActionResult()

        t <- secureContainer.teamManager
          .create(teamToCreate.name, organization)
          .coreErrorToActionResult()

        team <- secureContainer.organizationManager
          .getTeamWithOrganizationTeamByNodeId(organization, t.nodeId)
          .coreErrorToActionResult()
        teamDTO = TeamDTO(team)

        administrators <- secureContainer.teamManager
          .getAdministrators(team._1)
          .coreErrorToActionResult()

        storageManager = StorageManager.create(secureContainer, organization)
        sanitizedAdministrators <- sanitizeUsers(administrators.toList)(
          storageManager
        )
        members <- secureContainer.teamManager
          .getUsers(team._1)
          .coreErrorToActionResult()

        _ <- auditLogger
          .message()
          .append("team-id", team._1.id)
          .append("team-node-id", team._1.nodeId)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult()

      } yield
        ExpandedTeamResponse(
          teamDTO,
          sanitizedAdministrators.toSet,
          administrators.exists(_.nodeId == user.nodeId),
          members.size
        )

      val is = result.value.map(CreatedResult)
    }
  }

  val updateTeamOperation: OperationBuilder = (apiOperation[Option[TeamDTO]](
    "updateTeam"
  )
    summary "updates a team"
    parameters (
      pathParam[String]("organizationId").description("organization id"),
      pathParam[String]("id").description("team id"),
      bodyParam[UpdateGroupRequest]("team").description("team to modify")
  ))

  put("/:organizationId/teams/:id", operation(updateTeamOperation)) {

    new AsyncResult {
      val result: EitherT[Future, ActionResult, TeamDTO] = for {
        secureContainer <- getSecureContainer()
        traceId <- getTraceId(request)
        teamToSave <- extractOrErrorT[UpdateGroupRequest](parsedBody)
        organizationId <- paramT[String]("organizationId")
        teamId <- paramT[String]("id")

        organization <- secureContainer.organizationManager
          .getByNodeId(organizationId, DBPermission.Administer)
          .coreErrorToActionResult()

        _ <- secureContainer.teamManager
          .update(organization, teamId, teamToSave.name)
          .coreErrorToActionResult()

        updated <- secureContainer.organizationManager
          .getTeamWithOrganizationTeamByNodeId(organization, teamId)
          .coreErrorToActionResult()

        teamDTO = TeamDTO(updated)

        _ <- auditLogger
          .message()
          .append("team-node-id", teamId)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult()

      } yield teamDTO

      val is = result.value.map(OkResult)
    }
  }

  val deleteTeamOperation: OperationBuilder = (apiOperation[Unit]("deleteTeam")
    summary "deletes a team"
    parameters (
      pathParam[String]("organizationId").description("organization id"),
      pathParam[String]("id").description("team id")
  ))

  delete("/:organizationId/teams/:id", operation(deleteTeamOperation)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Unit] = for {
        secureContainer <- getSecureContainer()
        traceId <- getTraceId(request)
        user = secureContainer.user

        organizationId <- paramT[String]("organizationId")
        teamId <- paramT[String]("id")

        organization <- secureContainer.organizationManager
          .getByNodeId(organizationId, DBPermission.Administer)
          .coreErrorToActionResult()

        team <- secureContainer.organizationManager
          .getTeamWithOrganizationTeamByNodeId(organization, teamId)
          .coreErrorToActionResult()

        _ <- checkOrErrorT(team._2.systemTeamType isEmpty)(
          BadRequest(
            s"team $teamId is managed by pennsieve and cannot be deleted"
          )
        )

        _ <- secureContainer.teamManager
          .delete(team._1.id)
          .coreErrorToActionResult()

        _ <- auditLogger
          .message()
          .append("team-id", team._1.id)
          .append("team-node-id", team._1.nodeId)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult()

      } yield ()

      val is = result.value.map(OkResult)
    }
  }

  val getMembersOperation: OperationBuilder = (apiOperation[Set[UserDTO]](
    "getMembers"
  )
    summary "get the members that belong to an organization"
    parameters
      pathParam[String]("id").description("organization id"))

  get("/:id/members", operation(getMembersOperation)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Seq[UserDTO]] = for {
        secureContainer <- getSecureContainer()
        traceId <- getTraceId(request)
        organizationId <- paramT[String]("id")

        _ <- assertNotDemoOrganization(secureContainer)

        organization <- secureContainer.organizationManager
          .getByNodeId(organizationId)
          .coreErrorToActionResult()

        members <- secureContainer.organizationManager
          .getOrganizationUsers(organization)
          .coreErrorToActionResult()
        storageManager = StorageManager.create(secureContainer, organization)
        storageMap <- storageManager
          .getStorage(susers, members.map(_._1.id))
          .orError()
        pennsieveTermsOfServiceMap <- insecureContainer.pennsieveTermsOfServiceManager
          .getUserMap(members.map(_._1.id))
          .orError()
        customTermsOfServiceMap <- insecureContainer.customTermsOfServiceManager
          .getUserMap(members.map(_._1.id), organization.id)
          .orError()
        dto <- members
          .traverse {
            case (member, orgUser) =>
              Builders.userDTO(
                member,
                storageMap.get(member.id).flatten,
                pennsieveTermsOfServiceMap.get(member.id).map(_.toDTO),
                customTermsOfServiceMap
                  .getOrElse(member.id, Seq.empty)
                  .map(_.toDTO(organization.nodeId)),
                orgUser.permission.toRole
              )(insecureContainer.organizationManager, executor)
          }
          .coreErrorToActionResult()

        _ <- auditLogger
          .message()
          .append("organization-id", organization.id)
          .append("organization-node-id", organization.nodeId)
          .append("team-member-ids", members.map(_._1.id): _*)
          .append("team-member-node-ids", members.map(_._1.nodeId): _*)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult()

      } yield dto

      val is = result.value.map(OkResult)
    }
  }

  val removeFromOrganizationOperation: OperationBuilder = (apiOperation[Unit](
    "removeFromOrganization"
  )
    summary "removes a member from an organization"
    parameters (
      pathParam[String]("organizationId").description("organization id"),
      pathParam[String]("id").description("member id")
  ))

  delete(
    "/:organizationId/members/:id",
    operation(removeFromOrganizationOperation)
  ) {
    new AsyncResult {
      val result = for {
        secureContainer <- getSecureContainer()
        organizationId <- paramT[String]("organizationId")
        memberId <- paramT[String]("id")
        organization <- secureContainer.organizationManager
          .getByNodeId(organizationId)
          .coreErrorToActionResult()
        member <- insecureContainer.userManager
          .getByNodeId(memberId)
          .coreErrorToActionResult()

        _ <- checkOrErrorT(!member.isIntegrationUser)(
          InvalidAction("Cannot remove integration user"): CoreError
        ).coreErrorToActionResult()

        ownersAndAdmins <- secureContainer.organizationManager
          .getOwnersAndAdministrators(organization)(executor)
          .coreErrorToActionResult()
        owner = ownersAndAdmins._1.toList.headOption
        _ <- secureContainer.packageManager
          .switchOwner(member, owner)
          .coreErrorToActionResult()
        userDatasets <- secureContainer.datasetManager
          .find(member, Role.Viewer)
          .map(_.map(_.dataset).toList)
          .coreErrorToActionResult()
        _ <- secureContainer.datasetManager
          .removeCollaborators(userDatasets, Set(member.nodeId))
          .coreErrorToActionResult()
        storageManager = StorageManager.create(secureContainer, organization)
        storageMap <- storageManager
          .getStorage(susers, List(member.id))
          .coreErrorToActionResult()
        storage = storageMap.get(member.id).flatten.getOrElse(0L)
        _ = storageManager.incrementStorage(susers, -storage, member.id)
        _ = owner.traverse(
          o => storageManager.incrementStorage(susers, storage, o.id)
        )
        _ <- secureContainer.organizationManager
          .removeUser(organization, member)
          .coreErrorToActionResult()
      } yield Unit

      val is = result.value.map(OkResult)
    }
  }

  val updateMemberOperation: OperationBuilder = (apiOperation[Option[UserDTO]](
    "updateMember"
  )
    summary "update a member for an organization"
    parameters (
      pathParam[String]("organizationId").description("organization id"),
      pathParam[String]("id").description("member id"),
      bodyParam[UpdateMemberRequest]("user").required
  ))

  put("/:organizationId/members/:id", operation(updateMemberOperation)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, UserDTO] = for {
        secureContainer <- getSecureContainer()
        traceId <- getTraceId(request)
        organizationId <- paramT[String]("organizationId")
        userId <- paramT[String]("id")
        userToSave <- extractOrErrorT[UpdateMemberRequest](parsedBody)

        someFieldsNotEmpty = userToSave match {
          case UpdateMemberRequest(None, None, None, None, None, None) =>
            false
          case _ => true
        }

        _ <- checkOrErrorT(someFieldsNotEmpty)(
          BadRequest("Must send at least one field to update")
        )
        _ <- secureContainer.organizationManager
          .hasPermission(secureContainer.organization, DBPermission.Administer)(
            executor
          )
          .coreErrorToActionResult()

        user <- insecureContainer.userManager
          .getByNodeId(userId)
          .coreErrorToActionResult()

        _ <- checkOrErrorT(!user.isIntegrationUser)(
          InvalidAction("Integration User cannot be updated."): CoreError
        ).coreErrorToActionResult()

        preferredOrganizationId <- insecureContainer.userManager
          .getPreferredOrganizationId(
            userToSave.organization,
            user.preferredOrganizationId
          )(insecureContainer.organizationManager, executor)
          .coreErrorToActionResult()

        updatedUser = user.copy(
          firstName = userToSave.firstName.getOrElse(user.firstName),
          lastName = userToSave.lastName.getOrElse(user.lastName),
          credential = userToSave.credential.getOrElse(user.credential),
          preferredOrganizationId = preferredOrganizationId,
          url = userToSave.url.getOrElse(user.url)
        )

        _ <- checkOrErrorT(
          userToSave.permission.isEmpty || userToSave.permission
            .contains(Delete) || userToSave.permission.contains(Administer)
        )(BadRequest(s"Invalid permission: ${userToSave.permission}"))

        _ <- userToSave.permission
          .traverse { permission =>
            secureContainer.organizationManager
              .updateUserPermission(
                secureContainer.organization,
                updatedUser,
                permission
              )
          }
          .coreErrorToActionResult()

        organization <- secureContainer.organizationManager
          .getByNodeId(organizationId)
          .coreErrorToActionResult()
        storageManager = StorageManager.create(secureContainer, organization)
        newUser <- insecureContainer.userManager
          .update(updatedUser)
          .coreErrorToActionResult()
        storageMap <- storageManager
          .getStorage(susers, List(newUser.id))
          .orError()
        storage = storageMap.get(newUser.id).flatten
        dto <- Builders
          .userDTO(newUser, storage = storage)(
            insecureContainer.organizationManager,
            insecureContainer.pennsieveTermsOfServiceManager,
            insecureContainer.customTermsOfServiceManager,
            executor
          )
          .coreErrorToActionResult()

        _ <- auditLogger
          .message()
          .append("organization-id", organization.id)
          .append("organization-node-id", organization.nodeId)
          .append("user-id", user.id)
          .append("user-node-id", user.nodeId)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult()

      } yield dto

      val is = result.value.map(OkResult)
    }
  }

  // get /:organizationId/invites
  val getOrganizationInvites
    : OperationBuilder = (apiOperation[List[UserInviteDTO]](
    "getOrganizationInvites"
  )
    summary "get all invites that belong to this organization"
    parameters
      pathParam[String]("organizationId"))

  get("/:organizationId/invites", operation(getOrganizationInvites)) {
    new AsyncResult {
      val result = for {
        secureContainer <- getSecureContainer()
        organizationId <- paramT[String]("organizationId")
        organization <- secureContainer.organizationManager
          .getByNodeId(organizationId)
          .coreErrorToActionResult()
        invites <- secureContainer.organizationManager
          .getInvites(organization)(
            insecureContainer.userInviteManager,
            executor
          )
          .coreErrorToActionResult()
      } yield invites.map(UserInviteDTO(_))

      val is = result.value.map(OkResult)
    }
  }

  val refreshOrganizationInvite
    : OperationBuilder = (apiOperation[AddUserResponse](
    "refreshOrganizationInvite"
  )
    summary "refresh an invite to a particular organization"
    parameters (
      pathParam[String]("organizationId"),
      pathParam[String]("id").description("id of the invite to refresh")
  ))

  put("/:organizationId/invites/:id", operation(refreshOrganizationInvite)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, AddUserResponse] = for {
        secureContainer <- getSecureContainer()
        admin = secureContainer.user

        organizationId <- paramT[String]("organizationId")
        inviteId <- paramT[String]("id")

        organization <- secureContainer.organizationManager
          .getByNodeId(organizationId, DBPermission.Administer)
          .coreErrorToActionResult()

        inviteOrUser <- secureContainer.organizationManager
          .refreshInvite(
            organization,
            inviteId,
            Duration.ofSeconds(Settings.newUserTokenTTL)
          )(
            insecureContainer.userManager,
            insecureContainer.userInviteManager,
            cognitoClient,
            insecureContainer.emailer,
            insecureContainer.messageTemplates,
            executor
          )
          .coreErrorToActionResult()

      } yield AddUserResponse(organization)(inviteOrUser)

      val is = result.value.map(OkResult)
    }
  }

  val deleteOrganizationInvite: OperationBuilder = (apiOperation[Unit](
    "deleteOrganizationInvite"
  )
    summary "delete an invite to a particular organization"
    parameters (
      pathParam[String]("organizationId"),
      pathParam[String]("id").description("id of the invite to delete")
  ))
  delete("/:organizationId/invites/:id", operation(deleteOrganizationInvite)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Unit] = for {
        secureContainer <- getSecureContainer()
        organizationId <- paramT[String]("organizationId")
        inviteId <- paramT[String]("id")

        inviteOrUser <- secureContainer.organizationManager
          .deleteInvite(organizationId, inviteId)(
            insecureContainer.userInviteManager,
            executor
          )
          .coreErrorToActionResult()
      } yield inviteOrUser

      val is = result.value.map(OkResult)
    }
  }

  val getTeamMembersOperation: OperationBuilder = (apiOperation[Set[UserDTO]](
    "getTeamMembers"
  )
    summary "get the members that belong to a team"
    parameters (
      pathParam[String]("organizationId").description("organization id"),
      pathParam[String]("id").description("team id")
  ))

  get("/:organizationId/teams/:id/members", operation(getTeamMembersOperation)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Seq[UserDTO]] = for {
        secureContainer <- getSecureContainer()
        traceId <- getTraceId(request)
        organizationId <- paramT[String]("organizationId")
        teamId <- paramT[String]("id")

        _ <- assertNotDemoOrganization(secureContainer)

        organization <- secureContainer.organizationManager
          .getByNodeId(organizationId)
          .coreErrorToActionResult()

        team <- insecureContainer.organizationManager
          .getTeamByNodeId(organization, teamId)
          .coreErrorToActionResult()

        members <- secureContainer.teamManager
          .getUsers(team)
          .coreErrorToActionResult()
        storageManager = StorageManager.create(secureContainer, organization)
        storageMap <- storageManager
          .getStorage(susers, members.map(_.id))
          .orError()
        pennsieveTermsOfServiceMap <- insecureContainer.pennsieveTermsOfServiceManager
          .getUserMap(members.map(_.id))
          .orError()
        customTermsOfServiceMap <- insecureContainer.customTermsOfServiceManager
          .getUserMap(members.map(_.id), organization.id)
          .orError()
        dto <- members
          .traverse(
            member =>
              Builders
                .userDTO(
                  member,
                  storage = storageMap.get(member.id).flatten,
                  pennsieveTermsOfService =
                    pennsieveTermsOfServiceMap.get(member.id).map(_.toDTO),
                  customTermsOfService = customTermsOfServiceMap
                    .getOrElse(member.id, Seq.empty)
                    .map(_.toDTO(organization.nodeId))
                )(insecureContainer.organizationManager, executor)
          )
          .coreErrorToActionResult()

        _ <- auditLogger
          .message()
          .append("organization-id", organization.id)
          .append("organization-node-id", organization.nodeId)
          .append("team-member-ids", members.map(_.id): _*)
          .append("team-member-node-ids", members.map(_.nodeId): _*)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult()

      } yield dto

      val is = result.value.map(OkResult)
    }
  }

  val addToTeamOperation: OperationBuilder = (apiOperation[Set[UserDTO]](
    "addToTeam"
  )
    summary "adds a member to a team, notifies them over email"
    parameters (
      pathParam[String]("organizationId").description("organization id"),
      pathParam[String]("id").description("team id"),
      bodyParam[AddToTeamRequest]("ids").required
  ))

  post("/:organizationId/teams/:id/members", operation(addToTeamOperation)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, List[UserDTO]] = for {
        secureContainer <- getSecureContainer()
        traceId <- getTraceId(request)
        admin = secureContainer.user
        organizationId <- paramT[String]("organizationId")

        membersToAdd <- extractOrErrorT[AddToTeamRequest](parsedBody)
        teamId <- paramT[String]("id")

        organization <- secureContainer.organizationManager
          .getByNodeId(organizationId, DBPermission.Administer)
          .coreErrorToActionResult()

        team <- insecureContainer.organizationManager
          .getTeamByNodeId(organization, teamId)
          .coreErrorToActionResult()

        administrator = s"${admin.firstName} ${admin.lastName}"

        users <- insecureContainer.userManager
          .getByNodeIds(membersToAdd.ids.toSet)
          .coreErrorToActionResult()

        results <- users
          .traverse { user =>
            secureContainer.teamManager
              .addUser(team, user, Delete)
              .map { _ =>
                insecureContainer.emailer
                  .sendEmail(
                    Email(user.email),
                    Settings.support_email,
                    insecureContainer.messageTemplates.addedToTeam(
                      administrator = administrator,
                      team = team.name,
                      emailAddress = user.email,
                      org = organization
                    ),
                    "You’ve been added to a team"
                  )

                user
              }
          }
          .coreErrorToActionResult()

        dto <- results
          .traverse(
            Builders.userDTO(
              _,
              storage = None,
              pennsieveTermsOfService = None,
              customTermsOfService = Seq.empty
            )(insecureContainer.organizationManager, executor)
          )
          .coreErrorToActionResult()

        _ <- auditLogger
          .message()
          .append("organization-id", organization.id)
          .append("organization-node-id", organization.nodeId)
          .append("team-id", team.id)
          .append("team-node-id", team.nodeId)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult()

      } yield dto

      val is = result.value.map(OkResult(_))
    }
  }

  val removeFromTeamOperation: OperationBuilder = (apiOperation[Unit](
    "removeFromTeam"
  )
    summary "removes a member from a team"
    parameters (
      pathParam[String]("organizationId").description("organization id"),
      pathParam[String]("teamId").description("team id"),
      pathParam[String]("id").description("member id")
  ))

  delete(
    "/:organizationId/teams/:teamId/members/:id",
    operation(removeFromTeamOperation)
  ) {
    new AsyncResult {
      val result = for {
        secureContainer <- getSecureContainer()
        user = secureContainer.user

        organizationId <- paramT[String]("organizationId")
        teamId <- paramT[String]("teamId")
        memberId <- paramT[String]("id")

        organization <- secureContainer.organizationManager
          .getByNodeId(organizationId, DBPermission.Administer)
          .coreErrorToActionResult()

        team <- insecureContainer.organizationManager
          .getTeamByNodeId(organization, teamId)
          .coreErrorToActionResult()

        member <- insecureContainer.userManager
          .getByNodeId(memberId)
          .coreErrorToActionResult()

        _ <- secureContainer.teamManager
          .removeUser(team, member)
          .coreErrorToActionResult()
      } yield Unit

      val is = result.value.map(OkResult)
    }
  }

  val getOrganizationCustomTermsOfService
    : OperationBuilder = (apiOperation[String]("getCustomTermsOfService")
    summary "Given a version, gets the custom terms of service for an organization"
    parameters (
      pathParam[String]("organizationId").description("Organization ID")
    ))

  get(
    "/:organizationId/custom-terms-of-service",
    operation(getOrganizationCustomTermsOfService)
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, String] = for {
        secureContainer <- getSecureContainer()
        organizationId <- paramT[String]("organizationId")

        organization <- secureContainer.organizationManager
          .getByNodeId(organizationId, DBPermission.Read)
          .coreErrorToActionResult()

        version <- organization.customTermsOfServiceVersion
          .toRight(MissingCustomTermsOfService: CoreError)
          .toEitherT[Future]
          .orNotFound()

        text <- customTermsOfServiceClient
          .getTermsOfService(organization.nodeId, version)
          .leftMap(_ => InvalidDateVersion(version.toString): CoreError)
          .toEitherT[Future]
          .orNotFound()

      } yield text

      val is = result.value.map(OkResult)
    }
  }

  val getDatasetStatus: OperationBuilder = (apiOperation[Seq[DatasetStatusDTO]](
    "getDatasetStatus"
  )
    summary "Get the dataset status options for an organization"
    parameters (
      pathParam[String]("organizationId").description("Organization ID")
    ))

  get("/:organizationId/dataset-status", operation(getDatasetStatus)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Seq[DatasetStatusDTO]] = for {
        secureContainer <- getSecureContainer()
        organizationId <- paramT[String]("organizationId")

        organization <- secureContainer.organizationManager
          .getByNodeId(organizationId, DBPermission.Read)
          .coreErrorToActionResult()

        status <- new DatasetStatusManager(insecureContainer.db, organization).getAllWithUsage
          .coreErrorToActionResult()

      } yield status.map(DatasetStatusDTO(_))

      val is = result.value.map(OkResult)
    }
  }

  val createDatasetStatus: OperationBuilder = (apiOperation[DatasetStatusDTO](
    "createDatasetStatus"
  )
    summary "Create a dataset status for an organization"
    parameters (
      pathParam[String]("organizationId").description("Organization ID"),
      bodyParam[DatasetStatusRequest].required
  ))

  post("/:organizationId/dataset-status", operation(createDatasetStatus)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, DatasetStatusDTO] = for {
        secureContainer <- getSecureContainer()
        _ <- assertNotDemoOrganization(secureContainer)

        organizationId <- paramT[String]("organizationId")
        body <- extractOrErrorT[DatasetStatusRequest](parsedBody)

        organization <- secureContainer.organizationManager
          .getByNodeId(organizationId, DBPermission.Administer)
          .coreErrorToActionResult()

        status <- new DatasetStatusManager(insecureContainer.db, organization)
          .create(displayName = body.displayName, color = body.color)
          .coreErrorToActionResult()

      } yield DatasetStatusDTO(status, inUse = DatasetStatusInUse(false))

      val is = result.value.map(OkResult)
    }
  }

  val updateDatasetStatus: OperationBuilder = (apiOperation[DatasetStatusDTO](
    "updateDatasetStatus"
  )
    summary "Update a dataset status for an organization"
    parameters (
      pathParam[String]("organizationId").description("Organization ID"),
      pathParam[Int]("datasetStatusId").description("Dataset Status ID"),
      bodyParam[DatasetStatusRequest].required
  ))

  put(
    "/:organizationId/dataset-status/:datasetStatusId",
    operation(updateDatasetStatus)
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, DatasetStatusDTO] = for {
        secureContainer <- getSecureContainer()
        organizationId <- paramT[String]("organizationId")
        datasetStatusId <- paramT[Int]("datasetStatusId")
        body <- extractOrErrorT[DatasetStatusRequest](parsedBody)

        organization <- secureContainer.organizationManager
          .getByNodeId(organizationId, DBPermission.Administer)
          .coreErrorToActionResult()

        status <- new DatasetStatusManager(insecureContainer.db, organization)
          .update(
            id = datasetStatusId,
            displayName = body.displayName,
            color = body.color
          )
          .coreErrorToActionResult()

      } yield DatasetStatusDTO(status)

      val is = result.value.map(OkResult)
    }
  }

  val deleteDatasetStatus: OperationBuilder = (apiOperation[DatasetStatusDTO](
    "deleteDatasetStatus"
  )
    summary "Delete a dataset status for an organization"
    parameters (
      pathParam[String]("organizationId").description("Organization ID"),
      pathParam[Int]("datasetStatusId").description("Dataset Status ID")
  ))

  delete(
    "/:organizationId/dataset-status/:datasetStatusId",
    operation(deleteDatasetStatus)
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, DatasetStatusDTO] = for {
        secureContainer <- getSecureContainer()
        organizationId <- paramT[String]("organizationId")
        datasetStatusId <- paramT[Int]("datasetStatusId")

        organization <- secureContainer.organizationManager
          .getByNodeId(organizationId, DBPermission.Administer)
          .coreErrorToActionResult()

        status <- new DatasetStatusManager(insecureContainer.db, organization)
          .delete(id = datasetStatusId)
          .coreErrorToActionResult()

      } yield DatasetStatusDTO(status)

      val is = result.value.map(OkResult)
    }
  }

  val getDataUseAgreement
    : OperationBuilder = (apiOperation[Seq[DataUseAgreementDTO]](
    "getDataUseAgreement"
  )
    summary "Get the data use agreements for the organization"
    parameters (
      pathParam[String]("organizationId").description("Organization ID")
    ))

  get("/:organizationId/data-use-agreements", operation(getDataUseAgreement)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Seq[DataUseAgreementDTO]] =
        for {
          secureContainer <- getSecureContainer()
          organizationId <- paramT[String]("organizationId")

          organization <- secureContainer.organizationManager
            .getByNodeId(organizationId, DBPermission.Read)
            .coreErrorToActionResult()

          agreements <- secureContainer.dataUseAgreementManager.getAll
            .coreErrorToActionResult()

        } yield agreements.map(DataUseAgreementDTO(_))

      val is = result.value.map(OkResult)
    }
  }

  val createDataUseAgreement
    : OperationBuilder = (apiOperation[Seq[DataUseAgreementDTO]](
    "createDataUseAgreement"
  )
    summary "Create a new data use agreement for the organization"
    parameters (
      pathParam[String]("organizationId").description("Organization ID"),
      bodyParam[CreateDataUseAgreementRequest]("body")
  ))

  post(
    "/:organizationId/data-use-agreements",
    operation(createDataUseAgreement)
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, DataUseAgreementDTO] =
        for {
          secureContainer <- getSecureContainer()
          organizationId <- paramT[String]("organizationId")

          body <- extractOrErrorT[CreateDataUseAgreementRequest](parsedBody)

          organization <- secureContainer.organizationManager
            .getByNodeId(organizationId, DBPermission.Administer)
            .coreErrorToActionResult()

          _ <- assertNotDemoOrganization(secureContainer)

          agreement <- secureContainer.dataUseAgreementManager
            .create(
              name = body.name,
              description = body.description.getOrElse(""),
              body = body.body,
              isDefault = body.isDefault
            )
            .coreErrorToActionResult()

        } yield DataUseAgreementDTO(agreement)

      val is = result.value.map(OkResult)
    }
  }

  val updateDataUseAgreement: OperationBuilder = (apiOperation[Unit](
    "updateDataUseAgreement"
  )
    summary "Update a new data use agreement for the organization"
    parameters (
      pathParam[String]("organizationId").description("Organization ID"),
      pathParam[String]("agreementId").description("Data Use Agreement ID"),
      bodyParam[UpdateDataUseAgreementRequest]("body")
  ))

  put(
    "/:organizationId/data-use-agreements/:agreementId",
    operation(updateDataUseAgreement)
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Unit] =
        for {
          secureContainer <- getSecureContainer()
          organizationId <- paramT[String]("organizationId")
          agreementId <- paramT[Int]("agreementId")

          body <- extractOrErrorT[UpdateDataUseAgreementRequest](parsedBody)

          organization <- secureContainer.organizationManager
            .getByNodeId(organizationId, DBPermission.Administer)
            .coreErrorToActionResult()

          _ <- assertNotDemoOrganization(secureContainer)

          _ <- secureContainer.dataUseAgreementManager
            .update(
              agreementId,
              name = body.name,
              body = body.body,
              description = body.description,
              isDefault = body.isDefault
            )
            .coreErrorToActionResult()

        } yield ()

      val is = result.value.map(NoContentResult(_))
    }
  }

  val deleteDataUseAgreement
    : OperationBuilder = (apiOperation[Seq[DataUseAgreementDTO]](
    "deleteDataUseAgreement"
  )
    summary "Delete a new data use agreement for the organization"
    parameters (
      pathParam[String]("organizationId").description("Organization ID"),
      pathParam[String]("agreementId").description("Data Use Agreement ID")
  ))

  delete(
    "/:organizationId/data-use-agreements/:agreementId",
    operation(deleteDataUseAgreement)
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Unit] =
        for {
          secureContainer <- getSecureContainer()
          organizationId <- paramT[String]("organizationId")
          agreementId <- paramT[Int]("agreementId")

          organization <- secureContainer.organizationManager
            .getByNodeId(organizationId, DBPermission.Administer)
            .coreErrorToActionResult()

          _ <- secureContainer.dataUseAgreementManager
            .delete(agreementId)
            .coreErrorToActionResult()

        } yield ()

      val is = result.value.map(NoContentResult(_))
    }
  }

  /**
    * Demo / sandbox users are a special case. They should not be able to share
    * datasets under any circumstances.
    *
    * This is identical to the helper in the test datasets controller.
    */
  private def assertNotDemoOrganization(
    secureContainer: SecureAPIContainer
  ): EitherT[Future, ActionResult, Unit] =
    for {
      demoOrganization <- secureContainer.organizationManager
        .isDemo(secureContainer.organization.id)
        .coreErrorToActionResult()

      _ <- checkOrErrorT(!demoOrganization)(
        InvalidAction("Demo user cannot share datasets."): CoreError
      ).coreErrorToActionResult()
    } yield ()
}
