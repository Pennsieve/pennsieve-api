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
import com.pennsieve.domain.PredicateError
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

  override def getByNodeId(
    nodeId: String,
    withPermission: DBPermission = DBPermission.Read
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Organization] =
    state.organizations.values.find(_.nodeId == nodeId) match {
      case None => EitherT.leftT(NotFound(nodeId): CoreError)
      case Some(org) =>
        val ok = actor.isSuperAdmin ||
          state.orgUserPermissions
            .get((org.id, actor.id))
            .exists(_ >= withPermission)
        if (ok) EitherT.rightT(org)
        else
          EitherT.leftT(
            PermissionError(actor.nodeId, withPermission, org.nodeId): CoreError
          )
    }

  override def getTeamWithOrganizationTeamByNodeId(
    organization: Organization,
    teamId: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[
    Future,
    CoreError,
    (com.pennsieve.models.Team, com.pennsieve.models.OrganizationTeam)
  ] = {
    val team = state.teams.collectFirst {
      case ((orgId, _), t) if orgId == organization.id && t.nodeId == teamId =>
        t
    }
    team match {
      case Some(t) =>
        val systemTeam =
          if (state.publisherTeamByOrg.get(organization.id).contains(t.id))
            Some(com.pennsieve.models.SystemTeamType.Publishers)
          else None
        EitherT.rightT(
          (
            t,
            com.pennsieve.models.OrganizationTeam(
              organizationId = organization.id,
              teamId = t.id,
              permission = DBPermission.Delete,
              systemTeamType = systemTeam
            )
          )
        )
      case None => EitherT.leftT(NotFound(teamId))
    }
  }

  override def getUserPermission(
    organization: Organization,
    user: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Option[DBPermission]] =
    EitherT.rightT(state.orgUserPermissions.get((organization.id, user.id)))

  override def getPublisherTeam(
    organization: Organization
  )(implicit
    ec: ExecutionContext
  ): EitherT[
    Future,
    CoreError,
    (com.pennsieve.models.Team, com.pennsieve.models.OrganizationTeam)
  ] = {
    val teamId = state.publisherTeamByOrg.get(organization.id) match {
      case Some(id) => id
      case None =>
        val id = state.newId()
        val team = com.pennsieve.models.Team(
          nodeId = com.pennsieve.models.NodeCodes
            .generateId(com.pennsieve.models.NodeCodes.teamCode),
          name = "Publishers",
          id = id
        )
        state.teams.put((organization.id, id), team)
        state.publisherTeamByOrg.put(organization.id, id)
        id
    }
    val team = state.teams((organization.id, teamId))
    EitherT.rightT(
      (
        team,
        com.pennsieve.models.OrganizationTeam(
          organizationId = organization.id,
          teamId = team.id,
          permission = DBPermission.Delete,
          systemTeamType = Some(com.pennsieve.models.SystemTeamType.Publishers)
        )
      )
    )
  }

  override def isPublisher(
    organization: Organization
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Boolean] = {
    state.publisherTeamByOrg.get(organization.id) match {
      case Some(teamId) =>
        EitherT.rightT(
          state.teamMemberships.contains((organization.id, teamId, actor.id))
        )
      case None => EitherT.rightT(false)
    }
  }

  override def getPublishingTeamMembers(
    organization: Organization
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[User]] = {
    val ids = state.publisherTeamByOrg
      .get(organization.id)
      .toSeq
      .flatMap { teamId =>
        state.teamMemberships.collect {
          case ((orgId, tid, uid), _)
              if orgId == organization.id && tid == teamId =>
            uid
        }
      }
    EitherT.rightT(ids.flatMap(state.users.get))
  }

  override def addGuestUser(
    organization: Organization,
    user: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, OrganizationUser] = {
    state.orgUserPermissions.put((organization.id, user.id), DBPermission.Guest)
    EitherT.rightT(
      OrganizationUser(
        organizationId = organization.id,
        userId = user.id,
        permission = DBPermission.Guest
      )
    )
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
    EitherT.rightT(
      state.featureFlags.getOrElse((organizationId, feature), false)
    )

  override def getBySlug(
    slug: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Organization] =
    state.organizations.values.find(_.slug.equalsIgnoreCase(slug)) match {
      case Some(o) => EitherT.rightT(o)
      case None => EitherT.leftT(NotFound(s"Organization with slug ($slug)"))
    }

  override def addUser(
    organization: Organization,
    user: User,
    permission: DBPermission
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, OrganizationUser] = {
    val ou = OrganizationUser(organization.id, user.id, permission)
    state.orgUsers.put((organization.id, user.id), ou)
    state.orgUserPermissions.put((organization.id, user.id), permission)
    // Mirror the real manager's side effect: when a user is added to an
    // organization, upgrade any contributor with the same email to point at
    // this user. (See OrganizationManager.upgradeContributor.)
    if (user.email.nonEmpty) {
      state.contributors.foreach {
        case (key @ (orgId, _), c)
            if orgId == organization.id &&
              c.email.exists(_.equalsIgnoreCase(user.email)) =>
          state.contributors.put(key, c.copy(userId = Some(user.id)))
        case _ => ()
      }
    }
    EitherT.rightT(ou)
  }

  override def removeUser(
    organization: Organization,
    user: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] = {
    state.orgUsers.remove((organization.id, user.id))
    state.orgUserPermissions.remove((organization.id, user.id))
    EitherT.rightT(())
  }

  override def getUsers(
    organization: Organization
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[User]] =
    EitherT.rightT(
      state.orgUsers
        .collect { case ((orgId, uid), _) if orgId == organization.id => uid }
        .flatMap(state.users.get)
        .toList
        .sortBy(_.id)
    )

  override def getOrganizationUsers(
    organization: Organization
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[(User, OrganizationUser)]] =
    EitherT.rightT(
      state.orgUsers
        .collect {
          case ((orgId, uid), ou) if orgId == organization.id =>
            state.users.get(uid).map(_ -> ou)
        }
        .flatten
        .toList
        .sortBy(_._1.id)
    )

  override def getOwnersAndAdministrators(
    organization: Organization
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Seq[User], Seq[User])] = {
    val (owners, admins) = state.orgUserPermissions.toSeq
      .collect {
        case ((orgId, uid), p)
            if orgId == organization.id &&
              (p == DBPermission.Owner || p == DBPermission.Administer) =>
          (uid, p)
      }
      .partition(_._2 == DBPermission.Owner)
    val ownersUsers = owners.flatMap { case (uid, _) => state.users.get(uid) }
    val adminUsers = admins.flatMap { case (uid, _) => state.users.get(uid) }
    EitherT.rightT((ownersUsers, adminUsers))
  }

  override def getTeams(
    organization: Organization
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[
    (com.pennsieve.models.Team, com.pennsieve.models.OrganizationTeam)
  ]] = {
    // Make sure publisher team exists (mirrors real-DB seed behavior).
    if (!state.publisherTeamByOrg.contains(organization.id)) {
      val pid = state.newId()
      state.teams.put(
        (organization.id, pid),
        com.pennsieve.models.Team(
          nodeId = com.pennsieve.models.NodeCodes
            .generateId(com.pennsieve.models.NodeCodes.teamCode),
          name = "Publishers",
          id = pid
        )
      )
      state.publisherTeamByOrg.put(organization.id, pid)
    }
    val publisherTeamId = state.publisherTeamByOrg.get(organization.id)
    val rows = state.teams
      .collect {
        case ((orgId, _), t) if orgId == organization.id =>
          val systemType =
            if (publisherTeamId.contains(t.id))
              Some(com.pennsieve.models.SystemTeamType.Publishers)
            else None
          (
            t,
            com.pennsieve.models.OrganizationTeam(
              organizationId = organization.id,
              teamId = t.id,
              permission = DBPermission.Delete,
              systemTeamType = systemType
            )
          )
      }
      .toList
      .sortBy(_._1.id)
    EitherT.rightT(rows)
  }

  override def getTeamByNodeId(
    organization: Organization,
    teamId: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, com.pennsieve.models.Team] =
    state.teams.values.find(_.nodeId == teamId) match {
      case Some(t) => EitherT.rightT(t)
      case None => EitherT.leftT(NotFound(teamId))
    }

  override def update(
    organization: Organization
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Organization] = {
    state.organizations.put(organization.id, organization)
    EitherT.rightT(organization.copy(updatedAt = java.time.ZonedDateTime.now()))
  }

  override def update(
    organizationNodeId: String,
    details: com.pennsieve.managers.UpdateOrganization
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Organization] = {
    state.organizations.values.find(_.nodeId == organizationNodeId) match {
      case None => EitherT.leftT(NotFound(organizationNodeId): CoreError)
      case Some(org) =>
        for {
          // Subscription updates require Owner permission.
          _ <- details.subscription match {
            case Some(sub) =>
              if (state.orgUserPermissions
                  .get((org.id, actor.id))
                  .exists(_ == DBPermission.Owner) || actor.isSuperAdmin) {
                state.subscriptions.put(org.id, sub)
                EitherT.rightT[Future, CoreError](())
              } else
                EitherT.leftT[Future, Unit](
                  PermissionError(actor.nodeId, DBPermission.Owner, org.nodeId): CoreError
                )
            case None => EitherT.rightT[Future, CoreError](())
          }

          updated = org.copy(
            name = details.name.getOrElse(org.name),
            colorTheme = details.colorTheme.orElse(org.colorTheme)
          )
          _ = state.organizations.put(org.id, updated)
        } yield updated
    }
  }

  override def updateUserPermission(
    organization: Organization,
    user: User,
    permission: DBPermission
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] = {
    if (user.isIntegrationUser)
      EitherT.leftT(
        PredicateError("Cannot update permission of an integration user"): CoreError
      )
    else {
      state.orgUserPermissions.put((organization.id, user.id), permission)
      state.orgUsers
        .get((organization.id, user.id))
        .foreach(
          ou =>
            state.orgUsers
              .put((organization.id, user.id), ou.copy(permission = permission))
        )
      EitherT.rightT(())
    }
  }

  override def getSubscription(
    organizationId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, com.pennsieve.models.Subscription] =
    EitherT.rightT(
      state.subscriptions.getOrElseUpdate(
        organizationId,
        com.pennsieve.models.Subscription(
          organizationId = organizationId,
          status = com.pennsieve.models.SubscriptionStatus.ConfirmedSubscription,
          `type` = None,
          acceptedForOrganization = None,
          acceptedByUser = None,
          acceptedBy = None
        )
      )
    )

  override def updateSubscription(
    subscription: com.pennsieve.models.Subscription
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] =
    if (state.orgUserPermissions
        .get((subscription.organizationId, actor.id))
        .exists(_ == DBPermission.Owner) || actor.isSuperAdmin) {
      state.subscriptions.put(subscription.organizationId, subscription)
      EitherT.rightT(1)
    } else
      EitherT.leftT(
        PermissionError(
          actor.nodeId,
          DBPermission.Owner,
          subscription.organizationId.toString
        ): CoreError
      )

  override def getCustomTermsOfServiceVersion(
    nodeId: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, java.time.ZonedDateTime] =
    state.organizations.values.find(_.nodeId == nodeId) match {
      case Some(org) =>
        org.customTermsOfServiceVersion match {
          case Some(v) => EitherT.rightT(v)
          case None => EitherT.leftT(NotFound("custom ToS version"): CoreError)
        }
      case None => EitherT.leftT(NotFound(nodeId): CoreError)
    }

  override def updateCustomTermsOfServiceVersion(
    nodeId: String,
    version: java.time.ZonedDateTime
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] = {
    state.organizations.values.find(_.nodeId == nodeId).foreach { o =>
      state.organizations
        .put(o.id, o.copy(customTermsOfServiceVersion = Some(version)))
    }
    EitherT.rightT(())
  }

  override def getInvites(
    organization: Organization
  )(implicit
    userInviteManager: com.pennsieve.managers.UserInviteManager,
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Iterable[com.pennsieve.models.UserInvite]] =
    userInviteManager.getByOrganization(organization).map(identity)

  override def deleteInvite(
    organizationNodeId: String,
    inviteId: String
  )(implicit
    userInviteManager: com.pennsieve.managers.UserInviteManager,
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] =
    for {
      invite <- userInviteManager.getByNodeId(inviteId)
      _ <- userInviteManager.delete(invite)
    } yield ()
}
