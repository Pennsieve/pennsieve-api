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

import java.util.UUID
import cats.data._
import cats.implicits._
import com.pennsieve.audit.middleware.TraceId

import scala.util.Either
import com.pennsieve.core.utilities.FutureEitherHelpers
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.db.{
  DatasetPublicationStatusMapper,
  WebhookEventSubscriptionsMapper,
  _
}
import com.pennsieve.domain._
import com.pennsieve.messages._
import com.pennsieve.models._
import com.pennsieve.core.utilities.checkOrErrorT
import com.pennsieve.traits.PostgresProfile.api._
import enumeratum.{ Enum, EnumEntry }
import org.postgresql.util.PSQLException
import slick.ast.Ordering
import slick.dbio.DBIO
import slick.lifted.{ ColumnOrdered, Query }

import java.time.ZonedDateTime
import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

case class ChangeResponse(success: Boolean, message: Option[String] = None)

case class Collaborators(
  users: Seq[User],
  organizations: Seq[Organization],
  teams: Seq[(Team, OrganizationTeam)]
)

case class CollaboratorChanges(
  changes: Map[String, ChangeResponse],
  counts: CollaboratorCounts
)

object DatasetManager {

  sealed trait OrderByColumn extends EnumEntry

  object OrderByColumn extends Enum[OrderByColumn] {

    val values: immutable.IndexedSeq[OrderByColumn] = findValues

    case object Name extends OrderByColumn

    case object UpdatedAt extends OrderByColumn

    case object IntId extends OrderByColumn
  }

  sealed trait OrderByDirection extends EnumEntry {

    def toSlick[T](column: Rep[T]) = OrderByDirection.toSlick(this, column)
  }

  object OrderByDirection extends Enum[OrderByDirection] {

    val values: immutable.IndexedSeq[OrderByDirection] = findValues

    case object Asc extends OrderByDirection

    case object Desc extends OrderByDirection

    def toSlick[T](direction: OrderByDirection, column: Rep[T]) =
      direction match {
        case Asc => ColumnOrdered(column, Ordering(Ordering.Asc))
        case Desc => ColumnOrdered(column, Ordering(Ordering.Desc))
      }
  }
}

class DatasetManager(
  val db: Database,
  val actor: User,
  val datasetsMapper: DatasetsMapper
) {

  import DatasetManager._

  val organization: Organization = datasetsMapper.organization

  val packagesMapper: PackagesMapper = new PackagesMapper(organization)

  val filesMapper: FilesMapper = new FilesMapper(organization)

  val contributor: ContributorMapper = new ContributorMapper(organization)

  val contributorManager: ContributorManager =
    new ContributorManager(db, actor, contributor, new UserManager(db))

  val datasetStatusManager: DatasetStatusManager =
    new DatasetStatusManager(db, organization)

  val datasetPublicationStatusMapper: DatasetPublicationStatusMapper =
    new DatasetPublicationStatusMapper(organization)

  val datasetContributorMapper = new DatasetContributorMapper(organization)

  val datasetIntegrationsMapper = new DatasetIntegrationsMapper(organization)

  val webhooksMapper = new WebhooksMapper(organization)

  implicit val collectionMapper: CollectionMapper =
    new CollectionMapper(organization)

  implicit val datasetTeam: DatasetTeamMapper = new DatasetTeamMapper(
    organization
  )

  implicit val datasetUser: DatasetUserMapper = new DatasetUserMapper(
    organization
  )

  implicit val datasetCollection: DatasetCollectionMapper =
    new DatasetCollectionMapper(organization)

  implicit val datasetContributor: DatasetContributorMapper =
    new DatasetContributorMapper(organization)

  implicit val datasetStatusLog: DatasetStatusLogMapper =
    new DatasetStatusLogMapper(organization)

  val collectionManager: CollectionManager =
    new CollectionManager(db, collectionMapper)

  val datasetIgnoreFiles: DatasetIgnoreFilesMapper =
    new DatasetIgnoreFilesMapper(organization)

  val organizationManager: OrganizationManager =
    new OrganizationManager(db)

  def isLocked(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Boolean] =
    db.run(datasetsMapper.isLocked(dataset, actor)).toEitherT

  def assertNotLocked(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] = {
    for {
      locked <- isLocked(dataset)
      _ <- checkOrErrorT(!locked)(LockedDatasetError(dataset.nodeId))
        .leftWiden[CoreError]
    } yield ()
  }

  def assertNotLocked(
    datasetId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] = {
    for {
      dataset <- get(datasetId)
      _ <- assertNotLocked(dataset)
    } yield ()
  }

  /**
    * This function should be called whenever data changes change the published
    * shape of a dataset. This includes:
    *
    *  - Dataset metadata (name, description, etc)
    *  - Dataset owner
    *  - Collaborators
    *  - Packages
    *  - Files
    *  - Ignore files
    */
  def touchUpdatedAtTimestamp(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] =
    touchUpdatedAtTimestamp(dataset.id)

  def touchUpdatedAtTimestamp(
    datasetId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] =
    db.run(
        datasetsMapper
          .get(datasetId)
          .map(_.updatedAt)
          .update(ZonedDateTime.now)
      )
      .toEitherT
      .subflatMap {
        case 0 => Left(NotFound(s"dataset $datasetId not found"))
        case _ => Right(())
      }

  def create(
    name: String,
    description: Option[String] = None,
    state: DatasetState = DatasetState.READY,
    automaticallyProcessPackages: Boolean = false,
    statusId: Option[Int] = None,
    license: Option[License] = None,
    tags: List[String] = List.empty,
    bannerId: Option[UUID] = None,
    readmeId: Option[UUID] = None,
    changelogId: Option[UUID] = None,
    dataUseAgreement: Option[DataUseAgreement] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Dataset] = {
    val nodeId = NodeCodes.generateId(NodeCodes.dataSetCode)

    for {
      _ <- FutureEitherHelpers.assert(name.trim.nonEmpty)(
        PredicateError("dataset name must not be empty")
      )

      // should just be a uniqueness constraint in the DB that we handle
      nameExists <- nameExists(name.trim).toEitherT
      _ <- FutureEitherHelpers.assert(!nameExists)(
        PredicateError("dataset name must be unique")
      )

      _ <- checkOrErrorT(name.trim.length < 256)(
        PredicateError("dataset name must be less than 255 characters")
      )

      orcid = actor.orcidAuthorization.map(_.orcid)

      contributorAndUser <- contributorManager
        .getOrCreateContributorFromUser(actor)

      (contributor, user) = contributorAndUser

      createdDataset = for {

        datasetStatus <- statusId
          .map(datasetStatusManager.getById(_))
          .getOrElse(datasetStatusManager.getDefaultStatus)

        dataset <- (datasetsMapper returning datasetsMapper) += Dataset(
          nodeId,
          name.trim,
          state,
          description,
          automaticallyProcessPackages = automaticallyProcessPackages,
          statusId = datasetStatus.id,
          license = license,
          tags = tags,
          bannerId = bannerId,
          readmeId = readmeId,
          changelogId = changelogId,
          dataUseAgreementId = dataUseAgreement.map(_.id)
        )

        _ <- datasetUser += DatasetUser(
          dataset.id,
          actor.id,
          DBPermission.Owner,
          Some(Role.Owner)
        )

        _ <- datasetStatusLog += DatasetStatusLog(
          dataset.id,
          Some(datasetStatus.id),
          datasetStatus.name,
          datasetStatus.displayName,
          Some(actor.id)
        )

      } yield dataset

      dataset <- db.run(createdDataset.transactionally).toEitherT

      _ <- addContributor(dataset, contributor.id)

    } yield dataset

  }

  def get(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Dataset] =
    db.run(datasetsMapper.withoutDeleted.filter(_.id === id).result.headOption)
      .whenNone(NotFound(s"Dataset ($id)"))

  def getByExternalIdWithMaxRole(
    externalId: ExternalId
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Dataset, Option[Role])] = {
    for {
      result <- db
        .run(
          externalId.fold(
            datasetsMapper.datasetWithRole(actor.id, _),
            datasetsMapper.datasetWithRoleByNodeId(actor.id, _)
          )
        )
        .whenNone(NotFound(externalId.toString))

      (dataset, datasetMaxRole) = result
      role = if (actor.isSuperAdmin)
        Role.maxRole
      else datasetMaxRole

      _ <- FutureEitherHelpers.assert[CoreError](role.isDefined)(
        DatasetRolePermissionError(actor.nodeId, dataset.id)
      )
    } yield (dataset, role)
  }

  def getByAnyId(
    nodeOrIntId: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Dataset] =
    Try(nodeOrIntId.toInt).toOption
      .map(id => get(id))
      .getOrElse(getByNodeId(nodeOrIntId))

  def getByNodeId(
    nodeId: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Dataset] =
    db.run(
        datasetsMapper.withoutDeleted
          .filter(_.nodeId === nodeId)
          .result
          .headOption
      )
      .whenNone(NotFound(nodeId))

  def nameExists(name: String): Future[Boolean] =
    db.run(datasetsMapper.nameExists(name))

  def find(
    withRole: Role
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[DatasetAndStatus]] =
    find(actor, withRole, None)

  def find(
    user: User,
    withRole: Role,
    datasetIds: Option[List[Int]] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[DatasetAndStatus]] =
    for {
      userPermission <- organizationManager.getUserPermission(
        organization,
        user
      )
      result <- db
        .run(
          datasetsMapper.find(user, userPermission, Some(withRole), datasetIds)
        )
        .toEitherT
    } yield result

  def getStatusLog(
    dataset: Dataset,
    limit: Int,
    offset: Int
  )(implicit
    ec: ExecutionContext
  )
    : EitherT[
      Future,
      CoreError,
      (Seq[(DatasetStatusLog, Option[User])], Long)
    ] = {
    val startingQuery = datasetStatusLog.getStatusLogs(dataset.id)

    val finalQuery = startingQuery.drop(offset).take(limit)

    val query = for {
      logCount <- startingQuery.size.result
        .map(_.toLong)
      statusLogEntries <- finalQuery.result
    } yield (statusLogEntries, logCount)

    db.run(query).toEitherT
  }

  def getContributors(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[(Contributor, Option[User])]] =
    for {
      contributors <- db
        .run(datasetContributor.getContributors(dataset).result)
        .toEitherT
    } yield contributors

  def getCollaborators(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Collaborators] = {

    val usersFuture =
      db.run(datasetUser.collaborators(dataset.id).map(_._2).result)
    val teamsFuture = db.run(datasetTeam.teams(dataset.id).result)
    val sharedWithOrganizationList =
      dataset.role.map(_ => organization).toSeq

    usersFuture
      .zip(teamsFuture)
      .map {
        case (users, teams) =>
          Collaborators(users, sharedWithOrganizationList, teams)
      }
      .toEitherT
  }

  def getCollaboratorCounts(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, CollaboratorCounts] = {
    val userCountFuture =
      db.run(datasetUser.collaborators(dataset.id).length.result)
    val teamCountFuture = db.run(datasetTeam.teams(dataset.id).length.result)
    val orgCount = dataset.role.map(_ => 1).getOrElse(0)

    userCountFuture
      .zip(teamCountFuture)
      .map {
        case (userCount, teamCount) =>
          CollaboratorCounts(userCount, orgCount, teamCount)
      }
      .toEitherT
  }

  def getCollaboratorCounts(
    datasets: List[Dataset]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Map[Dataset, CollaboratorCounts]] = {

    val query = for {
      userCounts <- datasetsMapper
        .filter(_.id inSet datasets.map(_.id))
        .join(datasetUser.filter(_.role =!= (Role.Owner: Role)))
        .on(_.id === _.datasetId)
        .groupBy(_._1)
        .map {
          case (dataset, users) => (dataset.id, users.length)
        }
        .result
        .map(_.toMap)

      teamCounts <- datasetsMapper
        .join(datasetTeam)
        .on(_.id === _.datasetId)
        .groupBy(_._1)
        .map {
          case (dataset, teams) => (dataset.id, teams.length)
        }
        .result
        .map(_.toMap)

    } yield (userCounts, teamCounts)

    for {
      counts <- db.run(query).toEitherT
      (userCounts, teamCounts) = counts
    } yield
      datasets
        .map(
          dataset =>
            dataset -> CollaboratorCounts(
              users = userCounts.get(dataset.id).getOrElse(0),
              teams = teamCounts.get(dataset.id).getOrElse(0),
              organizations = dataset.role.map(_ => 1).getOrElse(0)
            )
        )
        .toMap
  }

  // Note: this does not check if the user is part of the organization, so
  // calling this with a user outside of the org can give incorrect results
  def maxRole(
    dataset: Dataset,
    user: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Role] =
    db.run(datasetsMapper.maxRoles(user.id))
      .map(_.get(dataset.id).flatten)
      .whenNone[CoreError](DatasetRolePermissionError(user.nodeId, dataset.id))

  def update(
    dataset: Dataset,
    checkforDuplicateNames: Boolean = true
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Dataset] = {
    for {
      _ <- FutureEitherHelpers.assert(dataset.name.trim.nonEmpty)(
        PredicateError("dataset name must not be empty")
      )

      currentDataset <- db
        .run(
          datasetsMapper
            .filterNot(_.state === (DatasetState.DELETING: DatasetState))
            .filter(_.name === dataset.name)
            .result
            .headOption
        )
        .map(_.getOrElse(dataset))
        .toEitherT

      _ <- FutureEitherHelpers.assert(
        (currentDataset.id == dataset.id) || !checkforDuplicateNames
      )(ServiceError(s"name is already taken: ${dataset.name}"))

      datasetStatus <- db
        .run(datasetStatusManager.getById(dataset.statusId))
        .toEitherT

      query = for {
        _ <- if (currentDataset.statusId != dataset.statusId)
          datasetStatusLog += DatasetStatusLog(
            dataset.id,
            Some(datasetStatus.id),
            datasetStatus.name,
            datasetStatus.displayName,
            Some(actor.id)
          )
        else DBIO.successful(())

        _ <- datasetsMapper
          .filter(_.id === dataset.id)
          .update(dataset)
      } yield ()

      _ <- db
        .run(query.transactionally)
        .toEitherT[CoreError] {
          case e: PSQLException
              if (e.getMessage() == "ERROR: value too long for type character varying(255)") =>
            PredicateError("dataset name must be less than 255 characters"): CoreError
        }

      updatedDataset <- if (dataset.state == DatasetState.DELETING)
        EitherT[Future, CoreError, Dataset](Future.successful(Right(dataset)))
      else get(dataset.id)

    } yield updatedDataset

  }

  def delete(
    traceId: TraceId,
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, BackgroundJob] =
    for {
      _ <- assertNotLocked(dataset)
      _ <- update(dataset.copy(state = DatasetState.DELETING))
      - <- removeCollections(dataset)
    } yield
      DeleteDatasetJob(
        dataset.id,
        organization.id,
        actor.nodeId,
        traceId = traceId
      ): BackgroundJob

  val validShareableNodeCodes: Set[String] =
    Set(NodeCodes.userCode, NodeCodes.organizationCode, NodeCodes.teamCode)

  def validShareId(id: String): Boolean =
    NodeCodes.nodeIdIsOneOf(validShareableNodeCodes, id)

  def generateAddCollaboratorQueriesMapper(
    dataset: Dataset,
    validIdsToShareWithMap: Map[String, Int]
  )(
    codeIdPair: (String, Set[String])
  )(implicit
    ec: ExecutionContext
  ): Set[DBIO[String]] =
    codeIdPair match {
      case (NodeCodes.userCode, shareeIds) =>
        shareeIds.map(
          nodeId =>
            datasetUser
              .+=(
                DatasetUser(
                  dataset.id,
                  validIdsToShareWithMap(nodeId),
                  DBPermission.Delete,
                  Some(Role.Editor)
                )
              )
              .map(_ => nodeId)
        )
      case (NodeCodes.teamCode, shareeIds) =>
        shareeIds.map(
          nodeId =>
            datasetTeam
              .+=(
                DatasetTeam(
                  dataset.id,
                  validIdsToShareWithMap(nodeId),
                  DBPermission.Delete,
                  Some(Role.Editor)
                )
              )
              .map(_ => nodeId)
        )
      case (NodeCodes.organizationCode, _) =>
        Set {
          datasetsMapper
            .setOrganizationRole(dataset.id, Some(Role.Editor))
            .map(_ => organization.nodeId)
        }

      case (_, ids) =>
        Set {
          DBIO.failed(new RuntimeException(ids.toString))
        }
    }

  def addCollaborators(
    dataset: Dataset,
    collaboratorIds: Set[String]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, CollaboratorChanges] =
    for {
      currentSharees <- db
        .run(datasetsMapper.currentSharees(dataset.id).result)
        .map(_.toSet)
        .toEitherT

      validIdsToShareWithMap <- db
        .run(datasetsMapper.validIdTuplesToShareWith().result)
        .map(_.toMap.updated(organization.nodeId, organization.id))
        .toEitherT

      validIdsToShareWith = validIdsToShareWithMap.keySet

      // separate out valid ids from invalid ones
      (validIds, invalidIds) = collaboratorIds.partition(validShareId)

      // only share with for ids that we are allowed to but are not already shared to
      idsToShareTo = validIds
        .intersect(validIdsToShareWith)
        .diff(currentSharees)

      addQueriesMapper = generateAddCollaboratorQueriesMapper(
        dataset,
        validIdsToShareWithMap
      )(_)

      addQueries: List[DBIO[String]] = idsToShareTo
        .groupBy(NodeCodes.extractNodeCodeFromId)
        .toList
        .flatMap(addQueriesMapper)

      idsShared <- db.run(DBIO.sequence(addQueries)).toEitherT

      counts <- getCollaboratorCounts(dataset)

      // explain to user why certain ids failed or if they succeeded
      invalidIdResponse = invalidIds
        .map(_ -> ChangeResponse(success = false, Some("Not a valid id")))
        .toMap
      notInOrg = validIds
        .diff(validIdsToShareWith)
        .map(
          _ -> ChangeResponse(
            success = false,
            Some("Id does not have access to this Organization")
          )
        )
        .toMap
      alreadySharedResponse = validIds
        .intersect(currentSharees)
        .map(_ -> ChangeResponse(success = false, Some("Already has access")))
        .toMap
      sharedIdsResponse = idsShared
        .map(_ -> ChangeResponse(success = true))
        .toMap
    } yield
      CollaboratorChanges(
        changes = invalidIdResponse ++ notInOrg ++ alreadySharedResponse ++ sharedIdsResponse,
        counts = counts
      )

  def removeCollaborators(
    datasets: List[Dataset],
    collaborators: Set[String],
    removeIntegrationUsers: Boolean = false
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[CollaboratorChanges]] =
    datasets.map { dataset =>
      removeCollaborators(dataset, collaborators, removeIntegrationUsers)
    }.sequence

  def removeCollaborators(
    dataset: Dataset,
    collaborators: Set[String],
    removeIntegrationUsers: Boolean
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, CollaboratorChanges] = {

    // only admins are allowed to make changes so we can assume the user
    // is the admin at this point
    val adminId = actor.nodeId

    val collaboratorsWithoutAdmin = collaborators - adminId

    for {

      currentShareesIds <- db
        .run(datasetsMapper.currentSharees(dataset.id).result)
        .map { ids =>
          if (dataset.role.isDefined)
            ids.toSet + organization.nodeId
          else ids.toSet
        }
        .toEitherT

      // group valid and invalid ids together
      (validIds, invalidIds) = collaboratorsWithoutAdmin.partition(validShareId)

      deleteUsersQuery = datasetUser
        .filter(
          ds => ds.datasetId === dataset.id && ds.role =!= (Role.Owner: Role)
        ) //we allowed removing the owner, which caused havoc
        .filter { du =>
          du.userId in {
            UserMapper
              .filter(
                u =>
                  u.nodeId
                    .inSet(collaboratorsWithoutAdmin) && u.isIntegrationUser === removeIntegrationUsers
              )
              .map(_.id)
          }
        }
        .delete

      deleteTeamsQuery = datasetTeam
        .filter(ds => ds.datasetId === dataset.id)
        .filter { dt =>
          dt.teamId in {
            TeamsMapper
              .filter(_.nodeId.inSet(collaboratorsWithoutAdmin))
              .map(_.id)
          }
        }
        .delete

      updateOrganizationPermissionQuery = if (collaborators.exists(
          NodeCodes.nodeIdIsA(_, NodeCodes.organizationCode)
        )) {
        datasetsMapper
          .setOrganizationRole(dataset.id, None)
      } else {
        DBIO.successful(())
      }

      deleteQuery = DBIO
        .seq(
          deleteUsersQuery,
          deleteTeamsQuery,
          updateOrganizationPermissionQuery
        )
        .transactionally

      _ <- db.run(deleteQuery).toEitherT

      // explain to user why ids failed or succeeded
      invalidIdResponse = invalidIds
        .map(_ -> ChangeResponse(success = false, Some("Not a valid id")))
        .toMap
      notSharedWithResponse = validIds
        .diff(currentShareesIds)
        .map(_ -> ChangeResponse(success = false, Some("Not currently shared")))
        .toMap
      adminIdInvalid = collaborators
        .intersect(Set(adminId))
        .map(
          _ -> ChangeResponse(
            success = false,
            Some("Cannot revoke your own access")
          )
        )
        .toMap
      idsToUnshareResponse = validIds
        .intersect(currentShareesIds)
        .map(_ -> ChangeResponse(success = true))
        .toMap

      count <- getCollaboratorCounts(dataset)

    } yield
      CollaboratorChanges(
        changes = invalidIdResponse ++ notSharedWithResponse ++ adminIdInvalid ++ idsToUnshareResponse,
        counts = count
      )
  }

  def getOwner(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, User] = {
    db.run(datasetsMapper.owner(dataset.id))
      .whenNone[CoreError](NotFound(dataset.nodeId))
  }

  def getOwners(
    datasets: Seq[Dataset]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Map[Dataset, User]] =
    for {
      ownerMap <- db.run(datasetsMapper.owners(datasets.map(_.id))).toEitherT

      datasetsAndOwners <- datasets.toList.traverse(
        dataset =>
          ownerMap
            .get(dataset.id)
            .toRight[CoreError](NotFound(dataset.nodeId))
            .toEitherT[Future]
            .map(dataset -> _)
      )
    } yield datasetsAndOwners.toMap

  def getManagers(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[User]] =
    db.run(datasetsMapper.managers(dataset.id)).map(_.toList).toEitherT

  // NEW ROLE-BASED PERMISSIONS

  def canShareWithUser(
    user: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] =
    db.run(
        OrganizationUserMapper
          .getBy(user.id, organization.id)
          .result
          .headOption
      )
      .whenNone[CoreError](NotFound(s"user ${user.id}"))
      .map(_ => ())

  def addUserCollaborator(
    dataset: Dataset,
    user: User,
    role: Role
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, OldRole] =
    for {
      currentOwner <- getOwner(dataset)

      _ <- FutureEitherHelpers.assert[CoreError](role != Role.Owner)(
        InvalidAction(
          "Another person already owns this dataset. Please contact your organization admin for help."
        )
      )

      _ <- FutureEitherHelpers.assert[CoreError](currentOwner.id != user.id)(
        InvalidAction(
          "To relinquish ownership of a dataset, please use the PUT /collaborators/owner endpoint."
        )
      )
      _ <- canShareWithUser(user)

      oldRole <- getUserCollaboratorRole(dataset, user)

      _ <- db
        .run(
          datasetUser.insertOrUpdate(
            DatasetUser(dataset.id, user.id, role.toPermission, Some(role))
          )
        )
        .toEitherT
    } yield OldRole(oldRole)

  case class OldRole(oldRole: Option[Role])

  def getUserCollaboratorRole(
    dataset: Dataset,
    user: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Option[Role]] =
    db.run(
        datasetUser
          .getBy(userId = user.id, datasetId = dataset.id)
          .map(_.role)
          .result
          .headOption
          .map(_.flatten)
      )
      .toEitherT

  def addCollection(
    dataset: Dataset,
    collectionId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Collection] = {
    val query = for {
      collection <- collectionMapper
        .get(collectionId)
        .result
        .headOption
        .flatMap {
          case Some(c) => DBIO.successful(c)
          case None => DBIO.failed(NotFound(collectionId.toString))
        }

      _ <- datasetCollection
        .insertOrUpdate(DatasetCollection(dataset.id, collection.id))
    } yield collection

    db.run(query.transactionally).toEitherT
  }

  def removeCollection(
    dataset: Dataset,
    collectionId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Collection] = {
    val query = for {
      collection <- collectionMapper
        .get(collectionId)
        .result
        .headOption
        .flatMap {
          case Some(c) => DBIO.successful(c)
          case None => DBIO.failed(NotFound(collectionId.toString))
        }

      _ <- datasetCollection.getBy(dataset, collection.id).delete

      // Clean up: delete the collection from the organization if no other
      // dataset references it

      used <- datasetCollection
        .filter(_.collectionId === collection.id)
        .exists
        .result

      _ <- if (!used) {
        collectionMapper
          .filter(_.id === collection.id)
          .delete
      } else {
        DBIO.successful(0)
      }

    } yield collection

    db.run(query.transactionally).toEitherT
  }

  def removeCollections(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] = {
    for {
      collections <- db
        .run(datasetCollection.getCollections(dataset).result)
        .toEitherT
      _ <- collections.toList.traverse(c => removeCollection(dataset, c.id))
    } yield ()
  }

  def getCollections(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[Collection]] =
    for {
      collections <- db
        .run(datasetCollection.getCollections(dataset).result)
        .toEitherT
    } yield collections

  def getContributor(
    dataset: Dataset,
    contributorId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DatasetContributor] = {
    db.run(
        datasetContributor
          .getBy(dataset, contributorId)
          .result
          .headOption
      )
      .whenNone[CoreError](NotFound(dataset.nodeId))
  }

  def addContributor(
    dataset: Dataset,
    contributorId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] = {
    val addContributorQueries = for {
      //find the highest order of the contributors
      position <- datasetContributor
        .getByDataset(dataset)
        .sortBy(_.order.desc)
        .map(c => c.order)
        .take(1)
        .result

      computedPosition = if (position.isEmpty) {
        1
      } else {
        position.head + 1
      }
      //insert the new contributor at the next position
      _ <- datasetContributor.insertOrUpdate(
        DatasetContributor(dataset.id, contributorId, computedPosition)
      )
    } yield ()

    for {
      _ <- db
        .run(addContributorQueries.transactionally)
        .toEitherT
    } yield ()
  }

  def switchContributorOrder(
    dataset: Dataset,
    contributor: DatasetContributor,
    otherContributor: DatasetContributor
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] = {

    val switchContributorOrder = for {
      _ <- datasetContributor.updateContributorOrder(
        dataset,
        contributor,
        otherContributor.order
      )
      _ <- datasetContributor.updateContributorOrder(
        dataset,
        otherContributor,
        contributor.order
      )
    } yield ()

    for {
      _ <- db
        .run(switchContributorOrder.transactionally)
        .toEitherT
    } yield ()
  }

  def removeContributor(
    dataset: Dataset,
    contributorId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] =
    db.run(
        datasetContributor
          .getBy(dataset, contributorId)
          .delete
      )
      .toEitherT
      .subflatMap {
        case 0 => Left(NotFound(s"Contributor $contributorId"))
        case _ => Right(())
      }

  def switchOwner(
    dataset: Dataset,
    currentOwner: User,
    newOwner: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] = {

    val switchOwner = for {
      _ <- datasetUser.insertOrUpdate(
        DatasetUser(
          dataset.id,
          currentOwner.id,
          Role.Manager.toPermission,
          Some(Role.Manager)
        )
      )
      _ <- datasetUser.insertOrUpdate(
        DatasetUser(
          dataset.id,
          newOwner.id,
          Role.Owner.toPermission,
          Some(Role.Owner)
        )
      )
    } yield ()

    for {
      _ <- canShareWithUser(currentOwner)
      _ <- canShareWithUser(newOwner)
      _ <- db
        .run(switchOwner.transactionally)
        .toEitherT
    } yield ()
  }

  def getUserCollaborators(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[(User, Role)]] =
    EitherT[Future, CoreError, List[(User, Role)]](
      db.run(
          datasetUser
            .getUsersBy(dataset.id)
            .map {
              case (dsUser, user) => (user, dsUser.role)
            }
            .result
        )
        .map(_.toList.traverse {
          case (user, Some(role)) => Either.right((user, role))
          case (user, None) =>
            Either.left(NotFound(s"role for user ${user.id}"))
        })
    )

  def deleteUserCollaborator(
    dataset: Dataset,
    user: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, OldRole] =
    for {
      oldRole <- getUserCollaboratorRole(dataset, user)

      _ <- db
        .run(
          datasetUser
            .getBy(user.id, dataset.id)
            .filter(_.userId =!= actor.id)
            .filter(_.role =!= (Role.Owner: Role))
            .delete
        )
        .map(_ => ())
        .toEitherT

    } yield OldRole(oldRole)

  def addTeamCollaborator(
    dataset: Dataset,
    team: Team,
    role: Role
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, OldRole] =
    for {
      oldRole <- getTeamCollaboratorRole(dataset, team)

      _ <- db
        .run(
          datasetTeam.insertOrUpdate(
            DatasetTeam(dataset.id, team.id, role.toPermission, Some(role))
          )
        )
        .map(_ => ())
        .toEitherT
    } yield OldRole(oldRole)

  def getTeamCollaboratorRole(
    dataset: Dataset,
    team: Team
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Option[Role]] =
    db.run(
        datasetTeam
          .getBy(teamId = team.id, datasetId = dataset.id)
          .map(_.role)
          .result
          .headOption
          .map(_.flatten)
      )
      .toEitherT

  def getTeamCollaborators(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[(Team, Role)]] =
    EitherT[Future, CoreError, List[(Team, Role)]](
      db.run(datasetTeam.teamsWithRoles(dataset.id).result)
        .map(_.toList.traverse {
          case (team, Some(role)) => Either.right((team, role))
          case (team, None) =>
            Either.left(NotFound(s"role for team ${team.id}"))
        })
    )

  def deleteTeamCollaborator(
    dataset: Dataset,
    team: Team
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, OldRole] =
    for {
      oldRole <- getTeamCollaboratorRole(dataset, team)

      _ <- db
        .run(
          datasetTeam
            .getBy(dataset.id, team.id)
            .delete
        )
        .map(_ => ())
        .toEitherT
    } yield OldRole(oldRole)

  def setOrganizationCollaboratorRole(
    dataset: Dataset,
    role: Option[Role]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] =
    db.run(datasetsMapper.setOrganizationRole(dataset.id, role))
      .map(_ => ())
      .toEitherT

  def sourceFileCount(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Long] = {
    val query = packagesMapper
      .filter(p => p.datasetId === dataset.id)
      .filterNot(p => p.state === (PackageState.DELETING: PackageState))
      .join(filesMapper)
      .on(_.id === _.packageId)
      .filter {
        case (_, filesTable) =>
          filesTable.objectType === (FileObjectType.Source: FileObjectType)
      }
      .size
      .result
      .map(_.toLong)

    db.run(query).toEitherT
  }

  /**
    * Test if the query is "simple". A query is considered simple if:
    *
    * (1) It consists of a single word
    * (2) It does not contain any search operators like "&", "|", "!", ":*", "(", ")"
    *
    * @param query
    * @return
    */
  private def isSimpleQuery(query: String): Boolean =
    query.trim.split(" ").size == 1 && "[()!&|]|:\\*".r
      .findFirstIn(query)
      .isEmpty

  /**
    * Test if the query is a multi-term query. A query is considered multi-term if:
    *
    * (1) It contains multiple words
    * (2) It does not contain any search operators like "&", "|", "!", ":*", "(", ")"
    *
    * @param query
    * @return
    */
  private def isMultiTermQuery(query: String): Boolean =
    query.trim.split(" ").size > 1 && "[()!&|]|:\\*".r
      .findFirstIn(query)
      .isEmpty

  /**
    * Convert a multi-term query into a conjoined Postgres text query.
    *
    * Example: "cat dog bear" -> "cat & dog & bear"
    *
    * @param query
    * @return
    */
  private def multiTermToTextSearch(query: String): String =
    query.trim.split(" ").mkString(" & ")

  /**
    * Full text search
    */
  private def fullTextSearch(
    query: Query[
      ((DatasetsTable, DatasetUserTable), UserTable),
      ((Dataset, DatasetUser), User),
      Seq
    ],
    textSearch: Option[String]
  ): Query[DatasetsTable, Dataset, Seq] =
    textSearch match {
      case Some(ts: String) => {

        val rollupUsers =
          query
            .groupBy { case ((dt: DatasetsTable, _), _) => dt.id }
            .map {
              case (datasetId, grouping) =>
                (
                  datasetId,
                  grouping
                    .map {
                      case (_, u: UserTable) =>
                        u.firstName ++ " " ++ u.lastName ++ " " ++ u.email
                    }
                    .arrayAgg[String]
                )
            }

        val q = rollupUsers
          .join(datasetsMapper)
          .on(_._1 === _.id)
          .joinLeft(datasetContributor)
          .on(_._2.id === _.datasetId)
          .join(contributor)
          .on(_._2.map(_.contributorId) === _.id)

        val usersContributors =
          q.groupBy {
              case ((((_, _), dt: DatasetsTable), _), _) => dt.id
            }
            .map {
              case (datasetId, grouping) =>
                (
                  datasetId,
                  grouping
                    .map {
                      case ((((_, users), _), _), c) =>
                        c.firstName.getOrElse("") ++ " " ++ c.lastName
                          .getOrElse("") ++ " " ++ c.email
                          .getOrElse("") ++ " " ++ F
                          .arrayToString(users, " ")
                    }
                    .arrayAgg[String]
                )
            }

        val qq = for {
          uc <- usersContributors
          d <- datasetsMapper if (uc._1 === d.id)
        } yield (d, uc._2)

        qq.filter {
            case (dt: DatasetsTable, usersContributors) => {

              val tokens = dt.id
                .asColumnOf[String] ++ " " ++ dt.name.toLowerCase ++ " " ++
                dt.description.getOrElse("").toLowerCase ++ " " ++
                F.arrayToString(dt.tags, " ").toLowerCase ++ " " ++
                F.arrayToString(usersContributors, " ").toLowerCase

              val tokensWithoutNoise =
                F.regexpReplace(
                  tokens,
                  "[\\[\\]\\(\\)\\-+\\.\\?!\t\n]",
                  " ",
                  "g"
                )

              // For simple queries consisting of a single word, append the Postgres full-text prefix search
              // operator to the search term: "foo" -> "foo:*"
              val textQuery: String = if (isSimpleQuery(ts)) {
                s"${ts.trim.toLowerCase}:*"
              } else if (isMultiTermQuery(ts)) {
                multiTermToTextSearch(ts.trim.toLowerCase)
              } else {
                ts.trim.toLowerCase
              }

              val tv = toTsVector(tokens) @+ toTsVector(tokensWithoutNoise)

              tv @@ toTsQuery(textQuery.bind)

            }
          }
          .map { case (dt: DatasetsTable, _) => dt }
      }

      case None =>
        query.map { case ((dt: DatasetsTable, _), _) => dt }
    }

  def getDatasetPaginated(
    withRole: Role,
    limit: Option[Int],
    offset: Option[Int],
    orderBy: (OrderByColumn, OrderByDirection),
    status: Option[DatasetStatus],
    textSearch: Option[String],
    publicationStatuses: Option[Set[PublicationStatus]] = None,
    publicationTypes: Option[Set[PublicationType]] = None,
    canPublish: Option[Boolean] = None,
    restrictToRole: Boolean = false,
    collectionId: Option[Int] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Seq[DatasetAndStatus], Long)] = {

    val query = datasetsMapper.maxRoles(actor.id).flatMap {
      roleMap: Map[Int, Option[Role]] =>
        {
          val datasetIds: List[Int] = roleMap
            .filter {
              case (_, Some(role)) =>
                if (restrictToRole) role == withRole
                else role >= withRole
              case (_, None) => false
            }
            .keys
            .toList

          // (1) Filter undeleted datasets in the set `datasetIds`:
          val query = datasetsMapper.withoutDeleted
            .filter(_.id.inSet(datasetIds))

            // (2) Only match datasets with the supplied status:
            .filterOpt(status)(_.statusId === _.id)

            // (3) Add users:
            .join(datasetUser)
            .on(_.id === _.datasetId)
            .join(UserMapper)
            .on(_._2.userId === _.id)

          val queryAfterCollectionFiltering =
            // (4) Only match datasets with the supplied collectionId:
            query
              .joinLeft(datasetCollection)
              .on(_._1._1.id === _.datasetId)
              .filterOpt(collectionId)(_._2.map(_.collectionId) === _)
              .map {
                case (((dataset, datasetUser), user), collection) =>
                  ((dataset, datasetUser), user)
              }

          // (5) Perform full text search
          val queryWithDistinct =
            fullTextSearch(queryAfterCollectionFiltering, textSearch)

            // (6) Join to the dataset workflow status
              .join(datasetStatusManager.datasetStatusMapper)
              .on {
                case (dataset, status) => dataset.statusId === status.id
              }

              // (7) Join to publication status
              .joinLeft(datasetPublicationStatusMapper)
              .on(_._1.publicationStatusId === _.id)

              // (8) Compute whether the dataset is in a publishable state or is locked
              .map {
                case ((dataset, status), publicationStatus) =>
                  (
                    dataset,
                    status,
                    publicationStatus,
                    datasetsMapper.canPublish(dataset),
                    datasetsMapper
                      .isLockedLifted(dataset, publicationStatus, actor)
                  )
              }
              // (9) Optionally filter by whether the dataset can be published
              .filterOpt(canPublish)(_._4 === _)

              // (10) Filter by publication status and publication type
              .filterOpt(publicationTypes)(
                _._3.map(_.publicationType) inSet (_)
              )
              .filterOpt(publicationStatuses)(
                _._3
                  .map(_.publicationStatus)
                  .getOrElse((PublicationStatus.Draft: PublicationStatus))
                  .inSet(_)
              )

              // (11) Ensure uniqueness in results
              .distinct

          val queryWithSort = orderBy match {
            case (OrderByColumn.Name, direction) =>
              if (direction == OrderByDirection.Desc)
                queryWithDistinct.sortBy(_._1.name.desc)
              else queryWithDistinct.sortBy(_._1.name.asc)
            case (OrderByColumn.UpdatedAt, direction) =>
              if (direction == OrderByDirection.Desc)
                queryWithDistinct.sortBy(_._1.updatedAt.desc)
              else queryWithDistinct.sortBy(_._1.updatedAt.asc)
            case (OrderByColumn.IntId, direction) =>
              if (direction == OrderByDirection.Desc)
                queryWithDistinct.sortBy(_._1.id.desc)
              else queryWithDistinct.sortBy(_._1.id.asc)
          }

          val queryWithOffset =
            offset.foldLeft(queryWithSort) { (query, offset) =>
              query.drop(offset)
            }

          val finalQuery = limit
            .foldLeft(queryWithOffset) { (query, limit) =>
              query.take(limit)
            }

          for {
            totalCount <- queryWithDistinct.size.result
              .map(_.toLong)
            datasetsAndPublicationStatus <- finalQuery.result
          } yield
            (
              datasetsAndPublicationStatus.map(DatasetAndStatus.apply _ tupled),
              totalCount
            )

        }
    }
    db.run(query).toEitherT
  }

  def getIgnoreFiles(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[DatasetIgnoreFile]] = {
    db.run(
        datasetIgnoreFiles
          .getIgnoreFilesByDatasetId(dataset.id)
          .result
      )
      .toEitherT
  }

  def setIgnoreFiles(
    dataset: Dataset,
    ignoreFiles: Seq[DatasetIgnoreFile]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[DatasetIgnoreFile]] = {
    val query = for {
      currentIgnoreFiles <- datasetIgnoreFiles
        .getIgnoreFilesByDatasetId(dataset.id)
        .delete

      updatedIgnoreFiles <- datasetIgnoreFiles.returning(datasetIgnoreFiles) ++= ignoreFiles
    } yield updatedIgnoreFiles

    db.run(query.transactionally).toEitherT
  }

  def getIntegrations(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[DatasetIntegration]] = {
    for {
      integrations <- db
        .run(datasetIntegrationsMapper.getByDatasetId(dataset.id).result)
        .toEitherT
    } yield integrations
  }

  def enableWebhook(
    dataset: Dataset,
    webhook: Webhook,
    integrationUser: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DatasetIntegration] = {
    for {
      integration <- db
        .run(
          datasetIntegrationsMapper
            .getOrCreate(webhook.id, dataset.id, actor)
            .transactionally
        )
        .toEitherT

      webhookRole = if (webhook.hasAccess) Role.Manager else Role.Viewer

      _ <- addUserCollaborator(dataset, integrationUser, webhookRole)

    } yield integration
  }

  def disableWebhook(
    dataset: Dataset,
    webhook: Webhook,
    integrationUser: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] = {
    for {
      deletedRowCount <- db
        .run(
          datasetIntegrationsMapper
            .getByDatasetAndWebhookId(dataset.id, webhook.id)
            .delete
        )
        .toEitherT

      _ <- deleteUserCollaborator(dataset, integrationUser)
    } yield deletedRowCount
  }

  def enableDefaultWebhooks(
    dataset: Dataset,
    includedWebhookIds: Option[Set[Int]] = None,
    excludedWebhookIds: Option[Set[Int]] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Option[Int]] = {

    val insertAction = for {
      whToEnable <- webhooksMapper
        .getDefaults(actor, includedWebhookIds, excludedWebhookIds)
        .map(_.id)
        .result
      insert <- datasetIntegrationsMapper ++= whToEnable.map(
        DatasetIntegration(_, dataset.id, actor.id)
      )
    } yield insert

    val insertAction2 = for {
      whToEnable <- webhooksMapper
        .getDefaults(actor, includedWebhookIds, excludedWebhookIds)
        .map(_.integrationUserId)
        .result

      role = Role.Manager

      datasetUsers = whToEnable
        .map(DatasetUser(dataset.id, _, role.toPermission, Some(role)))
      insert2 <- datasetUser ++= datasetUsers

    } yield insert2

    for {
      runInsert <- db.run(insertAction).toEitherT
      _ <- db.run(insertAction2).toEitherT

    } yield runInsert

  }
}
