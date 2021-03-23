// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.api

import cats.data._
import cats.implicits._
import com.authy.AuthyApiClient
import com.pennsieve.audit.middleware.Auditor
import com.pennsieve.core.utilities.FutureEitherHelpers
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.domain.StorageAggregation.susers
import com.pennsieve.dtos.{ Builders, OrcidDTO, UserDTO }
import com.pennsieve.helpers.APIContainers.{
  InsecureAPIContainer,
  SecureContainerBuilderType
}
import com.pennsieve.helpers.OrcidClient
import com.pennsieve.helpers.ResultHandlers.{ HandleResult, OkResult }
import com.pennsieve.helpers.either.EitherErrorHandler.implicits._
import com.pennsieve.helpers.either.EitherTErrorHandler.implicits._
import com.pennsieve.managers.{ AuthyManager, StorageServiceClientTrait }
import com.pennsieve.models.{ DateVersion, Degree, User }
import com.pennsieve.web.Settings
import org.json4s.JValue
import org.json4s.JsonAST.JNothing
import org.scalatra._
import org.scalatra.swagger.Swagger

import scala.concurrent.{ ExecutionContext, Future }

case class UpdateUserRequest(
  firstName: Option[String],
  lastName: Option[String],
  middleInitial: Option[String],
  degree: Option[String],
  credential: Option[String],
  organization: Option[String],
  url: Option[String],
  email: Option[String],
  color: Option[String]
)

case class UpdatePennsieveTermsOfServiceRequest(version: String)

case class AddAuthyRequest(
  phoneNumber: Option[String],
  countryCode: Option[String]
)

case class AuthyUserResponse(authyId: Int)

case class ORCIDRequest(authorizationCode: String)

// `version` expected to be a date in the same format as DateVersion:
case class AcceptCustomTermsOfServiceRequest(version: String)

/*
 * Note this controller relies on an insecure
 * userManager for all update operations because
 * the current secureUserManager currently only
 * allows reads of a user's own data.
 */

class UserController(
  val insecureContainer: InsecureAPIContainer,
  val secureContainerBuilder: SecureContainerBuilderType,
  auditLogger: Auditor,
  authyClient: AuthyApiClient,
  asyncExecutor: ExecutionContext,
  orcidClient: OrcidClient
)(implicit
  val swagger: Swagger
) extends ScalatraServlet
    with AuthenticatedController {

  override val swaggerTag = "User"

  override protected implicit def executor: ExecutionContext = asyncExecutor

  implicit class JValueExtended(value: JValue) {
    def hasField(childString: String): Boolean =
      (value \ childString) != JNothing
  }

  def createUserDTO(
    user: User,
    storageManager: StorageServiceClientTrait
  ): EitherT[Future, ActionResult, UserDTO] =
    for {
      storageMap <- {
        storageManager
          .getStorage(susers, List(user.id))
          .orError
      }
      storage = storageMap.get(user.id).flatten
      dto <- {
        Builders
          .userDTO(user, storage)(
            insecureContainer.organizationManager,
            insecureContainer.pennsieveTermsOfServiceManager,
            insecureContainer.customTermsOfServiceManager,
            executor
          )
          .orError
      }
    } yield dto

  val getUserServiceOperation =
    (
      apiOperation[Option[UserDTO]]("getUser")
        summary "gets a user (Internal Use Only)"
        parameter pathParam[String]("userId").required
          .description("id of the user requested")
  )

  get("/:userId", operation(getUserServiceOperation)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, UserDTO] = for {
        _ <- {
          FutureEitherHelpers.assert(isServiceClaim(request))(
            Forbidden("Internal service only")
          )
        }
        secureContainer <- getSecureContainer
        traceId <- getTraceId(request)
        userId <- paramT[Int]("userId")
        user <- {
          insecureContainer.userManager
            .get(userId)
            .coreErrorToActionResult()
        }

        storageManager = secureContainer.storageManager

        dto <- createUserDTO(user, storageManager)

        _ <- auditLogger
          .message()
          .append("user-node-id", user.nodeId)
          .append("user-id", user.id)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult

      } yield dto

      val is = result.value.map(OkResult)
    }
  }

  val getUserOperation =
    apiOperation[Option[UserDTO]]("getUser") summary "gets the current user"

  get("/", operation(getUserOperation)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, UserDTO] = for {
        secureContainer <- getSecureContainer
        traceId <- getTraceId(request)
        loggedInUser = secureContainer.user

        storageManager = secureContainer.storageManager

        dto <- createUserDTO(loggedInUser, storageManager)

        _ <- auditLogger
          .message()
          .append("user-id", loggedInUser.id)
          .append("user-node-id", loggedInUser.nodeId)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult

      } yield dto

      val is = result.value.map(OkResult)
    }
  }

  val updateUserOperation = (apiOperation[Option[UserDTO]]("updateUser")
    summary "update an existing user"
    parameter bodyParam[UpdateUserRequest]("user").required)

  put("/", operation(updateUserOperation)) {
    new AsyncResult {
      val userToSave = parsedBody.extract[UpdateUserRequest]

      val result: EitherT[Future, ActionResult, UserDTO] = for {
        secureContainer <- getSecureContainer
        traceId <- getTraceId(request)
        loggedInUser = secureContainer.user

        preferredOrganizationId <- insecureContainer.userManager
          .getPreferredOrganizationId(
            userToSave.organization,
            loggedInUser.preferredOrganizationId
          )(insecureContainer.organizationManager, executor)
          .orError

        colorCheck = Settings.userColors.contains(
          userToSave.color.getOrElse(loggedInUser.color)
        )

        degreeFromParsedBody = userToSave.degree.map(Degree.withName)

        /*
        Passing `degree=null` in the request body should erase the current degree.
        Excluding the degree from the request body should not change the degree.
        Both these cases de-serialize to `None`  so we must parse the request body
        by hand to tell the difference.
         */

        degree = degreeFromParsedBody.orElse(
          if (parsedBody.hasField("degree")) {
            None
          } else {
            loggedInUser.degree
          }
        )

        updatedUser = loggedInUser.copy(
          firstName = userToSave.firstName.getOrElse(loggedInUser.firstName),
          lastName = userToSave.lastName.getOrElse(loggedInUser.lastName),
          middleInitial =
            userToSave.middleInitial.orElse(loggedInUser.middleInitial),
          degree = degree,
          credential = userToSave.credential.getOrElse(loggedInUser.credential),
          preferredOrganizationId = preferredOrganizationId,
          url = userToSave.url.getOrElse(loggedInUser.url),
          email = userToSave.email.getOrElse(loggedInUser.email),
          color = colorCheck match {
            case true => userToSave.color.getOrElse(loggedInUser.color)
            case false => loggedInUser.color
          }
        )

        storageServiceClient = secureContainer.storageManager
        newUser <- insecureContainer.userManager.update(updatedUser).orError
        storageMap <- storageServiceClient
          .getStorage(susers, List(newUser.id))
          .orError
        storage = storageMap.get(newUser.id).flatten
        dto <- Builders
          .userDTO(newUser, storage)(
            insecureContainer.organizationManager,
            insecureContainer.pennsieveTermsOfServiceManager,
            insecureContainer.customTermsOfServiceManager,
            executor
          )
          .orError

        _ <- auditLogger
          .message()
          .append("user-node-id", loggedInUser.nodeId)
          .append("user-id", loggedInUser.id)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult

      } yield dto

      val is = result.value.map(OkResult)
    }
  }

  val apiHost: String = insecureContainer.config.getString("pennsieve.api_host")
  val switchOrganizationOperation = (apiOperation[Option[UserDTO]](
    "switchOrganization"
  )
    summary "switch the organization active for the user and session"
    parameter pathParam[String]("organizationId").required
      .description("the organization id to switch to"))

  put(
    "/organization/:organizationId/switch",
    operation(switchOrganizationOperation)
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, String] = for {
        secureContainer <- getSecureContainer
        user = secureContainer.user
        sessionId <- getSessionId(request)
          .orBadRequest("request must be session-based")
          .toEitherT[Future]

        organizationId <- param[String]("organizationId").toEitherT[Future]

        organization <- if (user.isSuperAdmin) {
          secureContainer.organizationManager
            .getByNodeId(organizationId)
            .orNotFound
        } else {
          insecureContainer.userManager
            .getOrganizationByNodeId(user, organizationId)
            .orNotFound
        }
      } yield
        s"$apiHost/session/switch-organization?organization_id=${organization.id}&api_key=$sessionId"

      val is = result.value.map {
        case Right(location) => MovedPermanently(location)
        case Left(response) => response
      }
    }
  }

  // Create authy account endpoint
  val createTwoFactorOperation = (apiOperation[AuthyUserResponse](
    "createTwoFactor"
  )
    summary "create two factor for the current user"
    parameter bodyParam[AddAuthyRequest]("twoFactorCredentials").required)

  post("/twofactor", operation(createTwoFactorOperation)) {
    new AsyncResult {
      val authyDetails = parsedBody.extract[AddAuthyRequest]

      val result: EitherT[Future, ActionResult, AuthyUserResponse] = for {
        secureContainer <- getSecureContainer
        traceId <- getTraceId(request)
        loggedInUser = secureContainer.user

        _ <- if (loggedInUser.authyId == 0)
          Right(loggedInUser).toEitherT[Future]
        else
          Left(BadRequest(Error("Two factor already setup"))).toEitherT[Future]

        phoneNumber <- authyDetails.phoneNumber
          .orBadRequest("No phone number specified")
          .toEitherT[Future]
        countryCode <- authyDetails.countryCode
          .orBadRequest("No country code specified")
          .toEitherT[Future]

        authyUser <- AuthyManager
          .createAuthyUser(loggedInUser, phoneNumber, countryCode)(
            authyClient.getUsers
          )
          .toEitherT[Future]
        updatedUser = loggedInUser.copy(authyId = authyUser.getId)

        savedUser <- insecureContainer.userManager.update(updatedUser).orError
        dto <- Builders
          .userDTO(
            savedUser,
            storage = None,
            pennsieveTermsOfService = None,
            customTermsOfService = Seq.empty
          )(secureContainer.organizationManager, executor)
          .orError

        _ <- auditLogger
          .message()
          .append("user-node-id", loggedInUser.nodeId)
          .append("user-id", loggedInUser.id)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult

      } yield AuthyUserResponse(dto.authyId)

      val is = result.value.map(OkResult)
    }
  }

  // Delete authy account endpoint
  val deleteTwoFactorOperation = (apiOperation[Unit]("deleteTwoFactor")
    summary "delete two factor for the current user")

  delete("/twofactor", operation(deleteTwoFactorOperation)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, UserDTO] = for {
        secureContainer <- getSecureContainer
        traceId <- getTraceId(request)
        loggedInUser = secureContainer.user

        _ <- if (loggedInUser.authyId != 0)
          Right(loggedInUser).toEitherT[Future]
        else Left(BadRequest(Error("Two factor not setup"))).toEitherT[Future]

        updatedUser <- AuthyManager
          .deleteAuthyUser(loggedInUser)(authyClient)
          .toEitherT[Future]
        savedUser <- insecureContainer.userManager.update(updatedUser).orError
        dto <- Builders
          .userDTO(
            savedUser,
            storage = None,
            pennsieveTermsOfService = None,
            customTermsOfService = Seq.empty
          )(insecureContainer.organizationManager, executor)
          .orError

        _ <- auditLogger
          .message()
          .append("user-node-id", loggedInUser.nodeId)
          .append("user-id", loggedInUser.id)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult
      } yield dto

      val is = result.value.map(
        u =>
          HandleResult(u) { _ =>
            Ok()
          }
      )
    }
  }

  val createORCIDOperation = (apiOperation[OrcidDTO]("createORCID") summary "associate an ORCID with a user using the orcid authorization code"
    parameter bodyParam[ORCIDRequest]("orcid").required)

  post("/orcid", operation(createORCIDOperation)) {
    new AsyncResult {

      val result: EitherT[Future, ActionResult, OrcidDTO] = for {
        secureContainer <- getSecureContainer
        loggedInUser = secureContainer.user

        orcidRequest <- extractOrErrorT[ORCIDRequest](parsedBody)

        _ <- if (loggedInUser.orcidAuthorization.isEmpty)
          Right(()).toEitherT[Future]
        else
          Left(
            BadRequest(
              Error(
                "ORCID id already configured. Please delete to set a new one"
              )
            )
          ).toEitherT[Future]

        orcidAuth <- EitherT.right[ActionResult](
          orcidClient.getToken(orcidRequest.authorizationCode)
        )

        updatedUser = loggedInUser.copy(orcidAuthorization = Some(orcidAuth))
        _ <- secureContainer.userManager.update(updatedUser).orError
      } yield OrcidDTO(name = orcidAuth.name, orcid = orcidAuth.orcid)

      val is = result.value.map(
        either =>
          HandleResult(either) { orcidDTO =>
            Ok(orcidDTO)
          }
      )
    }
  }

  val deleteORCIDOperation = (apiOperation[Unit]("deleteORCID")
    summary "delete orcid for the current user")

  delete("/orcid", operation(deleteORCIDOperation)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Unit] = for {
        secureContainer <- getSecureContainer
        loggedInUser = secureContainer.user

        _ <- if (loggedInUser.orcidAuthorization.isDefined)
          Right(()).toEitherT[Future]
        else
          Left(BadRequest(Error("ORCID id is not configured.")))
            .toEitherT[Future]

        updatedUser = loggedInUser.copy(orcidAuthorization = None)
        _ <- secureContainer.userManager.update(updatedUser).orError
      } yield ()

      val is = result.value.map(
        u =>
          HandleResult(u) { _ =>
            Ok()
          }
      )
    }
  }

  val updatePennsieveTermsOfServiceOperation = (apiOperation[Option[UserDTO]](
    "updatePennsieveTermsOfService"
  )
    summary "update an existing user's pennsieve terms of service version"
    parameter bodyParam[UpdatePennsieveTermsOfServiceRequest].required)

  put(
    "/pennsieve-terms-of-service",
    operation(updatePennsieveTermsOfServiceOperation)
  ) {
    new AsyncResult {
      val newVersion =
        parsedBody.extract[UpdatePennsieveTermsOfServiceRequest].version

      val result: EitherT[Future, ActionResult, UserDTO] = for {
        secureContainer <- getSecureContainer
        dateVersion <- DateVersion
          .from(newVersion)
          .toEitherT[Future]
          .leftMap(_ => BadRequest(s"Invalid version format: $newVersion"))
        newTerms <- secureContainer.pennsieveTermsOfServiceManager
          .setNewVersion(secureContainer.user.id, dateVersion.toZonedDateTime)
          .coreErrorToActionResult
        dto <- Builders
          .userDTO(
            secureContainer.user,
            storage = None,
            pennsieveTermsOfService = Some(newTerms.toDTO),
            customTermsOfService = Seq.empty
          )(insecureContainer.organizationManager, executor)
          .orError
      } yield dto

      val is = result.value.map(OkResult)
    }
  }

  val acceptCustomTermsOfService = (apiOperation[UserDTO](
    "acceptCustomTermsOfService"
  )
    summary "marks the user as having accepted a custom terms of service"
    parameter bodyParam[AcceptCustomTermsOfServiceRequest].required)

  put("/custom-terms-of-service", operation(acceptCustomTermsOfService)) {
    new AsyncResult {
      val acceptRequest = parsedBody.extract[AcceptCustomTermsOfServiceRequest]
      val result: EitherT[Future, ActionResult, UserDTO] = for {
        secureContainer <- getSecureContainer
        loggedInUser = secureContainer.user
        organization = secureContainer.organization
        acceptedCustomToS <- secureContainer.customTermsOfServiceManager
          .accept(loggedInUser.id, organization.id, acceptRequest.version)
          .orBadRequest
        dto <- Builders
          .userDTO(
            secureContainer.user,
            storage = None,
            pennsieveTermsOfService = None,
            customTermsOfService =
              Seq(acceptedCustomToS.toDTO(organization.nodeId))
          )(insecureContainer.organizationManager, executor)
          .orError
      } yield dto

      val is = result.value.map(OkResult)
    }
  }
}
