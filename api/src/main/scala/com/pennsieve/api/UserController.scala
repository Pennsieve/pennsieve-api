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

import cats.data._
import cats.implicits._
import com.pennsieve.audit.middleware.Auditor
import com.pennsieve.aws.cognito.CognitoClient
import com.pennsieve.aws.email.Email
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
import com.pennsieve.managers.StorageServiceClientTrait
import com.pennsieve.models.{
  CognitoId,
  DateVersion,
  Degree,
  OrcidIdentityProvider,
  User
}
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
  color: Option[String],
  userRequestedChange: Option[Boolean] = None
)

case class UserMergeRequest(
  email: String,
  cognitoId: String,
  password: Option[String]
)

case class UpdatePennsieveTermsOfServiceRequest(version: String)

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
  asyncExecutor: ExecutionContext,
  orcidClient: OrcidClient,
  cognitoClient: CognitoClient
)(implicit
  val swagger: Swagger
) extends ScalatraServlet
    with AuthenticatedController {

  override val pennsieveSwaggerTag = "User"

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
          .orError()
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
          .orError()
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
        secureContainer <- getSecureContainer()
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
          .coreErrorToActionResult()

      } yield dto

      val is = result.value.map(OkResult)
    }
  }

  val getUserByEmailServiceOperation =
    (
      apiOperation[Option[UserDTO]]("getUserByEmail")
        summary "gets a user by email address"
        parameter pathParam[String]("email").required
          .description("email of the user requested")
  )

  get("/email/:email", operation(getUserByEmailServiceOperation)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, UserDTO] = for {
        secureContainer <- getSecureContainer()
        loggedInUser = secureContainer.user

        traceId <- getTraceId(request)
        email <- paramT[String]("email")
        user <- {
          insecureContainer.userManager
            .getByEmail(email)
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
          .coreErrorToActionResult()

      } yield dto

      val is = result.value.map(OkResult)
    }
  }

  val getUserOperation =
    apiOperation[Option[UserDTO]]("getCurrentUser") summary "Returns the current user"

  get("/", operation(getUserOperation)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, UserDTO] = for {
        secureContainer <- getSecureContainer()
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
          .coreErrorToActionResult()

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
        secureContainer <- getSecureContainer()
        traceId <- getTraceId(request)
        loggedInUser = secureContainer.user

        preferredOrganizationId <- insecureContainer.userManager
          .getPreferredOrganizationId(
            userToSave.organization,
            loggedInUser.preferredOrganizationId
          )(insecureContainer.organizationManager, executor)
          .orError()

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
        newUser <- insecureContainer.userManager.update(updatedUser).orError()
        storageMap <- storageServiceClient
          .getStorage(susers, List(newUser.id))
          .orError()
        storage = storageMap.get(newUser.id).flatten
        dto <- Builders
          .userDTO(newUser, storage)(
            insecureContainer.organizationManager,
            insecureContainer.pennsieveTermsOfServiceManager,
            insecureContainer.customTermsOfServiceManager,
            executor
          )
          .orError()

        _ <- auditLogger
          .message()
          .append("user-node-id", loggedInUser.nodeId)
          .append("user-id", loggedInUser.id)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult()

      } yield dto

      val is = result.value.map(OkResult)
    }
  }

  val updateUserEmailOperation = (apiOperation[Option[UserDTO]](
    "updateUserEmail"
  )
    summary "update an existing user's email address"
    parameter bodyParam[UpdateUserRequest]("user").required)

  put("/email", operation(updateUserEmailOperation)) {
    new AsyncResult {
      val userToSave = parsedBody.extract[UpdateUserRequest]

      val result: EitherT[Future, ActionResult, UserDTO] = for {
        secureContainer <- getSecureContainer()
        traceId <- getTraceId(request)
        loggedInUser = secureContainer.user
        oldEmail = loggedInUser.email
        newEmail = userToSave.email.getOrElse(loggedInUser.email)
        userRequestedChange = userToSave.userRequestedChange.getOrElse(false)
        transactionId = loggedInUser.cognitoId.get.toString

        // make sure old email and new email are different
        _ <- {
          FutureEitherHelpers.assert(!oldEmail.equals(newEmail))(
            BadRequest("old and new email addresses are the same")
          )
        }

        // update the user in Pennsieve database
        storageServiceClient = secureContainer.storageManager
        newUser <- insecureContainer.userManager
          .updateEmail(loggedInUser, newEmail)
          .orError()
        storageMap <- storageServiceClient
          .getStorage(susers, List(newUser.id))
          .orError()
        storage = storageMap.get(newUser.id).flatten
        dto <- Builders
          .userDTO(newUser, storage)(
            insecureContainer.organizationManager,
            insecureContainer.pennsieveTermsOfServiceManager,
            insecureContainer.customTermsOfServiceManager,
            executor
          )
          .orError()

        // update the Cognito User
        _ <- cognitoClient
          .updateUserAttributes(
            loggedInUser.cognitoId.get.toString,
            Map("email" -> newUser.email, "email_verified" -> "true")
          )
          .toEitherT
          .coreErrorToActionResult()

        // send 'email changed' messages to old and new email addresses
        messageToOld = insecureContainer.messageTemplates
          .emailAddressChanged(
            previousEmailAddress = oldEmail,
            currentEmailAddress = newEmail,
            transactionNumber = transactionId,
            emailAddress = oldEmail
          )

        messageToNew = insecureContainer.messageTemplates
          .emailAddressChanged(
            previousEmailAddress = oldEmail,
            currentEmailAddress = newEmail,
            transactionNumber = transactionId,
            emailAddress = newEmail
          )

        _ = userRequestedChange match {
          case true =>
            insecureContainer.emailer
              .sendEmail(
                to = Email(oldEmail),
                from = Settings.support_email,
                message = messageToOld,
                subject = s"Your email address has changed"
              )
              .leftMap(error => InternalServerError(error.getMessage))
              .toEitherT[Future]

            insecureContainer.emailer
              .sendEmail(
                to = Email(newEmail),
                from = Settings.support_email,
                message = messageToNew,
                subject = s"Your email address has changed"
              )
              .leftMap(error => InternalServerError(error.getMessage))
              .toEitherT[Future]

          case false =>
            Future.successful(()).toEitherT.coreErrorToActionResult()
        }

        _ <- auditLogger
          .message()
          .append("user-node-id", loggedInUser.nodeId)
          .append("user-id", loggedInUser.id)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult()

      } yield dto

      val is = result.value.map(OkResult)
    }
  }

  val mergeUsersOperation = (apiOperation[UserDTO]("merge user accounts") summary "merge user accounts"
    parameter bodyParam[UserMergeRequest]("newUserToken").required)

  put("/merge/:userId", operation(mergeUsersOperation)) {
    new AsyncResult {
      val userMergeRequest = parsedBody.extract[UserMergeRequest]

      val result: EitherT[Future, ActionResult, UserDTO] = for {
        traceId <- getTraceId(request)
        secureContainer <- getSecureContainer()
        user1 = secureContainer.user

        userId <- paramT[Int]("userId")
        user2 <- {
          insecureContainer.userManager
            .get(userId)
            .coreErrorToActionResult()
        }

        realEmail = user1.email
        realCognitoId = user2.cognitoId
        fakeEmail = s"MERGED+${realEmail}"
        fakeCognitoId = Some(
          CognitoId.UserPoolId(
            java.util.UUID
              .fromString("00000000-0000-0000-0000-000000000000")
          )
        )

        // verify parameters
        _ <- {
          FutureEitherHelpers.assert(
            user1.email == userMergeRequest.email && user2.cognitoId.get.toString == userMergeRequest.cognitoId
          )(BadRequest("Request verification failed"))
        }

        // if a password was provided, ensure it authenticates for user 1
        _ <- userMergeRequest.password match {
          case Some(password) =>
            cognitoClient
              .authenticateUser(user1.cognitoId.get.toString, password)
              .toEitherT
              .coreErrorToActionResult()
          case None =>
            Future.successful(true).toEitherT.coreErrorToActionResult()
        }

        // Cognito does not allow two users to have the same email address, so we must execute these
        // two operations sequentially. Performing the flatMap() ensures the a Future completes before
        // a subsequent Future is started. In this sequence we perform five Cognito operations consecutively.

        // update Cognito User 1 email <- fakeEmail, and then
        // update Cognito User 2 email <- realEmail
        // update Cognito User 2 email verified <- true
        // disable Cognito User 1 (so user cannot login with this identity)
        // set password on Cognito User 2 (propagate password from old user to new user)
        _ <- cognitoClient
          .updateUserAttribute(user1.cognitoId.get.toString, "email", fakeEmail)
          .flatMap(
            _ =>
              cognitoClient
                .updateUserAttribute(
                  user2.cognitoId.get.toString,
                  "email",
                  realEmail
                )
                .flatMap(
                  _ =>
                    cognitoClient
                      .updateUserAttribute(
                        user2.cognitoId.get.toString,
                        "email_verified",
                        true.toString
                      )
                      .flatMap(
                        _ =>
                          cognitoClient
                            .disableUser(user1.cognitoId.get.toString)
                            .flatMap(
                              _ =>
                                userMergeRequest.password match {
                                  case Some(password) =>
                                    cognitoClient.setUserPassword(
                                      user2.cognitoId.get.toString,
                                      password
                                    )
                                  case None => Future.successful(true)
                                }
                            )
                      )
                )
          )
          .toEitherT
          .coreErrorToActionResult()

        // update Pennsieve User 2 email <- fakeEmail
        _ <- insecureContainer.userManager
          .updateEmail(user2, fakeEmail)
          .orError()

        // update Pennsieve User 2 cognito_id <- fakeCognitoId
        _ <- insecureContainer.userManager
          .updateCognitoId(user2, fakeCognitoId)
          .orError()

        // update Pennsieve User 1 cognito_id <- realCognitoId
        mergedUser <- insecureContainer.userManager
          .updateCognitoId(user1, realCognitoId)
          .orError()

        storageServiceClient = secureContainer.storageManager
        storageMap <- storageServiceClient
          .getStorage(susers, List(user1.id))
          .orError()
        storage = storageMap.get(user1.id).flatten

        dto <- Builders
          .userDTO(mergedUser, storage)(
            insecureContainer.organizationManager,
            insecureContainer.pennsieveTermsOfServiceManager,
            insecureContainer.customTermsOfServiceManager,
            executor
          )
          .orError()

        _ <- auditLogger
          .message()
          .append("user-node-id", user1.nodeId)
          .append("user-id", user2.id)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult()

      } yield dto

      val is = result.value.map(OkResult)
    }
  }

  val createORCIDOperation = (apiOperation[OrcidDTO]("createORCID") summary "associate an ORCID with a user using the orcid authorization code"
    parameter bodyParam[ORCIDRequest]("orcid").required)

  post("/orcid", operation(createORCIDOperation)) {
    new AsyncResult {

      val result: EitherT[Future, ActionResult, OrcidDTO] = for {
        secureContainer <- getSecureContainer()
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
        _ <- secureContainer.userManager.update(updatedUser).orError()
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
        secureContainer <- getSecureContainer()
        loggedInUser = secureContainer.user

        _ <- if (loggedInUser.orcidAuthorization.isDefined)
          Right(()).toEitherT[Future]
        else
          Left(BadRequest(Error("ORCID id is not configured.")))
            .toEitherT[Future]

        orcidId = loggedInUser.orcidAuthorization.get.orcid

        _ <- cognitoClient
          .deleteUserAttributes(
            loggedInUser.email,
            List(OrcidIdentityProvider.customAttributeName)
          )
          .toEitherT
          .coreErrorToActionResult()

        hasExternalUserLink <- cognitoClient
          .hasExternalUserLink(loggedInUser.email, OrcidIdentityProvider.name)
          .toEitherT
          .coreErrorToActionResult()

//        linkedCognitoId <- hasExternalUserLink match {
//          case true =>
//            cognitoClient
//              .getCognitoId(s"orcid_${orcidId}")
//              .toEitherT
//              .coreErrorToActionResult()
//          case false =>
//            Future.successful("none").toEitherT.coreErrorToActionResult()
//        }

        _ <- hasExternalUserLink match {
          case true =>
            cognitoClient
              .unlinkExternalUser(
                OrcidIdentityProvider.name,
                OrcidIdentityProvider.attributeNameForUnlink,
                orcidId
              )
              .toEitherT
              .coreErrorToActionResult()
          case false =>
            Future.successful(()).toEitherT.coreErrorToActionResult()
        }

        _ <- hasExternalUserLink match {
          case true =>
            cognitoClient
              .deleteUser(OrcidIdentityProvider.cognitoUsername(orcidId))
              .toEitherT
              .coreErrorToActionResult()
          case false =>
            Future.successful(()).toEitherT.coreErrorToActionResult()
        }

        updatedUser = loggedInUser.copy(orcidAuthorization = None)
        _ <- secureContainer.userManager.update(updatedUser).orError()
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
        secureContainer <- getSecureContainer()
        dateVersion <- DateVersion
          .from(newVersion)
          .toEitherT[Future]
          .leftMap(_ => BadRequest(s"Invalid version format: $newVersion"))
        newTerms <- secureContainer.pennsieveTermsOfServiceManager
          .setNewVersion(secureContainer.user.id, dateVersion.toZonedDateTime)
          .coreErrorToActionResult()
        dto <- Builders
          .userDTO(
            secureContainer.user,
            storage = None,
            pennsieveTermsOfService = Some(newTerms.toDTO),
            customTermsOfService = Seq.empty
          )(insecureContainer.organizationManager, executor)
          .orError()
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
        secureContainer <- getSecureContainer()
        loggedInUser = secureContainer.user
        organization = secureContainer.organization
        acceptedCustomToS <- secureContainer.customTermsOfServiceManager
          .accept(loggedInUser.id, organization.id, acceptRequest.version)
          .orBadRequest()
        dto <- Builders
          .userDTO(
            secureContainer.user,
            storage = None,
            pennsieveTermsOfService = None,
            customTermsOfService =
              Seq(acceptedCustomToS.toDTO(organization.nodeId))
          )(insecureContainer.organizationManager, executor)
          .orError()
      } yield dto

      val is = result.value.map(OkResult)
    }
  }
}
