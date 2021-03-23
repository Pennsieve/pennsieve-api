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

package com.pennsieve.helpers

import cats.implicits._
import com.pennsieve.auth.middleware.{
  DatasetId,
  DatasetNodeId,
  EncryptionKeyId,
  Jwt,
  OrganizationId,
  OrganizationNodeId,
  ServiceClaim,
  Session,
  UserClaim,
  UserId
}
import com.pennsieve.db.{
  DatasetTeamMapper,
  DatasetUserMapper,
  DatasetsMapper,
  FeatureFlagsMapper,
  OrganizationUserMapper,
  OrganizationsMapper,
  UserMapper
}
import com.pennsieve.models.{
  DBPermission,
  Dataset,
  Feature,
  Organization,
  Role,
  User
}
import com.pennsieve.test.helpers.AwaitableImplicits._
import com.pennsieve.traits.PostgresProfile.api._
import java.util.UUID

import com.pennsieve.auth.middleware.Jwt.Role.RoleIdentifier

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.{ FiniteDuration, _ }
import shapeless.syntax.inject._
import slick.dbio.{ DBIOAction, Effect, NoStream }
import slick.sql.FixedSqlStreamingAction

object Authenticator {

  def createUserToken(
    user: User,
    organization: Organization,
    datasetId: Option[Int] = None,
    session: Session = Session.Browser(UUID.randomUUID.toString),
    duration: FiniteDuration = 60.seconds
  )(implicit
    config: Jwt.Config,
    db: Database,
    executionContext: ExecutionContext
  ): String = {
    val result: Future[Jwt.Token] = for {
      organizationRole <- getOrganizationRole(user, organization)
      datasetRole <- datasetId.traverse(
        id => getDatasetRole(user, organization, id)
      )
    } yield {
      val roles =
        List(organizationRole.some, datasetRole).flatten
      val userClaim =
        UserClaim(id = UserId(user.id), roles = roles, session = Some(session))
      val claim =
        Jwt.generateClaim(userClaim, duration)

      Jwt.generateToken(claim)
    }

    result.await.value
  }

  private def getOrganizationRole(
    user: User,
    organization: Organization
  )(implicit
    db: Database,
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
            case None =>
              DBIO.failed(
                new Exception(
                  s"User ${user.id} does not have access to ${organization.id}"
                )
              )
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
      db.run(for {
        permission <- getPermission
        role <- {
          permission.toRole match {
            case Some(role) => DBIO.successful(role)
            case None =>
              DBIO.failed(
                new Exception("could not convert permission to role.")
              )
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

  private def getDatasetRole(
    user: User,
    organization: Organization,
    datasetId: Int
  )(implicit
    db: Database,
    executionContext: ExecutionContext
  ): Future[Jwt.DatasetRole] = {
    implicit val datasets: DatasetsMapper = new DatasetsMapper(organization)
    implicit val datasetUser: DatasetUserMapper = new DatasetUserMapper(
      organization
    )
    implicit val datasetTeam: DatasetTeamMapper = new DatasetTeamMapper(
      organization
    )

    val result: Future[(Dataset, Role)] =
      if (user.isSuperAdmin) {
        db.run(
            datasets
              .get(datasetId)
              .result
              .headOption
          )
          .map {
            case Some(dataset) => (dataset, Role.Owner)
            case None => throw new Exception(s"Dataset $datasetId not found.")
          }
      } else {
        db.run(
            datasets
              .datasetWithRole(user.id, datasetId)
          )
          .map {
            case Some((dataset, Some(role))) => (dataset, role)
            case _ =>
              throw new Exception(
                s"User ${user.id} does not have access to dataset $datasetId."
              )
          }
      }

    result.map {
      case (dataset, role) =>
        Jwt.DatasetRole(
          DatasetId(dataset.id).inject[Jwt.Role.RoleIdentifier[DatasetId]],
          role,
          DatasetNodeId(dataset.nodeId).some
        )
    }
  }
}
