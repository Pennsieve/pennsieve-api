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
import cats.implicits._
import com.pennsieve.db.DatasetsMapper
import com.pennsieve.domain.{
  CoreError,
  NotFound,
  PredicateError,
  ServiceError
}
import com.pennsieve.managers.DatasetManager
import com.pennsieve.models._

import scala.concurrent.{ ExecutionContext, Future }
import com.pennsieve.traits.PostgresProfile.api.Database

/**
  * In-memory fake of `DatasetManager`. Only the methods exercised by the
  * migrated `TestDataSetsController` are implemented; anything else falls
  * through to the trait body and trips the throwing `db`.
  */
class FakeDatasetManager(
  val state: InMemoryState,
  org: Organization,
  val actor: User
) extends DatasetManager {

  def db: Database =
    sys.error(
      "FakeDatasetManager: a method not yet stubbed by your test tried to " +
        "use the database. Override the method on this fake."
    )

  override lazy val datasetsMapper: DatasetsMapper = new DatasetsMapper(org)

  // ---- helpers ----------------------------------------------------------
  private def datasetsForOrg: Iterable[Dataset] =
    state.datasets.collect { case ((orgId, _), d) if orgId == org.id => d }

  private def putDataset(d: Dataset): Dataset = {
    state.datasets.put((org.id, d.id), d)
    d
  }

  private def appendStatusLog(
    d: Dataset,
    status: com.pennsieve.models.DatasetStatus
  ): Unit = {
    val entry = com.pennsieve.models.DatasetStatusLog(
      datasetId = d.id,
      statusId = Some(status.id),
      statusName = status.name,
      statusDisplayName = status.displayName,
      userId = Some(actor.id)
    )
    val buf = state.datasetStatusLog
      .getOrElseUpdate(
        (org.id, d.id),
        scala.collection.mutable.ArrayBuffer.empty
      )
    buf += entry
    ()
  }

  // ---- create -----------------------------------------------------------
  override def create(
    name: String,
    description: Option[String] = None,
    state0: DatasetState = DatasetState.READY,
    automaticallyProcessPackages: Boolean = false,
    statusId: Option[Int] = None,
    license: Option[License] = None,
    tags: List[String] = List.empty,
    bannerId: Option[java.util.UUID] = None,
    readmeId: Option[java.util.UUID] = None,
    changelogId: Option[java.util.UUID] = None,
    dataUseAgreement: Option[DataUseAgreement] = None,
    `type`: DatasetType = DatasetType.Research
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Dataset] = {
    val trimmed = name.trim
    if (trimmed.isEmpty)
      EitherT.leftT(PredicateError("dataset name must not be empty"))
    else if (trimmed.length >= 256)
      EitherT.leftT(
        PredicateError("dataset name must be less than 255 characters")
      )
    else if (datasetsForOrg.exists(_.name == trimmed))
      EitherT.leftT(PredicateError("dataset name must be unique"))
    else {
      // Resolve status: explicit statusId, else first status in the org
      val resolvedStatus = statusId
        .flatMap(id => state.datasetStatuses.get((org.id, id)))
        .orElse(
          state.datasetStatuses
            .collect { case ((orgId, _), s) if orgId == org.id => s }
            .toSeq
            .sortBy(_.id)
            .headOption
        )
      resolvedStatus match {
        case None =>
          EitherT.leftT(NotFound("No default dataset status found"))
        case Some(status) =>
          val id = state.newId()
          val nodeId = NodeCodes.generateId(NodeCodes.dataSetCode)
          val ds = Dataset(
            nodeId = nodeId,
            name = trimmed,
            state = state0,
            description = description,
            automaticallyProcessPackages = automaticallyProcessPackages,
            statusId = status.id,
            license = license,
            tags = tags,
            bannerId = bannerId,
            readmeId = readmeId,
            changelogId = changelogId,
            dataUseAgreementId = dataUseAgreement.map(_.id),
            `type` = `type`,
            id = id
          )
          putDataset(ds)
          // append initial status log entry
          appendStatusLog(ds, status)
          // owner role for actor
          state.datasetUserRoles.put((org.id, actor.id, ds.id), Role.Owner)
          // Auto-create-or-link a Contributor for the actor and link to dataset
          // Mirrors trait body: contributorManager.getOrCreateContributorFromUser(actor)
          val contribByUser = state.contributors.collect {
            case ((orgId, _), c)
                if orgId == org.id && c.userId.contains(actor.id) =>
              c
          }.headOption
          val contribByEmail = state.contributors.collect {
            case ((orgId, _), c)
                if orgId == org.id && c.email.exists(
                  _.equalsIgnoreCase(actor.email)
                ) =>
              c
          }.headOption
          val contributor = contribByUser.orElse(contribByEmail).getOrElse {
            val cid = state.newId()
            val c = Contributor(
              firstName = Some(actor.firstName),
              lastName = Some(actor.lastName),
              email = Some(actor.email.toLowerCase),
              middleInitial = actor.middleInitial,
              degree = actor.degree,
              orcid = actor.orcidAuthorization.map(_.orcid),
              userId = Some(actor.id),
              id = cid
            )
            state.contributors.put((org.id, cid), c)
            c
          }
          state.datasetContributors.put((org.id, ds.id, contributor.id), 0)
          EitherT.rightT(ds)
      }
    }
  }

  // ---- get / getByNodeId / getByAnyId / nameExists ---------------------
  override def get(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Dataset] =
    state.datasets.get((org.id, id)) match {
      case Some(d) if d.state != DatasetState.DELETING => EitherT.rightT(d)
      case _ => EitherT.leftT(NotFound(s"Dataset ($id)"))
    }

  override def getByNodeId(
    nodeId: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Dataset] =
    datasetsForOrg.find(
      d => d.nodeId == nodeId && d.state != DatasetState.DELETING
    ) match {
      case Some(d) => EitherT.rightT(d)
      case None => EitherT.leftT(NotFound(nodeId))
    }

  override def getByAnyId(
    nodeOrIntId: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Dataset] =
    scala.util
      .Try(nodeOrIntId.toInt)
      .toOption
      .map(get(_))
      .getOrElse(getByNodeId(nodeOrIntId))

  override def nameExists(name: String): Future[Boolean] =
    Future.successful(datasetsForOrg.exists(_.name == name))

  override def getByExternalIdWithMaxRole(
    externalId: com.pennsieve.models.ExternalId
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Dataset, Option[Role])] = {
    val dsOpt = externalId.value match {
      case Left(id) => state.datasets.get((org.id, id))
      case Right(nodeId) => datasetsForOrg.find(_.nodeId == nodeId)
    }
    dsOpt.filter(_.state != DatasetState.DELETING) match {
      case None =>
        EitherT.leftT[Future, (Dataset, Option[Role])](
          NotFound(externalId.toString): CoreError
        )
      case Some(d) =>
        val viaUser = state.datasetUserRoles.get((org.id, actor.id, d.id))
        val viaOrg = state.datasetOrgRoles.get((org.id, d.id))
        val userTeamIds = state.teamMemberships.collect {
          case ((o, t, u), _) if o == org.id && u == actor.id => t
        }.toSet
        val viaTeam = state.datasetTeamRoles.collect {
          case ((o, t, dsId), r)
              if o == org.id && dsId == d.id && userTeamIds.contains(t) =>
            r
        }
        val role: Option[Role] = (viaUser.toSeq ++ viaOrg.toSeq ++ viaTeam)
          .reduceOption((a, b) => if (a.weight >= b.weight) a else b)
        val effective: Option[Role] =
          if (actor.isSuperAdmin) Role.maxRole else role
        if (effective.isDefined)
          EitherT.rightT[Future, CoreError]((d, effective))
        else
          EitherT.leftT[Future, (Dataset, Option[Role])](
            com.pennsieve.domain
              .DatasetRolePermissionError(actor.nodeId, d.id): CoreError
          )
    }
  }

  // ---- update / delete -------------------------------------------------
  override def update(
    dataset: Dataset,
    checkforDuplicateNames: Boolean = true
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Dataset] = {
    if (dataset.name.trim.isEmpty)
      EitherT.leftT(PredicateError("dataset name must not be empty"))
    else if (dataset.name.trim.length >= 256)
      EitherT.leftT(
        PredicateError("dataset name must be less than 255 characters")
      )
    else {
      // duplicate-name check across non-deleting datasets, except self
      val collision = datasetsForOrg.find(
        d =>
          d.state != DatasetState.DELETING && d.name == dataset.name &&
            d.id != dataset.id
      )
      if (checkforDuplicateNames && collision.isDefined)
        EitherT.leftT(
          ServiceError(s"name is already taken: ${dataset.name}"): CoreError
        )
      else {
        // Mirror Postgres dataset_update_etag / dataset_update_updated_at
        // triggers — every successful update refreshes updatedAt so the etag
        // (computed from it) advances.  We bump by at least one second past
        // the existing updatedAt to guarantee the etag (epoch-second granular)
        // changes even when the test runs very fast.
        val newUpdatedAt = {
          val now = java.time.ZonedDateTime.now()
          val existing = state.datasets
            .get((org.id, dataset.id))
            .map(_.updatedAt)
            .getOrElse(dataset.updatedAt)
          if (now.toEpochSecond > existing.toEpochSecond) now
          else existing.plusSeconds(1)
        }
        val refreshed = dataset.copy(
          updatedAt = newUpdatedAt,
          etag = com.pennsieve.models.ETag(newUpdatedAt)
        )
        // If statusId changed, append a status log entry.
        val previousStatusId = state.datasets
          .get((org.id, dataset.id))
          .map(_.statusId)
        if (previousStatusId.exists(_ != refreshed.statusId)) {
          state.datasetStatuses
            .get((org.id, refreshed.statusId))
            .foreach(appendStatusLog(refreshed, _))
        }
        putDataset(refreshed)
        if (refreshed.state == DatasetState.DELETING)
          EitherT.rightT(refreshed)
        else
          get(refreshed.id)
      }
    }
  }

  override def getStatusLog(
    dataset: Dataset,
    limit: Int,
    offset: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[
    Future,
    CoreError,
    (Seq[(com.pennsieve.models.DatasetStatusLog, Option[User])], Long)
  ] = {
    val buf = state.datasetStatusLog
      .get((org.id, dataset.id))
      .map(_.toSeq)
      .getOrElse(Seq.empty)
    // Newest first
    val ordered = buf.reverse
    val total = ordered.size.toLong
    val page = ordered.drop(offset).take(limit)
    val rows = page.map(
      e =>
        e -> e.userId
          .flatMap(uid => state.users.get(uid))
    )
    EitherT.rightT((rows, total))
  }

  override def getCollaborators(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, com.pennsieve.managers.Collaborators] = {
    val users = state.datasetUserRoles
      .collect {
        case ((orgId, uid, dsId), role)
            if orgId == org.id && dsId == dataset.id && role != Role.Owner =>
          uid
      }
      .flatMap(state.users.get)
      .toSeq
    val teams = state.datasetTeamRoles
      .collect {
        case ((orgId, tid, dsId), _) if orgId == org.id && dsId == dataset.id =>
          tid
      }
      .flatMap(
        tid =>
          state.teams.get((org.id, tid)).map { t =>
            (
              t,
              com.pennsieve.models.OrganizationTeam(
                organizationId = org.id,
                teamId = t.id,
                permission = DBPermission.Delete
              )
            )
          }
      )
      .toSeq
    val orgs =
      if (state.datasetOrgRoles.contains((org.id, dataset.id))) Seq(org)
      else Seq.empty
    EitherT.rightT(com.pennsieve.managers.Collaborators(users, orgs, teams))
  }

  // ---- collaborators / counts -----------------------------------------
  override def getCollaboratorCounts(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, com.pennsieve.models.CollaboratorCounts] = {
    val userCount = state.datasetUserRoles.count {
      case ((orgId, uid, dsId), role) =>
        orgId == org.id && dsId == dataset.id && uid != actor.id && role != Role.Owner
    }
    val teamCount = state.datasetTeamRoles.count {
      case ((orgId, _, dsId), _) => orgId == org.id && dsId == dataset.id
    }
    val orgCount = dataset.role.map(_ => 1).getOrElse(0)
    EitherT.rightT(
      com.pennsieve.models
        .CollaboratorCounts(userCount, orgCount, teamCount)
    )
  }

  override def getCollaboratorCounts(
    datasets: List[Dataset]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Map[
    Dataset,
    com.pennsieve.models.CollaboratorCounts
  ]] = {
    val out = datasets.map { d =>
      val userCount = state.datasetUserRoles.count {
        case ((orgId, uid, dsId), role) =>
          orgId == org.id && dsId == d.id && uid != actor.id && role != Role.Owner
      }
      val teamCount = state.datasetTeamRoles.count {
        case ((orgId, _, dsId), _) => orgId == org.id && dsId == d.id
      }
      val orgCount = d.role.map(_ => 1).getOrElse(0)
      d -> com.pennsieve.models
        .CollaboratorCounts(userCount, orgCount, teamCount)
    }.toMap
    EitherT.rightT(out)
  }

  // ---- role lookups ---------------------------------------------------
  override def maxRole(
    dataset: Dataset,
    user: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Role] = {
    val viaUser = state.datasetUserRoles.get((org.id, user.id, dataset.id))
    val viaOrg = state.datasetOrgRoles.get((org.id, dataset.id))
    val viaTeam = state.datasetTeamRoles.collect {
      case ((orgId, _, dsId), r) if orgId == org.id && dsId == dataset.id => r
    }
    val all = viaUser.toSeq ++ viaOrg.toSeq ++ viaTeam
    all.reduceOption((a, b) => if (a.weight >= b.weight) a else b) match {
      case Some(r) => EitherT.rightT(r)
      case None =>
        EitherT.leftT(
          com.pennsieve.domain
            .DatasetRolePermissionError(user.nodeId, dataset.id)
        )
    }
  }

  // ---- ownership lookups ----------------------------------------------
  override def getOwner(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, User] = {
    val ownerId = state.datasetUserRoles.collectFirst {
      case ((orgId, uid, dsId), Role.Owner)
          if orgId == org.id && dsId == dataset.id =>
        uid
    }
    ownerId.flatMap(state.users.get) match {
      case Some(u) => EitherT.rightT(u)
      case None => EitherT.leftT(NotFound(dataset.nodeId))
    }
  }

  override def getOwners(
    datasets: Seq[Dataset]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Map[Dataset, User]] = {
    val out = datasets.flatMap { d =>
      val ownerId = state.datasetUserRoles.collectFirst {
        case ((orgId, uid, dsId), Role.Owner)
            if orgId == org.id && dsId == d.id =>
          uid
      }
      ownerId.flatMap(state.users.get).map(d -> _)
    }.toMap
    if (out.size != datasets.size)
      EitherT.leftT(
        NotFound(
          datasets.find(d => !out.contains(d)).map(_.nodeId).getOrElse("")
        )
      )
    else
      EitherT.rightT(out)
  }

  override def getManagers(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[User]] = {
    val ids = state.datasetUserRoles.collect {
      case ((orgId, uid, dsId), Role.Manager)
          if orgId == org.id && dsId == dataset.id =>
        uid
    }.toList
    EitherT.rightT(ids.flatMap(state.users.get))
  }

  // ---- contributors ----------------------------------------------------
  override def getContributors(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[(Contributor, Option[User])]] = {
    val rows = state.datasetContributors
      .collect {
        case ((orgId, dsId, cid), order)
            if orgId == org.id && dsId == dataset.id =>
          (order, cid)
      }
      .toSeq
      .sortBy(_._1)
    val out = rows.flatMap {
      case (_, cid) =>
        state.contributors
          .get((org.id, cid))
          .map(c => (c, c.userId.flatMap(state.users.get)))
    }
    EitherT.rightT(out)
  }

  override def addContributor(
    dataset: Dataset,
    contributorId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] = {
    state.contributors.get((org.id, contributorId)) match {
      case None => EitherT.leftT(NotFound(s"Contributor ($contributorId)"))
      case Some(_) =>
        val nextOrder = {
          val current = state.datasetContributors.collect {
            case ((orgId, dsId, _), order)
                if orgId == org.id && dsId == dataset.id =>
              order
          }
          if (current.isEmpty) 0 else current.max + 1
        }
        state.datasetContributors
          .put((org.id, dataset.id, contributorId), nextOrder)
        EitherT.rightT(())
    }
  }

  override def switchContributorOrder(
    dataset: Dataset,
    contributor: com.pennsieve.models.DatasetContributor,
    otherContributor: com.pennsieve.models.DatasetContributor
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] = {
    state.datasetContributors
      .put(
        (org.id, dataset.id, contributor.contributorId),
        otherContributor.order
      )
    state.datasetContributors
      .put(
        (org.id, dataset.id, otherContributor.contributorId),
        contributor.order
      )
    EitherT.rightT(())
  }

  override def removeContributor(
    dataset: Dataset,
    contributorId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] =
    if (!state.datasetContributors.contains(
        (org.id, dataset.id, contributorId)
      ))
      EitherT.leftT(NotFound(s"Contributor $contributorId"))
    else {
      state.datasetContributors.remove((org.id, dataset.id, contributorId))
      EitherT.rightT(())
    }

  override def getContributor(
    dataset: Dataset,
    contributorId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, com.pennsieve.models.DatasetContributor] =
    state.datasetContributors.get((org.id, dataset.id, contributorId)) match {
      case Some(order) =>
        EitherT.rightT(
          com.pennsieve.models
            .DatasetContributor(dataset.id, contributorId, order)
        )
      case None => EitherT.leftT(NotFound(dataset.nodeId))
    }

  // ---- find -----------------------------------------------------------
  override def find(
    withRole: Role
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[com.pennsieve.db.DatasetAndStatus]] =
    find(actor, withRole, None)

  override def find(
    user: User,
    withRole: Role,
    datasetIds: Option[List[Int]] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[com.pennsieve.db.DatasetAndStatus]] = {
    val statusById: Map[Int, DatasetStatus] = state.datasetStatuses.collect {
      case ((orgId, sid), s) if orgId == org.id => sid -> s
    }.toMap

    val out = datasetsForOrg
      .filter(d => d.state != DatasetState.DELETING)
      .filter(d => datasetIds.forall(_.contains(d.id)))
      .filter { d =>
        if (user.isSuperAdmin) true
        else {
          val viaUser = state.datasetUserRoles.get((org.id, user.id, d.id))
          val viaOrg = state.datasetOrgRoles.get((org.id, d.id))
          val viaTeam = state.datasetTeamRoles.collect {
            case ((orgId, _, dsId), r) if orgId == org.id && dsId == d.id => r
          }
          val all = viaUser.toSeq ++ viaOrg.toSeq ++ viaTeam
          val maxR =
            all.reduceOption((a, b) => if (a.weight >= b.weight) a else b)
          maxR.exists(_.weight >= withRole.weight)
        }
      }
      .map { d =>
        val s = statusById.getOrElse(d.statusId, statusById.values.head)
        val pub = state.datasetPublicationStatuses
          .collect {
            case ((orgId, _), ps) if orgId == org.id && ps.datasetId == d.id =>
              ps
          }
          .toSeq
          .sortBy(_.id)
          .lastOption
        val canPub = d.tags.nonEmpty && d.license.isDefined &&
          state.datasetContributors.keys.exists(
            k => k._1 == org.id && k._2 == d.id
          )
        val locked = pub.exists(
          p =>
            p.publicationStatus != PublicationStatus.Cancelled &&
              p.publicationStatus != PublicationStatus.Rejected &&
              p.publicationStatus != PublicationStatus.Completed
        )
        com.pennsieve.db.DatasetAndStatus(d, s, pub, canPub, locked)
      }
      .toSeq

    EitherT.rightT(out)
  }

  // ---- listing --------------------------------------------------------
  override def getDatasetPaginated(
    withRole: Role,
    limit: Option[Int],
    offset: Option[Int],
    orderBy: (DatasetManager.OrderByColumn, DatasetManager.OrderByDirection),
    status: Option[DatasetStatus],
    textSearch: Option[String],
    publicationStatuses: Option[Set[PublicationStatus]] = None,
    publicationTypes: Option[Set[PublicationType]] = None,
    canPublish: Option[Boolean] = None,
    restrictToRole: Boolean = false,
    collectionId: Option[Int] = None,
    isGuest: Boolean = false,
    datasetType: Option[DatasetType] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[
    Future,
    CoreError,
    (Seq[com.pennsieve.db.DatasetAndStatus], Long)
  ] = {
    // 1) compute role per dataset for the actor — same logic as
    // BaseApiUnitTest.userRoles
    val datasetIdsToRole: Map[Int, Role] = datasetsForOrg.flatMap { d =>
      val viaUser = state.datasetUserRoles.get((org.id, actor.id, d.id))
      val viaOrg = state.datasetOrgRoles.get((org.id, d.id))
      val viaTeam = state.datasetTeamRoles.collect {
        case ((orgId, _, dsId), r) if orgId == org.id && dsId == d.id => r
      }
      val all = viaUser.toSeq ++ viaOrg.toSeq ++ viaTeam
      val maxR =
        if (actor.isSuperAdmin) Some(Role.Owner)
        else all.reduceOption((a, b) => if (a.weight >= b.weight) a else b)
      maxR.map(d.id -> _)
    }.toMap

    val candidates: Iterable[Dataset] = datasetsForOrg.filter { d =>
      val role = datasetIdsToRole.get(d.id)
      val passesRole = role.exists { r =>
        if (restrictToRole) r == withRole else r.weight >= withRole.weight
      }
      passesRole && d.state != DatasetState.DELETING
    }

    val byStatus = status match {
      case Some(s) => candidates.filter(_.statusId == s.id)
      case None => candidates
    }
    val byType = datasetType match {
      case Some(t) => byStatus.filter(_.`type` == t)
      case None => byStatus
    }
    val byCollection = collectionId match {
      case Some(cid) =>
        byType.filter(
          d => state.datasetCollections.contains((org.id, d.id, cid))
        )
      case None => byType
    }
    val byText = textSearch match {
      case Some(t) =>
        val q = t.toLowerCase
        byCollection.filter(
          d =>
            d.name.toLowerCase.contains(q) ||
              d.description.exists(_.toLowerCase.contains(q))
        )
      case None => byCollection
    }

    val statusById: Map[Int, DatasetStatus] = state.datasetStatuses.collect {
      case ((orgId, sid), s) if orgId == org.id => sid -> s
    }.toMap

    val withPub: Iterable[com.pennsieve.db.DatasetAndStatus] = byText.map { d =>
      val s = statusById.getOrElse(d.statusId, statusById.values.head)
      val pub = state.datasetPublicationStatuses
        .collect {
          case ((orgId, _), ps) if orgId == org.id && ps.datasetId == d.id =>
            ps
        }
        .toSeq
        .sortBy(_.id)
        .lastOption
      val locked = pub.exists(
        p => PublicationStatus.lockedStatuses.contains(p.publicationStatus)
      )
      // Owner with ORCID — find owner of dataset and check orcid
      val ownerHasOrcid = state.datasetUserRoles
        .collect {
          case ((orgId, uid, dsId), Role.Owner)
              if orgId == org.id && dsId == d.id =>
            uid
        }
        .flatMap(state.users.get)
        .exists(_.orcidAuthorization.isDefined)
      val hasContributor = state.datasetContributors.keys
        .exists(k => k._1 == org.id && k._2 == d.id)
      val canPub = d.name.nonEmpty &&
        d.description.exists(_.nonEmpty) &&
        d.tags.nonEmpty &&
        d.license.isDefined &&
        d.readmeId.isDefined &&
        d.bannerId.isDefined &&
        hasContributor &&
        ownerHasOrcid &&
        !locked
      com.pennsieve.db.DatasetAndStatus(d, s, pub, canPub, locked)
    }

    val byCanPublish = canPublish match {
      case Some(b) => withPub.filter(_.canPublish == b)
      case None => withPub
    }

    val byPubTypes = publicationTypes match {
      case Some(types) =>
        byCanPublish.filter(
          _.publicationStatus.exists(ps => types.contains(ps.publicationType))
        )
      case None => byCanPublish
    }
    val byPubStatuses = publicationStatuses match {
      case Some(statuses) =>
        byPubTypes.filter { ds =>
          val cur = ds.publicationStatus
            .map(_.publicationStatus)
            .getOrElse(PublicationStatus.Draft)
          statuses.contains(cur)
        }
      case None => byPubTypes
    }

    val sorted = orderBy match {
      case (DatasetManager.OrderByColumn.Name, dir) =>
        val s = byPubStatuses.toSeq.sortBy(_.dataset.name)
        if (dir == DatasetManager.OrderByDirection.Desc) s.reverse else s
      case (DatasetManager.OrderByColumn.UpdatedAt, dir) =>
        val s = byPubStatuses.toSeq.sortBy(_.dataset.updatedAt.toInstant)
        if (dir == DatasetManager.OrderByDirection.Desc) s.reverse else s
      case (DatasetManager.OrderByColumn.IntId, dir) =>
        val s = byPubStatuses.toSeq.sortBy(_.dataset.id)
        if (dir == DatasetManager.OrderByDirection.Desc) s.reverse else s
    }

    val totalCount = sorted.size.toLong
    val paged = (offset, limit) match {
      case (Some(o), Some(l)) => sorted.drop(o).take(l)
      case (Some(o), None) => sorted.drop(o)
      case (None, Some(l)) => sorted.take(l)
      case _ => sorted
    }
    EitherT.rightT((paged, totalCount))
  }

  // ---- bulk add/remove (PUT/DELETE /:id/collaborators) ---------------
  override def addCollaborators(
    dataset: Dataset,
    collaboratorIds: Set[String]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, com.pennsieve.managers.CollaboratorChanges] = {
    val (validNode, invalidNode) = collaboratorIds.partition(validShareId)

    val currentSharees: Set[String] = {
      val shareUsers = state.datasetUserRoles.collect {
        case ((orgId, uid, dsId), role)
            if orgId == org.id && dsId == dataset.id && role != Role.Owner =>
          state.users.get(uid).map(_.nodeId)
      }.flatten
      val shareTeams = state.datasetTeamRoles.collect {
        case ((orgId, tid, dsId), _) if orgId == org.id && dsId == dataset.id =>
          state.teams.get((org.id, tid)).map(_.nodeId)
      }.flatten
      val shareOrg =
        state.datasetOrgRoles
          .get((org.id, dataset.id))
          .map(_ => org.nodeId)
          .toSet
      shareUsers.toSet ++ shareTeams.toSet ++ shareOrg
    }

    val results = scala.collection.mutable
      .Map[String, com.pennsieve.managers.ChangeResponse]()
    invalidNode.foreach(
      id =>
        results.put(
          id,
          com.pennsieve.managers
            .ChangeResponse(success = false, Some("Not a valid id"))
        )
    )
    validNode.foreach { id =>
      if (currentSharees.contains(id))
        results.put(
          id,
          com.pennsieve.managers
            .ChangeResponse(success = false, Some("Already has access"))
        )
      else if (id == org.nodeId) {
        state.datasetOrgRoles.put((org.id, dataset.id), Role.Editor)
        state.datasets.get((org.id, dataset.id)).foreach { d =>
          state.datasets
            .put((org.id, dataset.id), d.copy(role = Some(Role.Editor)))
        }
        results.put(id, com.pennsieve.managers.ChangeResponse(success = true))
      } else
        state.users.values.find(_.nodeId == id) match {
          case Some(user)
              if state.orgUserPermissions.contains((org.id, user.id)) =>
            state.datasetUserRoles
              .put((org.id, user.id, dataset.id), Role.Editor)
            results.put(
              id,
              com.pennsieve.managers.ChangeResponse(success = true)
            )
          case Some(_) =>
            results.put(
              id,
              com.pennsieve.managers.ChangeResponse(
                success = false,
                Some("Id does not have access to this Organization")
              )
            )
          case None =>
            state.teams.values.find(_.nodeId == id) match {
              case Some(team) =>
                state.datasetTeamRoles
                  .put((org.id, team.id, dataset.id), Role.Editor)
                results.put(
                  id,
                  com.pennsieve.managers.ChangeResponse(success = true)
                )
              case None =>
                results.put(
                  id,
                  com.pennsieve.managers.ChangeResponse(
                    success = false,
                    Some("Id does not have access to this Organization")
                  )
                )
            }
        }
    }

    val latest = state.datasets.getOrElse((org.id, dataset.id), dataset)
    for {
      counts <- getCollaboratorCounts(latest)
    } yield
      com.pennsieve.managers
        .CollaboratorChanges(changes = results.toMap, counts = counts)
  }

  override def removeCollaborators(
    dataset: Dataset,
    collaborators: Set[String],
    removeIntegrationUsers: Boolean
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, com.pennsieve.managers.CollaboratorChanges] = {
    val adminNodeId = actor.nodeId
    val collaboratorsWithoutAdmin = collaborators - adminNodeId
    val (validNode, invalidNode) =
      collaboratorsWithoutAdmin.partition(validShareId)

    val currentSharees: Set[String] = {
      val shareUsers = state.datasetUserRoles.collect {
        case ((orgId, uid, dsId), _) if orgId == org.id && dsId == dataset.id =>
          state.users.get(uid).map(_.nodeId)
      }.flatten
      val shareTeams = state.datasetTeamRoles.collect {
        case ((orgId, tid, dsId), _) if orgId == org.id && dsId == dataset.id =>
          state.teams.get((org.id, tid)).map(_.nodeId)
      }.flatten
      val shareOrg =
        state.datasetOrgRoles
          .get((org.id, dataset.id))
          .map(_ => org.nodeId)
          .toSet
      shareUsers.toSet ++ shareTeams.toSet ++ shareOrg
    }

    val results = scala.collection.mutable
      .Map[String, com.pennsieve.managers.ChangeResponse]()
    if (collaborators.contains(adminNodeId))
      results.put(
        adminNodeId,
        com.pennsieve.managers.ChangeResponse(
          success = false,
          Some("Cannot revoke your own access")
        )
      )
    invalidNode.foreach(
      id =>
        results.put(
          id,
          com.pennsieve.managers
            .ChangeResponse(success = false, Some("Not a valid id"))
        )
    )
    validNode.foreach { id =>
      if (!currentSharees.contains(id))
        results.put(
          id,
          com.pennsieve.managers
            .ChangeResponse(success = false, Some("Not currently shared"))
        )
      else if (id == org.nodeId) {
        state.datasetOrgRoles.remove((org.id, dataset.id))
        state.datasets.get((org.id, dataset.id)).foreach { d =>
          state.datasets.put((org.id, dataset.id), d.copy(role = None))
        }
        results.put(id, com.pennsieve.managers.ChangeResponse(success = true))
      } else
        state.users.values.find(_.nodeId == id) match {
          case Some(user) if user.isIntegrationUser == removeIntegrationUsers =>
            state.datasetUserRoles
              .filter(
                p =>
                  p._1._1 == org.id && p._1._3 == dataset.id && p._2 != Role.Owner && p._1._2 == user.id
              )
              .keys
              .foreach(state.datasetUserRoles.remove)
            results.put(
              id,
              com.pennsieve.managers.ChangeResponse(success = true)
            )
          case _ =>
            state.teams.values.find(_.nodeId == id) match {
              case Some(team) =>
                state.datasetTeamRoles
                  .filter(
                    p =>
                      p._1._1 == org.id && p._1._3 == dataset.id && p._1._2 == team.id
                  )
                  .keys
                  .foreach(state.datasetTeamRoles.remove)
                results.put(
                  id,
                  com.pennsieve.managers.ChangeResponse(success = true)
                )
              case None =>
                results.put(
                  id,
                  com.pennsieve.managers
                    .ChangeResponse(
                      success = false,
                      Some("Not currently shared")
                    )
                )
            }
        }
    }

    val latest = state.datasets.getOrElse((org.id, dataset.id), dataset)
    for {
      counts <- getCollaboratorCounts(latest)
    } yield
      com.pennsieve.managers
        .CollaboratorChanges(changes = results.toMap, counts = counts)
  }

  // ---- user collaborators -------------------------------------------
  override def addUserCollaborator(
    dataset: Dataset,
    user: User,
    role: Role
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, OldRole] = {
    val ownerOpt = state.datasetUserRoles.collectFirst {
      case ((orgId, uid, dsId), Role.Owner)
          if orgId == org.id && dsId == dataset.id =>
        uid
    }
    if (role == Role.Owner)
      EitherT.leftT(
        com.pennsieve.domain.InvalidAction(
          "Another person already owns this dataset. Please contact your organization admin for help."
        )
      )
    else if (ownerOpt.contains(user.id))
      EitherT.leftT(
        com.pennsieve.domain.InvalidAction(
          "To relinquish ownership of a dataset, please use the PUT /collaborators/owner endpoint."
        )
      )
    else if (!state.orgUserPermissions.contains((org.id, user.id)))
      EitherT.leftT(NotFound(s"user ${user.nodeId}"))
    else {
      val oldRole = state.datasetUserRoles.get((org.id, user.id, dataset.id))
      state.datasetUserRoles.put((org.id, user.id, dataset.id), role)
      EitherT.rightT(OldRole(oldRole))
    }
  }

  override def getUserCollaboratorRole(
    dataset: Dataset,
    user: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Option[Role]] =
    EitherT.rightT(state.datasetUserRoles.get((org.id, user.id, dataset.id)))

  override def getUserCollaborators(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[(User, Role)]] = {
    val rows = state.datasetUserRoles.collect {
      case ((orgId, uid, dsId), role)
          if orgId == org.id && dsId == dataset.id && role != Role.Owner =>
        (uid, role)
    }.toList
    val out = rows.flatMap {
      case (uid, role) => state.users.get(uid).map(u => (u, role))
    }
    EitherT.rightT(out)
  }

  override def deleteUserCollaborator(
    dataset: Dataset,
    user: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, OldRole] = {
    val oldRole = state.datasetUserRoles.get((org.id, user.id, dataset.id))
    // Mirrors filters: don't delete actor's own role; don't delete owner role
    if (user.id != actor.id && oldRole != Some(Role.Owner))
      state.datasetUserRoles.remove((org.id, user.id, dataset.id))
    EitherT.rightT(OldRole(oldRole))
  }

  override def addTeamCollaborator(
    dataset: Dataset,
    team: Team,
    role: Role
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, OldRole] = {
    val old = state.datasetTeamRoles.get((org.id, team.id, dataset.id))
    state.datasetTeamRoles.put((org.id, team.id, dataset.id), role)
    EitherT.rightT(OldRole(old))
  }

  override def getTeamCollaboratorRole(
    dataset: Dataset,
    team: Team
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Option[Role]] =
    EitherT.rightT(state.datasetTeamRoles.get((org.id, team.id, dataset.id)))

  override def getTeamCollaborators(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[(Team, Role)]] = {
    val rows = state.datasetTeamRoles.collect {
      case ((orgId, tid, dsId), role)
          if orgId == org.id && dsId == dataset.id =>
        (tid, role)
    }.toList
    val out = rows.flatMap {
      case (tid, role) => state.teams.get((org.id, tid)).map(t => (t, role))
    }
    EitherT.rightT(out)
  }

  override def deleteTeamCollaborator(
    dataset: Dataset,
    team: Team
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, OldRole] = {
    val old = state.datasetTeamRoles.get((org.id, team.id, dataset.id))
    state.datasetTeamRoles.remove((org.id, team.id, dataset.id))
    EitherT.rightT(OldRole(old))
  }

  override def setOrganizationCollaboratorRole(
    dataset: Dataset,
    role: Option[Role]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] = {
    role match {
      case Some(r) => state.datasetOrgRoles.put((org.id, dataset.id), r)
      case None => state.datasetOrgRoles.remove((org.id, dataset.id))
    }
    state.datasets.get((org.id, dataset.id)).foreach { d =>
      state.datasets.put((org.id, dataset.id), d.copy(role = role))
    }
    EitherT.rightT(())
  }

  // ---- releases & external repositories -------------------------------
  override def getReleases(
    datasetId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Option[
    Seq[com.pennsieve.models.DatasetRelease]
  ]] = {
    val releases = state.datasetReleases.collect {
      case ((orgId, dsId, _), r) if orgId == org.id && dsId == datasetId => r
    }.toSeq
    EitherT.rightT(if (releases.isEmpty) None else Some(releases))
  }

  override def addRelease(
    release: com.pennsieve.models.DatasetRelease
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, com.pennsieve.models.DatasetRelease] = {
    state.datasets.get((org.id, release.datasetId)) match {
      case Some(d) if d.`type` == DatasetType.Release =>
        val id = state.newId()
        val withId = release.copy(id = id)
        state.datasetReleases.put((org.id, release.datasetId, id), withId)
        EitherT.rightT(withId)
      case Some(_) =>
        EitherT.leftT(
          PredicateError(
            s"dataset type must be ${DatasetType.Release.toString}"
          )
        )
      case None => EitherT.leftT(NotFound(s"Dataset (${release.datasetId})"))
    }
  }

  override def getRegistration(
    dataset: Dataset,
    registry: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Option[
    com.pennsieve.models.DatasetRegistration
  ]] =
    EitherT.rightT(
      state.datasetRegistrations.get((org.id, dataset.id, registry))
    )

  override def addRegistration(
    registration: com.pennsieve.models.DatasetRegistration
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, com.pennsieve.models.DatasetRegistration] = {
    state.datasetRegistrations.put(
      (org.id, registration.datasetId, registration.registry),
      registration
    )
    EitherT.rightT(registration)
  }

  override def removeRegistration(
    dataset: Dataset,
    registry: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Boolean] = {
    state.datasetRegistrations.remove((org.id, dataset.id, registry))
    EitherT.rightT(true)
  }

  override def switchOwner(
    dataset: Dataset,
    currentOwner: User,
    newOwner: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] = {
    // Mirror the real `canShareWithUser` check — new owner must be a member
    // of the dataset's organization.
    if (!state.orgUserPermissions.contains((org.id, newOwner.id)))
      EitherT.leftT(NotFound(s"user ${newOwner.nodeId}"): CoreError)
    else {
      state.datasetUserRoles
        .put((org.id, currentOwner.id, dataset.id), Role.Manager)
      state.datasetUserRoles.put((org.id, newOwner.id, dataset.id), Role.Owner)
      EitherT.rightT(())
    }
  }

  override def addExternalRepository(
    extRepo: com.pennsieve.models.ExternalRepository
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, com.pennsieve.models.ExternalRepository] = {
    val datasetId = extRepo.datasetId.getOrElse(-1)
    state.datasets.get((org.id, datasetId)) match {
      case Some(d) if d.`type` == DatasetType.Release =>
        state.externalRepositories.put((org.id, datasetId), extRepo)
        EitherT.rightT(extRepo)
      case Some(_) =>
        EitherT.leftT(
          PredicateError(
            s"dataset type must be ${DatasetType.Release.toString}"
          )
        )
      case None => EitherT.leftT(NotFound(s"Dataset ($datasetId)"))
    }
  }

  override def getExternalRepository(
    organizationId: Int,
    datasetId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Option[
    com.pennsieve.models.ExternalRepository
  ]] =
    EitherT.rightT(state.externalRepositories.get((organizationId, datasetId)))

  override def sourceFileCount(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Long] = {
    val pkgIds = state.packages.collect {
      case ((orgId, _), p) if orgId == org.id && p.datasetId == dataset.id =>
        p.id
    }.toSet
    val count = state.files.count {
      case ((orgId, _), f) =>
        orgId == org.id && pkgIds.contains(f.packageId) &&
          f.objectType == com.pennsieve.models.FileObjectType.Source
    }
    EitherT.rightT(count.toLong)
  }

  // ---- ignore files / status log -------------------------------------
  override def getIgnoreFiles(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[com.pennsieve.models.DatasetIgnoreFile]] =
    EitherT.rightT(
      state.datasetIgnoreFiles.getOrElse((org.id, dataset.id), Seq.empty)
    )

  override def setIgnoreFiles(
    dataset: Dataset,
    ignoreFiles: Seq[com.pennsieve.models.DatasetIgnoreFile]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[com.pennsieve.models.DatasetIgnoreFile]] = {
    state.datasetIgnoreFiles.put((org.id, dataset.id), ignoreFiles)
    EitherT.rightT(ignoreFiles)
  }

  // ---- webhooks (no-op until tests demand) -----------------------------
  override def enableDefaultWebhooks(
    dataset: Dataset,
    includedWebhookIds: Option[Set[Int]] = None,
    excludedWebhookIds: Option[Set[Int]] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Option[Int]] = {
    val orgWebhooks = state.webhooks.collect {
      case ((orgId, _), w) if orgId == org.id => w
    }.toSeq
    val excluded = excludedWebhookIds.getOrElse(Set.empty)
    val included = includedWebhookIds.getOrElse(Set.empty)
    val toEnable = orgWebhooks.filter { w =>
      !excluded.contains(w.id) &&
      (w.isDefault ||
      (included.contains(w.id) &&
      (!w.isPrivate || actor.isSuperAdmin || w.createdBy == actor.id)))
    }
    toEnable.foreach { w =>
      val id = state.newId()
      state.datasetIntegrations.put(
        (org.id, id),
        com.pennsieve.models.DatasetIntegration(
          webhookId = w.id,
          datasetId = dataset.id,
          enabledBy = actor.id,
          id = id
        )
      )
      // Mirror the real manager: link integration user to dataset
      state.datasetUserRoles
        .put((org.id, w.integrationUserId, dataset.id), Role.Manager)
    }
    EitherT.rightT(Some(toEnable.size))
  }

  override def getIntegrations(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[com.pennsieve.models.DatasetIntegration]] = {
    val rows = state.datasetIntegrations.values
      .filter(d => d.datasetId == dataset.id)
      .toSeq
    EitherT.rightT(rows)
  }

  override def enableWebhook(
    dataset: Dataset,
    webhook: com.pennsieve.models.Webhook,
    integrationUser: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, com.pennsieve.models.DatasetIntegration] = {
    val existing = state.datasetIntegrations.values.find { d =>
      d.datasetId == dataset.id && d.webhookId == webhook.id
    }
    val integration = existing.getOrElse {
      val id = state.newId()
      val di = com.pennsieve.models.DatasetIntegration(
        webhookId = webhook.id,
        datasetId = dataset.id,
        enabledBy = actor.id,
        id = id
      )
      state.datasetIntegrations.put((org.id, id), di)
      di
    }
    val role = if (webhook.hasAccess) Role.Manager else Role.Viewer
    state.datasetUserRoles
      .put((org.id, integrationUser.id, dataset.id), role)
    EitherT.rightT(integration)
  }

  override def disableWebhook(
    dataset: Dataset,
    webhook: com.pennsieve.models.Webhook,
    integrationUser: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] = {
    val matching = state.datasetIntegrations.collect {
      case (k @ (orgId, _), d)
          if orgId == org.id && d.datasetId == dataset.id &&
            d.webhookId == webhook.id =>
        k
    }
    matching.foreach(state.datasetIntegrations.remove)
    state.datasetUserRoles.remove((org.id, integrationUser.id, dataset.id))
    EitherT.rightT(matching.size)
  }

  override def touchUpdatedAtTimestamp(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] = touchUpdatedAtTimestamp(dataset.id)

  override def touchUpdatedAtTimestamp(
    datasetId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] =
    state.datasets.get((org.id, datasetId)) match {
      case Some(d) =>
        state.datasets.put(
          (org.id, datasetId),
          d.copy(updatedAt = java.time.ZonedDateTime.now())
        )
        EitherT.rightT(())
      case None => EitherT.leftT(NotFound(s"dataset $datasetId not found"))
    }

  // ---- ownership / locking --------------------------------------------
  override def isLocked(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Boolean] = {
    // Latest publication status across this dataset; locked iff the latest
    // status is non-terminal (Requested, Accepted, Failed). Terminal states
    // (Cancelled, Rejected, Completed) → unlocked. No status at all → unlocked.
    val latest = state.datasetPublicationStatuses
      .collect {
        case ((orgId, _), ps)
            if orgId == org.id && ps.datasetId == dataset.id =>
          ps
      }
      .toSeq
      .sortBy(_.id)
      .lastOption
    val locked = latest.exists { ps =>
      ps.publicationStatus != PublicationStatus.Cancelled &&
      ps.publicationStatus != PublicationStatus.Rejected &&
      ps.publicationStatus != PublicationStatus.Completed
    }
    EitherT.rightT(locked)
  }

  // ---- collections -----------------------------------------------------
  override def getCollections(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[Collection]] = {
    val cids = state.datasetCollections.collect {
      case ((orgId, dsId, cid), _) if orgId == org.id && dsId == dataset.id =>
        cid
    }.toSeq
    EitherT.rightT(cids.flatMap(cid => state.collections.get((org.id, cid))))
  }

  override def removeCollection(
    dataset: Dataset,
    collectionId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Collection] =
    state.collections.get((org.id, collectionId)) match {
      case None => EitherT.leftT(NotFound(collectionId.toString))
      case Some(c) =>
        state.datasetCollections.remove((org.id, dataset.id, collectionId))
        // GC the collection if no other dataset references it
        val stillUsed =
          state.datasetCollections.keys.exists(_._3 == collectionId)
        if (!stillUsed) state.collections.remove((org.id, collectionId))
        EitherT.rightT(c)
    }

  override def removeCollections(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] = {
    val cids = state.datasetCollections.collect {
      case ((orgId, dsId, cid), _) if orgId == org.id && dsId == dataset.id =>
        cid
    }.toList
    cids.foreach(
      cid => state.datasetCollections.remove((org.id, dataset.id, cid))
    )
    EitherT.rightT(())
  }

  override def addCollection(
    dataset: Dataset,
    collectionId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Collection] = {
    state.collections.get((org.id, collectionId)) match {
      case None => EitherT.leftT(NotFound(s"Collection ($collectionId)"))
      case Some(c) =>
        state.datasetCollections.put((org.id, dataset.id, collectionId), ())
        EitherT.rightT(c)
    }
  }
}
