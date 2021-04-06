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

package com.pennsieve.authorization.routes

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{ HttpHeader, HttpResponse }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.pennsieve.db._
import com.pennsieve.domain.FeatureNotEnabled
import com.pennsieve.dtos.{ Builders, UserDTO }
import com.pennsieve.models._
import com.typesafe.scalalogging.LazyLogging
import slick.dbio.{ DBIOAction, Effect, NoStream }
import slick.sql.FixedSqlStreamingAction
import cats.implicits._
import com.pennsieve.akka.http.RouteService
import com.pennsieve.auth.middleware.{
  CognitoSession,
  DatasetId,
  DatasetNodeId,
  EncryptionKeyId,
  Jwt,
  OrganizationId,
  OrganizationNodeId,
  UserClaim,
  UserId,
  UserNodeId
}
import com.pennsieve.authorization.Router.ResourceContainer
import com.pennsieve.authorization.utilities.exceptions._
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.db.{ DatasetsMapper, OrganizationUserMapper, UserMapper }
import com.pennsieve.models.{ Organization, User }
import com.pennsieve.traits.PostgresProfile.api._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import shapeless.syntax.inject._

import net.ceedubs.ficus.Ficus._

import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

class AuthorizationRoutes(
  user: User,
  organization: Organization,
  cognitoId: Option[CognitoId]
)(implicit
  container: ResourceContainer,
  executionContext: ExecutionContext,
  system: ActorSystem
) extends RouteService
    with LazyLogging {

  import AuthorizationQueries._

  val routes: Route = pathPrefix("authorization") {
    authorization
  } ~ pathPrefix("session") { switchOrganization }

  def authorization: Route =
    (pathEndOrSingleSlash & get & parameters(
      'organization_id
        .as[String]
        .?, // Either an organization ID (integer) or a node ID ("N:organization:123-456...")
      'dataset_id
        .as[String]
        .? // Either a dataset ID (integer) or a node ID ("N:dataset:123-456...")
    )) { (organizationId, datasetId) =>
      val result: Future[Jwt.Token] = for {
        _ <- organizationId.traverse(
          assertOrganizationIdMatches(user, organization, _)
        )
        organizationRole <- getOrganizationRole(user, organization)
        datasetRole <- datasetId.traverse(getDatasetRole(user, organization, _))
      } yield {
        val roles =
          List(organizationRole.some, datasetRole).flatten
        val userClaim = getUserClaim(user, roles, cognitoId)
        val claim =
          Jwt.generateClaim(userClaim, container.duration)

        Jwt.generateToken(claim)(container)
      }

      onComplete(result) {
        case Success(token) =>
          complete(
            HttpResponse(
              status = OK,
              // TODO: use the original Authorization header when the ability
              // to override the HttpHeader renderInResponse property is possible.
              // Currently it is set to false and is not returned by the web-server
              // and the Authorization header class is defined as a `final` and
              // thus cannot be extended.
              //
              // headers = immutable.Seq[HttpHeader](Authorization(OAuth2BearerToken(token.value)))
              headers = immutable.Seq[HttpHeader](
                RawHeader("Authorization", s"Bearer ${token.value}")
              )
            )
          )

        case Failure(exception) => complete(exception.toResponse)
      }
    }

  def switchOrganization: Route =
    (path("switch-organization") & parameters('organization_id.as[String]) & put) {
      (organizationId) =>
        val result: Future[UserDTO] = for {

          // Only users logged in from the browser / user pool can switch organizations
          _ <- cognitoId.map(_.asUserPoolId) match {
            case Some(Right(_)) => Future.successful(())
            case _ => Future.failed(NonBrowserSession)
          }
          organizationToSwitchTo <- getOrganization(user, organizationId)

          updatedUser <- updateUserPreferredOrganization(
            user,
            organizationToSwitchTo
          )

          userDTO <- Builders
            .userDTO(updatedUser, storage = None)(
              container.organizationManager,
              container.pennsieveTermsOfServiceManager,
              container.customTermsOfServiceManager,
              executionContext
            )
            .value
            .flatMap {
              case Right(dto) => Future.successful(dto)
              case Left(error) => Future.failed(error)
            }
        } yield userDTO

        onComplete(result) {
          case Success(userDTO) =>
            complete((OK, userDTO))

          case Failure(exception) => complete(exception.toResponse)
        }
    }

  private def getUserClaim(
    user: User,
    roles: List[Jwt.Role],
    cognitoId: Option[CognitoId]
  ): UserClaim = {
    val cognitoSession = cognitoId.map {
      case id: CognitoId.TokenPoolId => CognitoSession.API(id)
      case id: CognitoId.UserPoolId => CognitoSession.Browser(id)
    }

    UserClaim(
      id = UserId(user.id),
      roles = roles,
      cognito = cognitoSession,
      node_id = Some(UserNodeId(user.nodeId))
    )
  }

}

private[routes] object AuthorizationQueries {

  /**
    * Updates the preferred organization for a user given an organization
    *
    * @param organization
    * @return
    */
  def updateUserPreferredOrganization(
    user: User,
    preferredOrganization: Organization
  )(implicit
    container: ResourceContainer,
    executionContext: ExecutionContext
  ): Future[User] = {
    val query = for {
      _ <- UserMapper
        .filter(_.id === user.id)
        .map(_.preferredOrganizationId)
        .update(Some(preferredOrganization.id))
      updatedUser <- UserMapper.getById(user.id)
      result <- updatedUser match {
        case Some(u) => DBIO.successful(u)
        case None => DBIO.failed(new Exception("User not found."))
      }
    } yield result

    container.db.run(query)
  }

  /**
    * Retrieves an organization if the user has permission to access it.
    *
    * Used to switch organizations within a session.
    *
    * @param organizationId
    * @return
    */
  def getOrganization(
    user: User,
    organizationId: String // integer or node ID
  )(implicit
    container: ResourceContainer,
    executionContext: ExecutionContext
  ): Future[Organization] = {
    val query = (parseId(organizationId) match {

      case Left(nodeId) if user.isSuperAdmin =>
        OrganizationsMapper.getByNodeId(nodeId)

      case Right(intId) if user.isSuperAdmin =>
        OrganizationsMapper.get(intId)

      case Left(nodeId) =>
        OrganizationsMapper
          .getByNodeId(user)(nodeId)
          .result
          .headOption
          .map(_.map(_._1))

      case Right(intId) =>
        OrganizationsMapper
          .get(user)(intId)
          .result
          .headOption
          .map(_.map(_._1))
    }).flatMap {
      case Some(organization) => DBIO.successful(organization)
      case None => DBIO.failed(new OrganizationNotFound(organizationId))
    }

    container.db.run(query)
  }

  /**
    * Compare the organization in the URL, if it exists, with the organization
    * in the session. These must match.
    */
  def assertOrganizationIdMatches(
    user: User,
    organization: Organization,
    organizationId: String
  ): Future[Unit] =
    parseId(organizationId) match {
      case Left(nodeId) if organization.nodeId == nodeId =>
        Future.successful(())
      case Right(id) if organization.id == id => Future.successful(())
      case _ if user.isSuperAdmin => Future.successful(())
      case _ => Future.failed(new InvalidOrganizationId(organizationId))
    }

  /**
    * Parse an id to either an node id or an integer id.
    */
  private def parseId(idOrNodeId: String): Either[String, Int] =
    Try(idOrNodeId.toInt).toEither.leftMap(_ => idOrNodeId)

  def getOrganizationRole(
    user: User,
    organization: Organization
  )(implicit
    container: ResourceContainer,
    executionContext: ExecutionContext
  ): Future[Jwt.OrganizationRole] = {
    def getPermission: DBIOAction[DBPermission, NoStream, Effect.Read] =
      if (user.isSuperAdmin)
        DBIO.successful(DBPermission.Owner)
      else
        OrganizationUserMapper
          .getBy(user.id, organization.id)
          .map(_.permission)
          .result
          .headOption
          .flatMap {
            case Some(permission) => DBIO.successful(permission)
            case None => DBIO.failed(new InvalidSession(user, organization))
          }

    def getEncryptionKeyId(
      permission: DBPermission
    ): DBIOAction[Option[String], NoStream, Effect.All] = {
      if (permission.value >= DBPermission.Write.value) {
        OrganizationsMapper
          .get(organization.id)
          .map(_.flatMap(_.encryptionKeyId))
      } else {
        DBIOAction.successful(None)
      }
    }

    def getEnabledFeatures
      : FixedSqlStreamingAction[Seq[Feature], Feature, Effect.Read] =
      FeatureFlagsMapper.getActiveFeatures(organization.id).result

    val result: Future[(Role, Option[String], Seq[Feature])] =
      container.db
        .run(for {
          permission <- getPermission
          role <- {
            permission.toRole match {
              case Some(role) => DBIO.successful(role)
              case None => DBIO.failed(new InvalidSession(user, organization))
            }
          }
          encryptionKeyId <- getEncryptionKeyId(permission)
          features <- getEnabledFeatures
        } yield (role, encryptionKeyId, features))

    result.map {
      case (role, encryptionKeyId, features) =>
        Jwt.OrganizationRole(
          OrganizationId(organization.id)
            .inject[Jwt.Role.RoleIdentifier[OrganizationId]],
          role,
          encryptionKeyId.map(EncryptionKeyId),
          OrganizationNodeId(organization.nodeId).some,
          features.toList.some
        )
    }
  }

  /**
    * Look up a dataset role by an integer dataset ID or a dataset node ID.
    *
    * @param datasetId
    * @return
    */
  def getDatasetRole(
    user: User,
    organization: Organization,
    datasetId: String
  )(implicit
    container: ResourceContainer,
    executionContext: ExecutionContext
  ): Future[Jwt.DatasetRole] = {
    implicit val datasets: DatasetsMapper = new DatasetsMapper(organization)
    implicit val datasetUser: DatasetUserMapper = new DatasetUserMapper(
      organization
    )
    implicit val datasetTeam: DatasetTeamMapper = new DatasetTeamMapper(
      organization
    )

    val result: Future[(Dataset, Boolean, Role)] = {

      val dsId: Either[String, Int] = parseId(datasetId)

      if (user.isSuperAdmin) {
        val fetchDataset = dsId match {
          case Left(nodeId) => datasets.getByNodeIds(Set(nodeId))
          case Right(intId) => datasets.get(intId)
        }
        container.db
          .run(fetchDataset.result.headOption)
          .flatMap {
            case Some(dataset) =>
              container.db
                .run(datasets.isLocked(dataset, user))
                .map(locked => (dataset, locked, Role.Owner))
            case None => throw new InvalidDatasetId(user, datasetId)
          }
      } else {
        val fetchDataset = dsId match {
          case Left(nodeId) => datasets.datasetWithRoleByNodeId(user.id, nodeId)
          case Right(intId) => datasets.datasetWithRole(user.id, intId)
        }
        container.db
          .run(fetchDataset)
          .flatMap {
            case Some((dataset, Some(role))) =>
              container.db
                .run(datasets.isLocked(dataset, user))
                .map(locked => (dataset, locked, role))
            case _ => throw new InvalidDatasetId(user, datasetId)
          }
      }
    }

    result.map {
      case (dataset, locked, role) =>
        Jwt.DatasetRole(
          DatasetId(dataset.id).inject[Jwt.Role.RoleIdentifier[DatasetId]],
          role,
          DatasetNodeId(dataset.nodeId).some,
          Some(locked)
        )
    }
  }
}
