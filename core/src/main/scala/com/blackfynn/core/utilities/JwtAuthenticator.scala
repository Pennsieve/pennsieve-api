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

package com.pennsieve.core.utilities

import cats.data.EitherT
import cats.implicits._
import com.pennsieve.auth.middleware.Jwt.Claim
import com.pennsieve.auth.middleware.Jwt.Role.RoleIdentifier
import com.pennsieve.auth.middleware.{
  CognitoSession,
  DatasetId,
  DatasetNodeId,
  EncryptionKeyId,
  Extractor,
  Jwt,
  OrganizationId,
  OrganizationNodeId,
  ServiceClaim,
  UserClaim,
  UserId,
  UserNodeId,
  Wildcard
}
import com.pennsieve.domain.{
  CoreError,
  InvalidJWT,
  MissingOrganization,
  UnsupportedJWTClaimType
}
import com.pennsieve.aws.cognito.CognitoPayload

import com.pennsieve.models.{ CognitoId, Dataset, Organization, Role, User }
import com.pennsieve.utilities.Container
import java.time.Instant

import shapeless.syntax.inject._

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ ExecutionContext, Future }

// TODO: make cognitoId non-optional
case class UserAuthContext(
  user: User,
  organization: Organization,
  cognitoPayload: CognitoPayload
)

object JwtAuthenticator {

  /**
    * Generate a user-level JWT.
    *
    * @param duration
    * @param user
    * @param roles
    * @param jwtConfig
    * @return
    */
  def generateUserToken(
    duration: FiniteDuration,
    user: User,
    roles: List[Jwt.Role]
  )(implicit
    jwtConfig: Jwt.Config
  ): Jwt.Token = {
    val userClaim =
      UserClaim(
        id = UserId(user.id),
        roles = roles,
        cognito = None,
        node_id = Some(UserNodeId(user.nodeId))
      )
    val claim = Jwt.generateClaim(content = userClaim, duration = duration)
    Jwt.generateToken(claim)
  }

  def generateUserToken(
    duration: FiniteDuration,
    user: User,
    organization: Organization,
    organizationRole: Role,
    dataset: Dataset,
    datasetRole: Role
  )(implicit
    config: Jwt.Config
  ): Jwt.Token = {
    val roles = List(
      Jwt.OrganizationRole(
        id = OrganizationId(organization.id)
          .inject[RoleIdentifier[OrganizationId]],
        node_id = Some(OrganizationNodeId(organization.nodeId)),
        role = organizationRole,
        encryption_key_id =
          organization.encryptionKeyId.map(EncryptionKeyId.apply)
      ),
      Jwt.DatasetRole(
        id = DatasetId(dataset.id).inject[Jwt.Role.RoleIdentifier[DatasetId]],
        node_id = Some(DatasetNodeId(dataset.nodeId)),
        role = datasetRole
      )
    )
    JwtAuthenticator.generateUserToken(duration, user, roles)
  }

  /**
    * Generate a service-level JWT.,
    *
    * @param duration
    * @param organizationId
    * @param datasetId
    * @param organizationEncryptionKeyId
    * @param jwtConfig
    * @return
    */
  def generateServiceToken(
    duration: FiniteDuration,
    organizationId: Int,
    datasetId: Option[Int] = None,
    organizationEncryptionKeyId: Option[String] = None
  )(implicit
    jwtConfig: Jwt.Config
  ): Jwt.Token = {

    val organization: Jwt.Role = Jwt.OrganizationRole(
      OrganizationId(organizationId)
        .inject[RoleIdentifier[OrganizationId]],
      Role.Owner,
      encryption_key_id = organizationEncryptionKeyId.map(EncryptionKeyId.apply)
    )

    val dataset: Jwt.Role = datasetId match {
      case Some(id) =>
        Jwt.DatasetRole(
          DatasetId(id).inject[RoleIdentifier[DatasetId]],
          Role.Owner
        )
      case None =>
        Jwt.DatasetRole(Wildcard.inject[RoleIdentifier[DatasetId]], Role.Owner)
    }

    val claim =
      Jwt.generateClaim(ServiceClaim(List(organization, dataset)), duration)

    Jwt.generateToken(claim)(jwtConfig)
  }

  /**
    * Given a string, attempt to resolve the string as a JWT, returning the associated user auth context.
    *
    * The JWT is expected to be verified as a user claim with at least one defined organization that is not a
    * wildcard ("*").
    *
    * A JWT may grant a user access to multiple organizations; because of this, multiple organizations IDs may be
    * found in the claim. The first found one will be returned.
    *
    * @param container
    * @param token
    * @param ec
    * @return
    */
  def userContextFromToken(
    container: Container
      with UserManagerContainer
      with OrganizationManagerContainer,
    token: Jwt.Token
  )(implicit
    config: Jwt.Config,
    ec: ExecutionContext
  ): EitherT[Future, CoreError, UserAuthContext] = {
    for {
      claim <- Jwt
        .parseClaim(token)
        .leftMap(_ => InvalidJWT(token.value): CoreError)
        .toEitherT[Future]
      context <- userContext(container, claim)

    } yield context
  }

  /**
    * Check if the supplied claim is a user claim, extracting the (user, organization, Cognito ID) from the claim.
    *
    * @param container
    * @param claim
    * @param config
    * @param ec
    * @return
    */
  def userContext(
    container: Container
      with UserManagerContainer
      with OrganizationManagerContainer,
    claim: Claim
  )(implicit
    config: Jwt.Config,
    ec: ExecutionContext
  ): EitherT[Future, CoreError, UserAuthContext] =
    claim.content match {
      case UserClaim(UserId(userId), _, Some(cognito), _) =>
        for {
          user <- container.userManager.get(userId)
          organizationId <- Extractor
            .getHeadOrganizationIdFromClaim(claim)
            .toRight(MissingOrganization: CoreError)
            .toEitherT[Future]
          organization <- container.organizationManager.get(
            organizationId.value
          )
        } yield
          UserAuthContext(
            user = user,
            organization = organization,
            cognitoPayload =
              CognitoPayload(cognito.id, claim.issuedAt, claim.expiration)
          )
      case ServiceClaim(_) =>
        EitherT.leftT[Future, UserAuthContext](
          UnsupportedJWTClaimType("service"): CoreError
        )
    }

  /**
    * Return the role assigned to a specific organization.
    *
    * @param organizationId
    * @param claim
    * @return
    */
  def getOrganizationRole(organizationId: Int, claim: Claim): Option[Role] =
    Extractor.getRole(OrganizationId(organizationId), claim)

  /**
    * Return the role assigned to a specific dataset.
    *
    * @param datasetId
    * @param claim
    * @return
    */
  def getDatasetRole(datasetId: Int, claim: Claim): Option[Role] =
    Extractor.getRole(DatasetId(datasetId), claim)
}
