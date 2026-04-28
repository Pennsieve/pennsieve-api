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

package com.pennsieve.helpers.fakes

import cats.data.EitherT
import com.pennsieve.domain.{ CoreError, NotFound, PermissionError }
import com.pennsieve.managers.SecureOrganizationManager
import com.pennsieve.models.{
  DBPermission,
  Feature,
  FeatureFlag,
  Organization,
  OrganizationUser,
  User
}
import com.pennsieve.traits.PostgresProfile.api.Database

import scala.concurrent.{ ExecutionContext, Future }

/**
  * Backed by InMemoryState. Override only the methods a test uses; any other
  * method will hit the trait's concrete implementation, call `db`, and fail
  * loudly with a clear message — that loud failure is the point.
  */
class FakeSecureOrganizationManager(state: InMemoryState, val actor: User)
    extends SecureOrganizationManager {

  def db: Database =
    sys.error(
      "FakeSecureOrganizationManager: a method not yet stubbed by your test " +
        "tried to use the database. Override the method on this fake (or a " +
        "subclass) to add the behavior your test needs."
    )

  override def get(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Organization] =
    state.organizations.get(id) match {
      case Some(org) => EitherT.rightT(org)
      case None => EitherT.leftT(NotFound(s"Organization ($id)"))
    }

  override def getByNodeId(
    nodeId: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Organization] =
    state.organizations.values.find(_.nodeId == nodeId) match {
      case Some(org) => EitherT.rightT(org)
      case None => EitherT.leftT(NotFound(nodeId))
    }

  override def hasPermission(
    organization: Organization,
    permission: DBPermission
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] =
    if (actor.isSuperAdmin) EitherT.rightT(())
    else
      state.orgUserPermissions.get((organization.id, actor.id)) match {
        case Some(p) if p >= permission => EitherT.rightT(())
        case _ =>
          EitherT.leftT(
            PermissionError(actor.nodeId, permission, organization.nodeId)
          )
      }

  override def getActiveFeatureFlags(
    organizationId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[FeatureFlag]] =
    EitherT.rightT(Seq.empty)

  override def hasFeatureFlagEnabled(
    organizationId: Int,
    feature: Feature
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Boolean] =
    EitherT.rightT(false)
}
