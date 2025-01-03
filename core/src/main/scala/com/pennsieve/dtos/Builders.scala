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

package com.pennsieve.dtos

import java.net.URL
import java.util.UUID
import akka.http.scaladsl.model.headers.{ Authorization, OAuth2BearerToken }
import akka.actor.ActorSystem
import cats.data.EitherT
import cats.implicits._
import com.pennsieve.auth.middleware.Jwt
import com.pennsieve.clients.DatasetAssetClient
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.core.utilities._
import com.pennsieve.db.{ DatasetAndStatus, PackagesMapper }
import com.pennsieve.domain.StorageAggregation.{ sdatasets, spackages }
import com.pennsieve.domain._
import com.pennsieve.dtos.FileDTO.TypeToFileDTO
import com.pennsieve.dtos.SimpleFileDTO.TypeToSimpleFile
import com.pennsieve.managers._
import com.pennsieve.models.FileObjectType.{ Source, View, File => FileT }
import com.pennsieve.models.PackageType.TimeSeries
import com.pennsieve.models.{
  Collection,
  Contributor,
  DBPermission,
  DataCanvas,
  DataCanvasFolder,
  Dataset,
  DatasetAsset,
  DatasetContributor,
  DatasetPublicationStatus,
  DatasetRelease,
  DatasetStatus,
  DatasetStatusInUse,
  DatasetType,
  ExternalRepository,
  FeatureFlag,
  File,
  FileObjectType,
  OrcidAuthorization,
  Organization,
  Package,
  PackageType,
  PublicationStatus,
  Role,
  Subscription,
  User
}

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._

object Builders {

  private def getDatasetPresignedBannerUrls(
    datasetAssetsManager: DatasetAssetsManager,
    datasets: Seq[Dataset]
  )(implicit
    executionContext: ExecutionContext,
    datasetAssetClient: DatasetAssetClient
  ): EitherT[Future, CoreError, List[(Int, URL)]] = {
    val bannerIds: List[UUID] = datasets.map(_.bannerId).toList.flatten
    for {
      banners <- datasetAssetsManager.getBannersByIds(bannerIds)
      presignedDatasetBannerUrls <- banners
        .map(
          (asset: DatasetAsset) =>
            datasetAssetClient
              .generatePresignedUrl(asset, 12.hour)
              .map((url: URL) => asset.datasetId -> url)
        )
        .toList
        .sequence
        .leftMap(ThrowableError(_): CoreError)
        .toEitherT[Future]
    } yield presignedDatasetBannerUrls
  }

  private def getDatasetPresignedBannerUrl(
    datasetAssetsManager: DatasetAssetsManager,
    dataset: Dataset
  )(implicit
    executionContext: ExecutionContext,
    datasetAssetClient: DatasetAssetClient
  ): EitherT[Future, CoreError, Option[URL]] =
    getDatasetPresignedBannerUrls(datasetAssetsManager, Seq(dataset))
      .map(_.headOption.map(_._2))

  val emptyStorage: Map[Int, Option[Long]] = Map.empty

  /**
    * Efficiently build dataset DTOs for many datasets.
    *
    * All queries in this function must be batched to prevent N + 1 combinatoric
    * explosions when adding related data to the DTOs.
    */
  def datasetDTOs[
    DIContainer <: DatasetManagerContainer with PackageDTODIContainer with OrganizationManagerContainer with DatasetAssetsContainer
  ](
    datasetAndStatus: Seq[DatasetAndStatus],
    includeBannerUrl: Boolean = false,
    includePublishedDataset: Boolean = false
  )(implicit
    executionContext: ExecutionContext,
    secureContainer: DIContainer,
    storageClient: StorageServiceClientTrait,
    datasetAssetClient: DatasetAssetClient,
    getPublishedDatasetsFromDiscover: (Organization, User) => EitherT[
      Future,
      CoreError,
      Map[Int, DiscoverPublishedDatasetDTO]
    ],
    system: ActorSystem,
    jwtConfig: Jwt.Config
  ): EitherT[Future, CoreError, List[DataSetDTO]] = {

    val datasets = datasetAndStatus.map(_.dataset)

    for {
      collaboratorCountMap <- secureContainer.datasetManager
        .getCollaboratorCounts(datasets.toList)
        .leftMap(_ => Error("Could not get collaborator counts"))

      ownerMap <- secureContainer.datasetManager
        .getOwners(datasets.toList)
        .leftMap(_ => Error("Could not get dataset owners"))

      storageMap <- storageClient
        .getStorage(sdatasets, datasets.map(_.id).toList)
        .leftMap(_ => Error("Could not get storage"))

      datasetBanners <- if (includeBannerUrl) {
        getDatasetPresignedBannerUrls(
          secureContainer.datasetAssetsManager,
          datasets
        ).map(_.toMap)
      } else {
        EitherT.rightT[Future, CoreError](Map.empty[Int, URL])
      }

      publishedStatuses <- if (includePublishedDataset) {
        getPublishedDatasetsFromDiscover(
          secureContainer.organization,
          secureContainer.user
        )
      } else {
        EitherT.rightT[Future, CoreError](
          Map[Int, DiscoverPublishedDatasetDTO]()
        )
      }

      dtos <- datasetAndStatus.toList
        .traverse {
          case datasetAndStatus: DatasetAndStatus =>
            val dataset = datasetAndStatus.dataset

            for {
              collaboratorCounts <- collaboratorCountMap
                .get(dataset)
                .toRight[CoreError](
                  Error(
                    s"Missing collaborator counts for dataset ${dataset.nodeId}"
                  )
                )
                .toEitherT[Future]

              owner <- ownerMap
                .get(dataset)
                .toRight[CoreError](
                  Error(s"Missing owner for dataset ${dataset.nodeId}")
                )
                .toEitherT[Future]

              organizationId = secureContainer.organization.id

              user = secureContainer.user

              role <- secureContainer.datasetManager
                .maxRole(dataset, user)

              counts <- secureContainer.packageManager
                .packageTypes(dataset)
                .map(_.map { case (k, v) => (k.toString, v.toLong) })

              repository <- if (dataset.`type`.equals(DatasetType.Release)) {
                secureContainer.datasetManager
                  .getExternalRepository(organizationId, dataset.id)
              } else {
                Future[Option[ExternalRepository]](None).toEitherT
              }

              datasetReleases <- if (dataset.`type`.equals(DatasetType.Release)) {
                secureContainer.datasetManager.getReleases(dataset.id)
              } else {
                Future[Option[Seq[DatasetRelease]]](None).toEitherT
              }

            } yield
              DataSetDTO(
                content = WrappedDataset(
                  dataset,
                  datasetAndStatus.status,
                  repository,
                  datasetReleases
                ),
                organization = secureContainer.organization.nodeId,
                children = None,
                owner = owner.nodeId,
                collaboratorCounts = collaboratorCounts,
                storage = storageMap.get(dataset.id).flatten,
                status = DatasetStatusDTO(
                  datasetAndStatus.status,
                  DatasetStatusInUse(true)
                ),
                publication = DatasetPublicationDTO(
                  publishedStatuses.get(dataset.id),
                  datasetAndStatus.publicationStatus
                ),
                canPublish = datasetAndStatus.canPublish,
                locked = datasetAndStatus.locked,
                bannerPresignedUrl = datasetBanners.get(dataset.id),
                packageTypeCounts = counts,
                role = role
              )
        }
    } yield dtos
  }

  def datasetDTO[
    DIContainer <: DatasetManagerContainer with PackageDTODIContainer with OrganizationManagerContainer with DatasetAssetsContainer
  ](
    dataset: Dataset,
    status: DatasetStatus,
    datasetPublicationStatus: Option[DatasetPublicationStatus],
    contributors: Seq[Contributor],
    includeChildren: Boolean = false,
    storage: Option[Long] = None,
    includeBannerUrl: Boolean = false,
    includePublishedDataset: Boolean = false,
    limit: Option[Int] = None,
    offset: Option[Int] = None
  )(implicit
    executionContext: ExecutionContext,
    secureContainer: DIContainer,
    datasetAssetClient: DatasetAssetClient,
    getPublishedDatasetsFromDiscover: (Organization, User) => EitherT[
      Future,
      CoreError,
      Map[Int, DiscoverPublishedDatasetDTO]
    ],
    system: ActorSystem,
    jwtConfig: Jwt.Config
  ): EitherT[Future, CoreError, DataSetDTO] = {
    for {

      children <- if (includeChildren) {
        childrenPackageDTOs(None, dataset, limit = limit, offset = offset)
          .map(Some.apply)
      } else {
        Future[Option[List[PackageDTO]]](None).toEitherT
      }

      organizationId = secureContainer.organization.id

      repository <- if (dataset.`type`.equals(DatasetType.Release)) {
        secureContainer.datasetManager
          .getExternalRepository(organizationId, dataset.id)
      } else {
        Future[Option[ExternalRepository]](None).toEitherT
      }

      datasetReleases <- if (dataset.`type`.equals(DatasetType.Release)) {
        secureContainer.datasetManager.getReleases(dataset.id)
      } else {
        Future[Option[Seq[DatasetRelease]]](None).toEitherT
      }

      collaboratorCounts <- secureContainer.datasetManager
        .getCollaboratorCounts(dataset)

      owner <- secureContainer.datasetManager.getOwner(dataset)

      user = secureContainer.user

      role <- secureContainer.datasetManager
        .maxRole(dataset, user)

      counts <- secureContainer.packageManager
        .packageTypes(dataset)
        .map(_.map { case (k, v) => (k.toString, v.toLong) })

      publishedStatuses <- if (includePublishedDataset) {
        getPublishedDatasetsFromDiscover(
          secureContainer.organization,
          secureContainer.user
        )
      } else {
        EitherT.rightT[Future, CoreError](
          Map[Int, DiscoverPublishedDatasetDTO]()
        )
      }
      bannerPresignedUrl <- if (includeBannerUrl) {
        getDatasetPresignedBannerUrl(
          secureContainer.datasetAssetsManager,
          dataset
        )
      } else {
        EitherT.rightT[Future, CoreError](None: Option[URL])
      }

      locked <- secureContainer.datasetManager.isLocked(dataset)

      canPublish = (
        !locked &&
          dataset.name.length > 0 &&
          dataset.description.exists(!_.isEmpty) &&
          dataset.license.isDefined &&
          dataset.readmeId.isDefined &&
          dataset.bannerId.isDefined &&
          contributors.nonEmpty &&
          owner.orcidAuthorization.isDefined
      )

    } yield
      DataSetDTO(
        WrappedDataset(dataset, status, repository, datasetReleases),
        secureContainer.organization.nodeId,
        children,
        owner.nodeId,
        collaboratorCounts,
        storage,
        DatasetStatusDTO(status, DatasetStatusInUse(true)),
        publication = DatasetPublicationDTO(
          publishedStatuses.get(dataset.id),
          datasetPublicationStatus
        ),
        canPublish = canPublish,
        locked = locked,
        bannerPresignedUrl = bannerPresignedUrl,
        packageTypeCounts = Some(counts),
        role = Some(role)
      )
  }

  def childrenPackageDTOs[DIContainer <: PackageDTODIContainer](
    parent: Option[Package],
    dataset: Dataset,
    limit: Option[Int] = None,
    offset: Option[Int] = None
  )(implicit
    executionContext: ExecutionContext,
    secureContainer: DIContainer
  ): EitherT[Future, CoreError, List[PackageDTO]] =
    for {
      childPackages <- secureContainer.packageManager.children(
        parent,
        dataset,
        offset,
        limit
      )
      childStorageMap <- {
        secureContainer.storageManager
          .getStorage(spackages, childPackages.map(_.id))
      }

      childSingleSourceMap <- secureContainer.fileManager
        .getSingleSourceMap(childPackages)
        .toEitherT

      childExternalFileMap <- secureContainer.externalFileManager
        .getMap(childPackages)
        .map(_.map { case (packageId, file) => packageId -> file.toDTO }.toMap)
        .toEitherT

      children = childPackages.map { childPackage =>
        PackageDTO.simple(
          childPackage,
          dataset,
          storage = childStorageMap.get(childPackage.id).flatten,
          externalFile = childExternalFileMap.get(childPackage.id),
          withExtension = childSingleSourceMap
            .get(childPackage.id)
            .flatten
            .flatMap(_.fileExtension)
        )
      }
    } yield children

  private def createEmptyFileMap[A] =
    Map[String, List[A]](
      View.entryName -> List(),
      FileT.entryName -> List(),
      Source.entryName -> List()
    )

  def buildSimpleFileMapIfNonEmpty(
    files: Seq[File],
    parent: Package
  ): Option[TypeToSimpleFile] =
    files
      .foldRight(Option.empty[TypeToSimpleFile]) {
        case (file, None) =>
          createEmptyFileMap[SimpleFileDTO]
            .updated(
              file.objectType.entryName,
              List(SimpleFileDTO(file, parent))
            )
            .some

        case (file, Some(typeToFiles)) =>
          val key = file.objectType.entryName
          typeToFiles
            .updated(
              key,
              SimpleFileDTO(file, parent) :: typeToFiles
                .getOrElse(key, List.empty)
            )
            .some
      }

  def buildFileDTOMap(files: Seq[File], parent: Package): TypeToFileDTO =
    files.foldRight(createEmptyFileMap[FileDTO]) { (f, m) =>
      val k = f.objectType.entryName
      m.updated(k, FileDTO(f, parent) :: m.getOrElse(k, List()))
    }

  type PackageDTODIContainer = PackageContainer
    with StorageContainer
    with TimeSeriesManagerContainer
    with FilesManagerContainer
    with ExternalFilesContainer

  def packageDTO[DIContainer <: PackageDTODIContainer](
    `package`: Package,
    dataset: Dataset,
    includeAncestors: Boolean = false,
    includeChildren: Boolean = true,
    include: Option[Set[FileObjectType]] = None, //"None" indicates that we should not return any of the objects
    storage: Option[Long] = None,
    limit: Option[Int] = None,
    offset: Option[Int] = None
  )(implicit
    executionContext: ExecutionContext,
    secureContainer: DIContainer
  ): EitherT[Future, CoreError, PackageDTO] = {
    val properties =
      ModelPropertiesDTO.fromModelProperties(`package`.attributes)

    for {
      parent <- secureContainer.packageManager.getParent(`package`)
      parentStorage <- {
        parent match {
          case Some(p) =>
            secureContainer.storageManager.getStorage(spackages, List(p.id))
          case None => Future.successful(emptyStorage).toEitherT
        }
      }
      singleSource <- {
        parent match {
          case Some(p) => secureContainer.fileManager.getSingleSource(p)
          case None => Future.successful(None).toEitherT
        }
      }

      parentDTO = {
        parent.map(
          p =>
            PackageDTO.simple(
              `package` = p,
              dataset = dataset,
              storage = parentStorage.get(p.id).flatten,
              withExtension = singleSource.flatMap(_.fileExtension)
            )
        )
      }

      childPackageDTOs <- {
        if (includeChildren)
          childrenPackageDTOs(
            Some(`package`),
            dataset,
            limit = limit,
            offset = offset
          )
        else Right(List.empty[PackageDTO]).toEitherT[Future]
      }

      ancestors <- {
        if (includeAncestors)
          secureContainer.packageManager.ancestors(`package`).map(Option.apply)
        else
          EitherT.fromEither[Future](
            Option.empty[List[Package]].asRight[CoreError]
          )
      }

      ancestorStorageMap <- ancestors.map { packages =>
        secureContainer.storageManager
          .getStorage(spackages, packages.map(_.id))
      } getOrElse Future.successful(emptyStorage).toEitherT

      ancestorsSingleSourceMap <- secureContainer.fileManager
        .getSingleSourceMap(ancestors.getOrElse(List.empty))
        .toEitherT

      ancestorDTOs = ancestors.map(
        a =>
          a.map(
            ancestor =>
              PackageDTO.simple(
                `package` = ancestor,
                dataset = dataset,
                storage = ancestorStorageMap.get(ancestor.id).flatten,
                withExtension = ancestorsSingleSourceMap
                  .get(ancestor.id)
                  .flatten
                  .flatMap(_.fileExtension)
              )
          )
      )

      //if objectTypes are not specified, it means we should NOT retrieve any objects
      objects <- include.traverse { objectTypes =>
        secureContainer.fileManager.getByType(`package`, objectTypes)
      }

      fileDTOMap = objects.map(obs => buildFileDTOMap(obs, `package`))

      channelDTOs <- `package`.`type` match {
        case TimeSeries =>
          secureContainer.timeSeriesManager
            .getChannels(`package`)
            .map(channels => Some(ChannelDTO(channels, `package`)))
        case _ => Future.successful(None).toEitherT
      }

      externalFileDTO <- `package`.`type` match {
        case PackageType.ExternalFile =>
          secureContainer.externalFileManager
            .get(`package`)
            .map(f => Some(f.toDTO))
        case _ => Future.successful(None).toEitherT
      }

      singleSource <- secureContainer.fileManager.getSingleSource(`package`)

    } yield
      PackageDTO(
        content = WrappedPackage(`package`, dataset),
        properties = properties,
        parent = parentDTO,
        objects = fileDTOMap,
        children = childPackageDTOs,
        ancestors = ancestorDTOs,
        channels = channelDTOs,
        externalFile = externalFileDTO,
        storage = storage,
        extension = singleSource.flatMap(_.fileExtension)
      )
  }

  /**
    * Note: used by notifications-service
    *
    * @param `package`
    * @param dataset
    * @param organization
    * @param executionContext
    * @param container
    * @tparam DIContainer
    * @return
    */
  def insecure_basicPackageDTO[DIContainer <: DatabaseContainer](
    `package`: Package,
    dataset: Dataset,
    organization: Organization
  )(implicit
    executionContext: ExecutionContext,
    container: DIContainer
  ): EitherT[Future, CoreError, PackageDTO] = {
    val packagesMapper = new PackagesMapper(organization)
    val storageManager = StorageManager.create(container, organization)

    for {
      parent <- container.db.run(packagesMapper.getParent(`package`)).toEitherT
      parentStorage <- parent.map { p =>
        storageManager.getStorage(spackages, List(p.id))
      } getOrElse Future.successful(emptyStorage).toEitherT

      parentDTO = parent.map(
        p => PackageDTO.simple(p, dataset, parentStorage.get(p.id).flatten)
      )

      storageMap <- storageManager.getStorage(spackages, List(`package`.id))
      storage = storageMap.get(`package`.id).flatten
    } yield
      PackageDTO
        .simple(`package`, dataset, storage)
        .copy(parent = parentDTO)
  }

  def collaboratorsDTO[DIContainer <: OrganizationManagerContainer](
    collaborators: Collaborators
  )(
    secureContainer: DIContainer
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, CollaboratorsDTO] =
    for {
      organizationsDTOs <- collaborators.organizations.toList
        .traverse { organization: Organization =>
          Builders.organizationDTO(organization, None)(
            secureContainer.organizationManager,
            ec
          )
        }
      teamDTOs = collaborators.teams.map(TeamDTO(_)).toList
      userDTOs = collaborators.users
        .map(
          user =>
            Builders
              .userDTO(
                user,
                organizationNodeId = None,
                storage = None,
                pennsieveTermsOfService = None,
                customTermsOfService = List.empty
              )
        )
        .toList
    } yield CollaboratorsDTO(userDTOs, organizationsDTOs, teamDTOs)

  def userDTO(
    user: User,
    storage: Option[Long],
    pennsieveTermsOfService: Option[PennsieveTermsOfServiceDTO],
    customTermsOfService: Seq[CustomTermsOfServiceDTO],
    role: Option[Role] = None
  )(implicit
    organizationManager: OrganizationManager,
    ec: ExecutionContext
  ): EitherT[Future, CoreError, UserDTO] = {

    // We can (and should) only fetch the preferred organization object for the requesting user.
    // Use this constructor when creating UserDTOs for any other user in a request.

    user.preferredOrganizationId
      .traverse(
        oid =>
          organizationManager
            .get(oid)
            .map(_.nodeId)
      )
      .leftMap[CoreError] { error =>
        Error(
          s"Exception while creating UserDTO.\n\nFailed to fetch preferred Organization with exception: ${error.getMessage}"
        )
      }
      .map(
        organizationNodeId =>
          userDTO(
            user,
            organizationNodeId,
            None,
            storage,
            pennsieveTermsOfService,
            customTermsOfService,
            role
          )
      )
  }

  def userDTO(
    user: User,
    storage: Option[Long]
  )(implicit
    organizationManager: OrganizationManager,
    pennsieveTermsOfServiceManager: PennsieveTermsOfServiceManager,
    customTermsOfServiceManager: CustomTermsOfServiceManager,
    ec: ExecutionContext
  ): EitherT[Future, CoreError, UserDTO] = {

    // We can (and should) only fetch the preferred organization object for the requesting user.
    // Use this constructor when creating UserDTOs for any other user in a request.

    for {
      maybePennsieveTermsOfService <- pennsieveTermsOfServiceManager.get(
        user.id
      )
      maybeOrganization <- user.preferredOrganizationId.traverse(
        oid =>
          organizationManager
            .get(oid)
            .leftMap[CoreError](error => {
              Error(
                s"Exception while creating UserDTO.\n\n"
                  + "Failed to fetch preferred Organization with exception: ${error.getMessage}"
              )
            })
      )
      maybeCustomTermsOfService <- maybeOrganization.traverse { organization =>
        customTermsOfServiceManager
          .get(user.id, organization.id)
      }
    } yield
      userDTO(
        user,
        maybeOrganization.map(_.nodeId),
        None,
        storage,
        maybePennsieveTermsOfService.map(_.toDTO),
        maybeCustomTermsOfService
          .flatMap(
            customTermsOfService =>
              maybeOrganization.map(
                organization =>
                  customTermsOfService.map(_.toDTO(organization.nodeId))
              )
          )
          .flatten
          .toSeq,
        role = None
      )

  }

  def orcidDTO(user: User): Option[OrcidDTO] =
    user.orcidAuthorization.map(orcidAuth => orcidDTO(orcidAuth))

  def orcidDTO(orcidAuth: OrcidAuthorization): OrcidDTO = OrcidDTO(
    name = orcidAuth.name,
    orcid = orcidAuth.orcid,
    scope = orcidAuth.scope.split(" ").toList
  )

  def userDTO(
    user: User,
    organizationNodeId: Option[String],
    permission: Option[DBPermission],
    storage: Option[Long],
    pennsieveTermsOfService: Option[PennsieveTermsOfServiceDTO],
    customTermsOfService: Seq[CustomTermsOfServiceDTO],
    role: Option[Role]
  ): UserDTO = {
    UserDTO(
      id = user.nodeId,
      email = user.email,
      firstName = user.firstName,
      middleInitial = user.middleInitial,
      lastName = user.lastName,
      degree = user.degree,
      credential = user.credential,
      color = user.color,
      url = user.url,
      authyId = user.authyId,
      isSuperAdmin = user.isSuperAdmin,
      isIntegrationUser = user.isIntegrationUser,
      preferredOrganization = organizationNodeId,
      isOwner = permission.map(p => p == DBPermission.Owner),
      orcid = orcidDTO(user),
      pennsieveTermsOfService = pennsieveTermsOfService,
      customTermsOfService = customTermsOfService,
      storage = storage,
      createdAt = user.createdAt,
      updatedAt = user.updatedAt,
      role = role,
      intId = user.id
    )
  }

  def userDTO(
    user: User,
    organizationNodeId: Option[String],
    storage: Option[Long],
    pennsieveTermsOfService: Option[PennsieveTermsOfServiceDTO],
    customTermsOfService: Seq[CustomTermsOfServiceDTO]
  ): UserDTO = {
    UserDTO(
      id = user.nodeId,
      email = user.email,
      firstName = user.firstName,
      middleInitial = user.middleInitial,
      lastName = user.lastName,
      degree = user.degree,
      credential = user.credential,
      color = user.color,
      url = user.url,
      authyId = user.authyId,
      isSuperAdmin = user.isSuperAdmin,
      isIntegrationUser = user.isIntegrationUser,
      preferredOrganization = organizationNodeId,
      isOwner = None,
      orcid = orcidDTO(user),
      pennsieveTermsOfService = pennsieveTermsOfService,
      customTermsOfService = customTermsOfService,
      storage = storage,
      createdAt = user.createdAt,
      updatedAt = user.updatedAt,
      intId = user.id
    )
  }

  def organizationDTO(
    organization: Organization,
    subscription: Subscription,
    featureFlags: Seq[FeatureFlag],
    storage: Option[Long]
  ): OrganizationDTO = {
    OrganizationDTO(
      id = organization.nodeId,
      name = organization.name,
      slug = organization.slug,
      encryptionKeyId = organization.encryptionKeyId.getOrElse(""),
      terms = organization.terms,
      subscriptionState = SubscriptionDTO(subscription),
      features = featureFlags.filter(_.enabled).map(_.feature).toSet,
      storage = storage,
      colorTheme = organization.colorTheme,
      bannerImageURI = organization.bannerImageURI,
      customTermsOfService = organization.customTermsOfServiceVersion
        .map(
          updatedAt => CustomTermsOfServiceDTO(updatedAt, organization.nodeId)
        ),
      intId = organization.id
    )
  }

  def organizationDTO(
    organization: Organization,
    storage: Option[Long] = None
  )(implicit
    organizationManager: OrganizationManager,
    ec: ExecutionContext
  ): EitherT[Future, CoreError, OrganizationDTO] = {
    for {
      subscription <- organizationManager.getSubscription(organization.id)
      featureFlags <- organizationManager.getActiveFeatureFlags(organization.id)
    } yield organizationDTO(organization, subscription, featureFlags, storage)
  }

  def dataCanvasDTO[DIContainer <: DataCanvasManagerContainer](
    datacanvas: DataCanvas
  )(implicit
    executionContext: ExecutionContext,
    secureContainer: DIContainer,
    system: ActorSystem,
    jwtConfig: Jwt.Config
  ): EitherT[Future, CoreError, DataCanvasDTO] = {
    for {
      locked <- secureContainer.dataCanvasManager.isLocked(datacanvas)
    } yield
      DataCanvasDTO(
        id = datacanvas.id,
        name = datacanvas.name,
        description = datacanvas.description,
        createdAt = datacanvas.createdAt,
        updatedAt = datacanvas.updatedAt,
        nodeId = datacanvas.nodeId,
        permissionBit = datacanvas.permissionBit,
        role = datacanvas.role,
        statusId = datacanvas.statusId,
        isPublic = datacanvas.isPublic
      )
  }

  def datacanvasDTOs[DIContainer <: DataCanvasManagerContainer](
    datacanvases: Seq[DataCanvas]
  )(implicit
    executionContext: ExecutionContext,
    secureContainer: DIContainer,
    system: ActorSystem,
    jwtConfig: Jwt.Config
  ): EitherT[Future, CoreError, List[DataCanvasDTO]] = {
    for {
      dtos <- datacanvases.toList
        .traverse {
          case datacanvas: DataCanvas =>
            for {
              _ <- secureContainer.dataCanvasManager.isLocked(datacanvas)
            } yield
              DataCanvasDTO(
                id = datacanvas.id,
                name = datacanvas.name,
                description = datacanvas.description,
                createdAt = datacanvas.createdAt,
                updatedAt = datacanvas.updatedAt,
                nodeId = datacanvas.nodeId,
                permissionBit = datacanvas.permissionBit,
                role = datacanvas.role,
                statusId = datacanvas.statusId,
                isPublic = datacanvas.isPublic
              )
        }
    } yield dtos
  }

  def dataCanvasFolderDTO[DIContainer <: DataCanvasManagerContainer](
    folder: DataCanvasFolder
  )(implicit
    executionContext: ExecutionContext,
    secureContainer: DIContainer,
    system: ActorSystem,
    jwtConfig: Jwt.Config
  ): EitherT[Future, CoreError, DataCanvasFolderDTO] = {
    for {
      locked <- secureContainer.dataCanvasManager.isFolderLocked(folder)
    } yield
      DataCanvasFolderDTO(
        id = folder.id,
        parentId = folder.parentId,
        dataCanvasId = folder.dataCanvasId,
        name = folder.name,
        nodeId = folder.nodeId,
        createdAt = folder.createdAt,
        updatedAt = folder.updatedAt
      )
  }
}
