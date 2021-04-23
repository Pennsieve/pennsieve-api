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

package com.pennsieve.managers

import java.time.{ Duration, ZonedDateTime }

import cats.data._
import cats.implicits._
import com.pennsieve.aws.cognito.CognitoClient
import com.pennsieve.aws.email.{ Email, EmailToSend, Emailer }
import com.pennsieve.core.utilities.{ DatabaseHelpers, FutureEitherHelpers }
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.db._
import com.pennsieve.domain._
import com.pennsieve.managers.OrganizationManager.Invite
import com.pennsieve.models.SubscriptionStatus.PendingSubscription
import com.pennsieve.models._
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.utilities.AbstractError
import com.pennsieve.core.utilities.MessageTemplates
import io.circe.{ Encoder, Json }
import io.circe.syntax._

import org.apache.commons.validator.routines.EmailValidator
import scala.concurrent.{ ExecutionContext, Future }

object OrganizationManager {
  case class Invite(email: String, firstName: String, lastName: String)

  def apply(db: Database): OrganizationManager =
    new OrganizationManager(db)

  /**
    * Result of inviting a user to an organization. Either a new user is
    * created, or an existing user is invited to a new organization.
    */
  sealed trait AddEmailResult
  object AddEmailResult {
    case class AddedExistingUser(user: User) extends AddEmailResult
    case class InvitedNewUser(invite: UserInvite) extends AddEmailResult

    implicit def encoder: Encoder[AddEmailResult] = Encoder.instance {
      case AddedExistingUser(user) => user.asJson
      case InvitedNewUser(invite) => invite.asJson
    }
  }
}

class OrganizationManager(db: Database) {

  def get(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Organization] =
    db.run(OrganizationsMapper.filter(_.id === id).result.headOption)
      .whenNone[CoreError](NotFound(s"Organization ($id)"))

  def get(
    ids: List[Int]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[Organization]] =
    db.run(OrganizationsMapper.filter(_.id inSet ids).result)
      .map(_.toList)
      .toEitherT

  def getWithPermission(
    id: Int,
    user: User,
    withPermission: DBPermission = DBPermission.Read
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Organization] =
    for {
      result <- {
        db.run(OrganizationsMapper.get(user)(id).result.headOption)
          .whenNone(NotFound(s"Organization ($id)"))
      }

      (organization, userPermission) = result

      isValidUser = user.isSuperAdmin || userPermission >= withPermission

      createPermissionError = { () =>
        PermissionError(user.nodeId, withPermission, organization.nodeId)
      }

      _ <- FutureEitherHelpers.assert[CoreError](isValidUser)(
        createPermissionError()
      )
    } yield organization

  def getByNodeId(
    nodeId: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Organization] =
    db.run(OrganizationsMapper.filter(_.nodeId === nodeId).result.headOption)
      .whenNone[CoreError](NotFound(nodeId))

  def getByNodeIds(
    nodeIds: List[String]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[Organization]] =
    db.run(OrganizationsMapper.filter(_.nodeId inSet nodeIds).result)
      .map(_.toList)
      .toEitherT

  def getBySlug(
    slug: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Organization] =
    db.run(
        OrganizationsMapper
          .filter(_.slug === slug.toLowerCase)
          .result
          .headOption
      )
      .whenNone(NotFound(s"Organization with slug ($slug)"))

  def getAll(): Future[Seq[Organization]] =
    db.run(OrganizationsMapper.sortBy(_.id.asc).result)

  def getId(
    nodeId: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] = {
    db.run(OrganizationsMapper.getId(nodeId)).whenNone(NotFound(nodeId))
  }

  def getTeam(
    organization: Organization,
    teamId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Team] =
    db.run(OrganizationsMapper.getTeam(organization.id, teamId))
      .whenNone(NotFound(teamId.toString))

  def getTeamByNodeId(
    organization: Organization,
    teamId: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Team] =
    db.run(OrganizationsMapper.getTeamByNodeId(organization.id, teamId))
      .whenNone[CoreError](NotFound(teamId))

  def getTeamWithOrganizationTeamByNodeId(
    organization: Organization,
    teamId: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Team, OrganizationTeam)] =
    db.run(
        OrganizationsMapper
          .getTeamWithOrganizationTeamByNodeId(organization.id, teamId)
      )
      .whenNone[CoreError](NotFound(teamId))

  def getTeams(
    organization: Organization
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[(Team, OrganizationTeam)]] =
    db.run(
        OrganizationsMapper.getTeamsWithOrganizationTeam(organization.id).result
      )
      .map(_.toList)
      .toEitherT

  def getPublisherTeam(
    organization: Organization
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Team, OrganizationTeam)] =
    db.run(
        OrganizationsMapper.getPublisherTeam(organization.id).result.headOption
      )
      .whenNone[CoreError](Error("no publishers team found"))

  def getUsersWithPermission(
    organization: Organization
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[(User, DBPermission)]] = {
    val query = OrganizationsMapper.getUsersWithPermission(organization)
    db.run(query.result).toEitherT
  }

  def getUserPermission(
    organization: Organization,
    user: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Option[DBPermission]] = {
    val query = OrganizationsMapper
      .getUsersWithPermission(organization)
      .filter(_._1.id === user.id)
      .map(_._2)
    db.run(query.result.headOption).toEitherT
  }

  def getUsers(
    organization: Organization
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[User]] =
    db.run(OrganizationsMapper.getUsers(organization.id).result)
      .map(_.toList)
      .toEitherT

  def getOrganizationUsers(
    organization: Organization
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[(User, OrganizationUser)]] =
    db.run(OrganizationsMapper.getOrganizationUsers(organization.id).result)
      .map(_.toList)
      .toEitherT

  def upgradeContributor(
    email: String,
    userId: Int,
    contributorMapper: ContributorMapper
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Contributor, Option[User])] =
    for {
      emailContributor <- db
        .run(
          contributorMapper
            .filter(_.email.toLowerCase === email.toLowerCase)
            .result
            .headOption
        )
        .whenNone(NotFound(s"Contributor with email ($email)"))

      _ <- db
        .run(
          contributorMapper
            .filter(_.id === emailContributor.id)
            .map(c => c.userId)
            .update(Some(userId))
        )
        .toEitherT

      updatedContributor <- db
        .run(
          contributorMapper
            .filter(_.id === emailContributor.id)
            .result
            .headOption
        )
        .whenNone(NotFound(s"Contributor (${emailContributor.id})"))

      user <- db
        .run(UserMapper.getById(userId))
        .toEitherT

    } yield (updatedContributor, user)

  def addUser(
    organization: Organization,
    user: User,
    permission: DBPermission
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, OrganizationUser] = {
    val organizationUserMapping =
      OrganizationUser(organization.id, user.id, permission)
    val upsertQuery = OrganizationUserMapper.insertOrUpdate(
      OrganizationUser(organization.id, user.id, permission)
    )
    val contributorMapper = new ContributorMapper(organization)

    db.run(upsertQuery)
      .toEitherT
      .flatMap { count =>
        if (count == 1) {
          for {
            emailInContributorTable <- db
              .run(
                contributorMapper
                  .filter(_.email.toLowerCase === user.email.toLowerCase)
                  .exists
                  .result
              )
              .toEitherT

            _ = if (emailInContributorTable)
              upgradeContributor(user.email, user.id, contributorMapper)
          } yield ()

          organizationUserMapping.asRight.toEitherT[Future]
        } else
          Either
            .left[CoreError, OrganizationUser](
              Error(s"Failed to add user: $user to $organization")
            )
            .toEitherT
      }
  }

  def getSubscription(
    organizationId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Subscription] =
    db.run(
        SubscriptionsMapper
          .filter(_.organizationId === organizationId)
          .sortBy(_.createdAt)
          .take(1)
          .result
          .headOption
      )
      .whenNone(NotFound(s"Subscription for organization ($organizationId)"))

  def getCustomTermsOfServiceVersion(
    nodeId: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, ZonedDateTime] =
    db.run(OrganizationsMapper.getCustomTermsOfServiceVersion(nodeId))
      .whenNone(NotFound(s"Organization ($nodeId)"): CoreError)
      .flatMap {
        case None => EitherT.leftT(MissingCustomTermsOfService: CoreError)
        case Some(version) => EitherT.rightT(version)
      }

  def updateCustomTermsOfServiceVersion(
    nodeId: String,
    version: ZonedDateTime
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] =
    db.run(
        OrganizationsMapper.updateCustomTermsOfServiceVersion(nodeId, version)
      )
      .toEitherT
      .map(_ => ())

  def getActiveFeatureFlags(
    organizationId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[FeatureFlag]] =
    db.run(FeatureFlagsMapper.getActiveFeatureFlags(organizationId).result)
      .toEitherT

  def hasFeatureFlagEnabled(
    organizationId: Int,
    feature: Feature
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Boolean] =
    db.run(
        FeatureFlagsMapper.hasFeatureFlagEnabled(organizationId, feature).result
      )
      .toEitherT

  def schemaExists(
    organization: Organization
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, String] = {
    db.run(DatabaseHelpers.getSchema(None, organization.schemaId))
      .whenNone(NotFound(s"schema"): CoreError)
      .map(_.schema)
  }

  def exists(
    slug: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Boolean] =
    db.run(
        OrganizationsMapper
          .filter(_.slug === slug.toLowerCase)
          .exists
          .result
      )
      .toEitherT

  def create(
    name: String,
    slug: String,
    features: Set[Feature] = Set(),
    subscriptionType: Option[String] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Organization] = {
    Left[CoreError, Organization](
      Error("not supported with insecure organization manager")
    ).toEitherT[Future]
  }

  def setFeatureFlag(
    featureFlag: FeatureFlag
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, FeatureFlag] = {
    val upsertQuery = for {
      affectedRows <- FeatureFlagsMapper.insertOrUpdate(featureFlag)
      _ <- if (affectedRows == 1) DBIO.successful(())
      else
        DBIO.failed(
          SqlError(
            s"Expected to insert or update 1 FeatureFlag with data: $featureFlag but actually inserted or updated $affectedRows"
          )
        )
      result <- FeatureFlagsMapper
        .filter(_.organizationId === featureFlag.organizationId)
        .filter(_.feature === featureFlag.feature)
        .result
        .headOption
    } yield result
    db.run(upsertQuery.transactionally)
      .whenNone[CoreError](
        SqlError(s"Failed to insert or update featureFlag: $featureFlag")
      )
  }
}

case class UpdateOrganization(
  name: Option[String],
  subscription: Option[Subscription]
)

class SecureOrganizationManager(val db: Database, val actor: User)
    extends OrganizationManager(db) {

  import OrganizationManager.AddEmailResult

  lazy val emailValidator: EmailValidator = EmailValidator.getInstance()

  def isPublisher(
    organization: Organization
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Boolean] = {
    for {
      publisherTeam <- getPublisherTeam(organization)
      members <- db
        .run(TeamsMapper.getUsers(publisherTeam._1.id).result)
        .toEitherT
    } yield members.map(_.id) contains actor.id
  }

  def hasPermission(
    organization: Organization,
    permission: DBPermission
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] = {
    if (actor.isSuperAdmin) FutureEitherHelpers.unit
    else {
      db.run {
          OrganizationUserMapper
            .getBy(actor.id, organization.id)
            .filter(_.permission >= permission)
            .result
            .headOption
        }
        .whenNone[CoreError](
          PermissionError(actor.nodeId, permission, organization.nodeId)
        )
        .map(_ => ())
    }
  }

  override def create(
    name: String,
    slug: String,
    features: Set[Feature] = Set(),
    subscriptionType: Option[String] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Organization] = {
    val nodeId = NodeCodes.generateId(NodeCodes.organizationCode)

    // encryptionKeyId only set here temporarily to pass NULL constraint
    // TODO: drop null constraint post DM transition

    val row = Organization(
      nodeId,
      name,
      slug.toLowerCase,
      encryptionKeyId = Some("NO_ENCRYPTION_KEY")
    )

    val createTransaction: DBIO[Organization] = (
      for {
        organization <- OrganizationsMapper.returning(OrganizationsMapper) += row
        _ <- FeatureFlagsMapper ++= features.map(
          feature => FeatureFlag(organization.id, feature)
        )
        _ <- SubscriptionsMapper += Subscription(
          organizationId = organization.id,
          status = PendingSubscription,
          `type` = subscriptionType
        )
        currentUpdatedAtOption <- OrganizationsMapper
          .filter(_.id === organization.id)
          .map(_.updatedAt)
          .result
          .headOption
        updatedAt = currentUpdatedAtOption.getOrElse(organization.updatedAt)

        teamNodeId = NodeCodes.generateId(NodeCodes.teamCode)
        team = Team(teamNodeId, SystemTeamType.Publishers.entryName.capitalize)
        teamId <- TeamsMapper returning TeamsMapper.map(_.id) += team
        _ <- OrganizationTeamMapper += OrganizationTeam(
          organization.id,
          teamId,
          DBPermission.Administer,
          Some(SystemTeamType.Publishers)
        )
      } yield organization.copy(updatedAt = updatedAt)
    ).transactionally

    for {
      exists <- exists(slug)
      _ <- FutureEitherHelpers.assert(!exists)(
        PredicateError("slug must be unique")
      )

      organization <- db.run(createTransaction).toEitherT

      _ <- schemaExists(organization)
    } yield organization
  }

  def get(
    id: Int,
    withPermission: DBPermission = DBPermission.Read
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Organization] =
    getWithPermission(id, actor, withPermission)

  override def get(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Organization] =
    get(id, withPermission = DBPermission.Read)

  def getByNodeId(
    nodeId: String,
    withPermission: DBPermission = DBPermission.Read
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Organization] =
    for {
      result <- db
        .run(OrganizationsMapper.getByNodeId(actor)(nodeId).result.headOption)
        .whenNone(NotFound(nodeId))
      (organization, userPermission) = result
      _ <- FutureEitherHelpers.assert[CoreError](
        actor.isSuperAdmin || userPermission >= withPermission
      )(PermissionError(actor.nodeId, withPermission, organization.nodeId))
    } yield organization

  override def getByNodeId(
    nodeId: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Organization] =
    getByNodeId(nodeId, withPermission = DBPermission.Read)

  def removeUser(
    organization: Organization,
    user: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] =
    db.run((for {
        teamIds <- OrganizationsMapper
          .getTeams(organization.id, user.id)
          .map(_.teamId)
          .result
          .map(_.toList)
        _ <- TeamsMapper.deleteUser(teamIds, user.id)
        _ <- OrganizationsMapper.deleteUser(organization.id, user.id)
      } yield ()).transactionally)
      .toEitherT

  override def setFeatureFlag(
    featureFlag: FeatureFlag
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, FeatureFlag] =
    FutureEitherHelpers
      .assert(actor.isSuperAdmin)(
        SuperPermissionError(
          actor.nodeId,
          s"Organization(${featureFlag.organizationId})"
        )
      )
      .flatMap(_ => super.setFeatureFlag(featureFlag))

  def update(
    organization: Organization
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Organization] = {
    for {
      _ <- hasPermission(organization, DBPermission.Write)

      organizationId <- db
        .run(
          OrganizationsMapper
            .filter(_.slug === organization.slug.toLowerCase)
            .map(_.id)
            .result
            .headOption
        )
        .map(_.getOrElse(organization.id))
        .toEitherT

      _ <- FutureEitherHelpers.assert(organizationId == organization.id)(
        ServiceError("slug is already taken.")
      )

      _ <- db
        .run(
          OrganizationsMapper
            .filter(_.id === organization.id)
            .update(organization)
        )
        .toEitherT

    } yield organization.copy(updatedAt = ZonedDateTime.now)
  }

  // TODO: don't use nodeId
  def update(
    organizationNodeId: String,
    details: UpdateOrganization
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Organization] =
    for {
      organization <- getByNodeId(organizationNodeId, DBPermission.Write)(ec)
      _ <- details.subscription match {
        case Some(subscription) =>
          updateSubscription(subscription)
        case None => FutureEitherHelpers.unit
      }

      updatedOrganization <- details.name match {
        case Some(name) =>
          for {
            result <- db
              .run(
                OrganizationsMapper
                  .filter(_.id === organization.id)
                  .map(_.name)
                  .update(name)
              )
              .toEitherT

            _ <- FutureEitherHelpers.assert(result == 1)(
              Error("failed to update organization name")
            )

            organization <- db
              .run(
                OrganizationsMapper
                  .filter(_.id === organization.id)
                  .result
                  .headOption
              )
              .whenNone[CoreError](NotFound(s"Organization ($organization.id)"))
          } yield organization
        case None =>
          Either.right[CoreError, Organization](organization).toEitherT[Future]
      }
    } yield updatedOrganization

  def getOwnersAndAdministrators(
    organization: Organization
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Seq[User], Seq[User])] = {
    val query =
      OrganizationUserMapper.getOwnersAndAdministrators(organization.id).result
    db.run(query)
      .map { results =>
        val (ownerList, adminList) =
          results.partition(_._1.permission == DBPermission.Owner)
        val owners = ownerList.map(_._2)
        val admins = adminList.map(_._2)
        (owners, admins)
      }
      .toEitherT
  }

  def updateSubscription(
    subscription: Subscription
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] = {
    for {
      _ <- get(subscription.organizationId, DBPermission.Owner)
      result <- db
        .run(
          SubscriptionsMapper
            .filter(_.organizationId === subscription.organizationId)
            .map { row =>
              (
                row.status,
                row.`type`,
                row.acceptedBy,
                row.acceptedForOrganization,
                row.acceptedByUser
              )
            }
            .update(
              (
                subscription.status,
                subscription.`type`,
                subscription.acceptedBy,
                subscription.acceptedForOrganization,
                subscription.acceptedByUser
              )
            )
        )
        .toEitherT

      _ <- FutureEitherHelpers.assert[CoreError](result == 1)(
        Error("failed to update organization subscription")
      )
    } yield result
  }

  override def addUser(
    organization: Organization,
    user: User,
    permission: DBPermission
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, OrganizationUser] =
    for {
      _ <- hasPermission(organization, DBPermission.Administer)
      result <- super.addUser(organization, user, permission)
    } yield result

  def updateUserPermission(
    organization: Organization,
    user: User,
    permission: DBPermission
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] =
    for {
      _ <- hasPermission(organization, DBPermission.Administer)
      _ <- db.run {
        OrganizationUserMapper
          .filter(_.organizationId === organization.id)
          .filter(_.userId === user.id)
          .map(_.permission)
          .update(permission)
      }.toEitherT
    } yield ()

  def getInvites(
    organization: Organization
  )(implicit
    userInviteManager: UserInviteManager,
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Iterable[UserInvite]] =
    for {
      _ <- hasPermission(organization, DBPermission.Administer)
      invites <- userInviteManager.getByOrganization(organization)
    } yield invites

  /**
    * Invite a new user to an organization. Creates a new Cognito user if needed
    * or sends welcome emails to existing users.
    */
  def inviteMember(
    organization: Organization,
    invite: Invite,
    ttl: Duration,
    permission: DBPermission
  )(implicit
    userManager: UserManager,
    userInviteManager: UserInviteManager,
    cognitoClient: CognitoClient,
    emailer: Emailer,
    messageTemplates: MessageTemplates,
    ec: ExecutionContext
  ): EitherT[Future, CoreError, AddEmailResult] =
    for {
      _ <- hasPermission(organization, DBPermission.Administer)

      _ <- FutureEitherHelpers
        .assert(emailValidator.isValid(invite.email))(
          Error("Invalid email address"): CoreError
        )

      result <- userManager
        .getByEmail(invite.email)
        .flatMap { foundUser =>
          for {
            _ <- super
              .addUser(organization, foundUser, permission)

            _ <- sendAddedToOrganizationEmail(
              user = foundUser,
              organization = organization,
              administrator = actor
            ).toEitherT[Future]

          } yield AddEmailResult.AddedExistingUser(foundUser): AddEmailResult
        }
        .recoverWith {
          case _: NotFound =>
            userInviteManager
              .createOrRefreshUserInvite(
                organization = organization,
                email = invite.email,
                firstName = invite.firstName,
                lastName = invite.lastName,
                permission = permission,
                ttl = ttl
              )
              .map(AddEmailResult.InvitedNewUser(_): AddEmailResult)
        }
    } yield result

  /**
    * Refresh a user invite. Prompts Cognito to resend invite emails.
    *
    * TODO: shares most logic with inviteMember - can they be merged entirely?
    */
  def refreshInvite(
    organization: Organization,
    inviteId: String,
    ttl: Duration
  )(implicit
    userManager: UserManager,
    userInviteManager: UserInviteManager,
    cognitoClient: CognitoClient,
    emailer: Emailer,
    messageTemplates: MessageTemplates,
    ec: ExecutionContext
  ): EitherT[Future, CoreError, AddEmailResult] =
    for {
      _ <- hasPermission(organization, DBPermission.Administer)

      invite <- userInviteManager.getByNodeId(inviteId)

      inviteOrUser <- userManager
        .getByEmail(invite.email)
        .flatMap { foundUser =>
          for {
            _ <- super.addUser(
              organization = organization,
              user = foundUser,
              permission = invite.permission
            )
            _ <- userInviteManager.delete(invite)

            _ <- sendAddedToOrganizationEmail(
              user = foundUser,
              organization = organization,
              administrator = actor
            ).toEitherT[Future]

          } yield AddEmailResult.AddedExistingUser(foundUser): AddEmailResult
        }
        .recoverWith {
          case _: NotFound =>
            userInviteManager
              .refreshUserInvite(invite)
              .map(AddEmailResult.InvitedNewUser(_): AddEmailResult)
        }
    } yield inviteOrUser

  def deleteInvite(
    organizationNodeId: String,
    inviteId: String
  )(implicit
    userInviteManager: UserInviteManager,
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] =
    for {
      _ <- getByNodeId(organizationNodeId, DBPermission.Administer)

      invite <- userInviteManager.getByNodeId(inviteId)
      _ <- userInviteManager.delete(invite)
    } yield ()

  private def sendAddedToOrganizationEmail(
    user: User,
    organization: Organization,
    administrator: User
  )(implicit
    emailer: Emailer,
    messageTemplates: MessageTemplates
  ): Either[CoreError, Unit] = {
    val email = EmailToSend(
      to = Email(user.email),
      from = messageTemplates.supportEmail,
      message = messageTemplates
        .addedToOrganization(user.email, administrator.fullName, organization),
      subject = s"You’ve been added to an organization"
    )

    emailer.sendEmail(email).leftMap(ThrowableError(_)).map(_ => ())
  }

  /**
    * Checks if the given organization ID is the special demo organization.
    */
  def isDemo(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Boolean] = {
    for {
      truth <- hasFeatureFlagEnabled(id, Feature.SandboxOrgFeature)
    } yield truth
  }
}
