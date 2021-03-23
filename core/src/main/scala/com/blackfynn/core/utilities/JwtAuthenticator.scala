// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.core.utilities

import cats.data.EitherT
import cats.implicits._
import com.pennsieve.auth.middleware.Jwt.Claim
import com.pennsieve.auth.middleware.Jwt.Role.RoleIdentifier
import com.pennsieve.auth.middleware.{
  DatasetId,
  EncryptionKeyId,
  Extractor,
  Jwt,
  OrganizationId,
  ServiceClaim,
  Session,
  UserClaim,
  UserId,
  UserNodeId,
  Wildcard
}
import com.pennsieve.domain.{
  CoreError,
  InvalidJWT,
  MissingOrganization,
  Sessions,
  UnsupportedJWTClaimType
}
import com.pennsieve.models.{ Organization, Role, User }
import com.pennsieve.utilities.Container
import shapeless.syntax.inject._

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ ExecutionContext, Future }

case class UserAuthContext(
  user: User,
  organization: Organization,
  session: Option[String]
)

object JwtAuthenticator {

  /**
    * Generate a user-level JWT.
    *
    * @param duration
    * @param userId
    * @param roles
    * @param session
    * @param jwtConfig
    * @return
    */
  def generateUserToken(
    duration: FiniteDuration,
    user: User,
    roles: List[Jwt.Role],
    session: Option[Sessions.Session] = None
  )(implicit
    jwtConfig: Jwt.Config
  ): Jwt.Token = {
    val _session: Option[Session] = session.map { s =>
      s.`type` match {
        case Sessions.APISession(_) => Session.API(s.uuid)
        case Sessions.BrowserSession => Session.Browser(s.uuid)
        case Sessions.TemporarySession => Session.Temporary(s.uuid)
      }
    }
    val userClaim =
      UserClaim(
        id = UserId(user.id),
        roles = roles,
        session = _session,
        node_id = Some(UserNodeId(user.nodeId))
      )
    val claim = Jwt.generateClaim(content = userClaim, duration = duration)
    Jwt.generateToken(claim)
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
    * Check if the supplied claim is a user claim, extracting the (user, organization, session) from the claim.
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
      case UserClaim(UserId(userId), _, session, _) =>
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
            session = session.map(_.id)
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
