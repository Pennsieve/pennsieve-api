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

package com.pennsieve.api

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.{ Authorization, OAuth2BearerToken }
import cats.data.{ EitherT, _ }
import cats.implicits._
import com.pennsieve.audit.middleware.{ Auditor, TraceId }
import com.pennsieve.auth.middleware.{ DatasetPermission, Jwt }
import com.pennsieve.clients.ModelServiceClient
import com.pennsieve.concepts._
import com.pennsieve.concepts.types._
import com.pennsieve.core.utilities
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.core.utilities.{
  checkOrErrorT,
  getFileType,
  splitFileName,
  JwtAuthenticator
}
import com.pennsieve.domain.{
  CoreError,
  IntegrityError,
  InvalidAction,
  InvalidId,
  OperationNoLongerSupported,
  PackagePreviewExpected,
  ServiceError
}
import com.pennsieve.dtos.Builders._
import com.pennsieve.dtos._
import com.pennsieve.helpers.APIContainers.{
  InsecureAPIContainer,
  SecureAPIContainer,
  SecureContainerBuilderType
}
import com.pennsieve.helpers.ObjectStore
import com.pennsieve.helpers.ResultHandlers._
import com.pennsieve.helpers.either.EitherTErrorHandler.implicits._
import com.pennsieve.jobscheduling.clients.generated.jobs.JobsClient
import com.pennsieve.managers.FileManager
import com.pennsieve.managers.FileManager.UploadSourceFile
//import com.pennsieve.models.Utilities.escapeName
import com.pennsieve.models.{
  ChangelogEventDetail,
  CollectionUpload,
  Dataset,
  ExternalId,
  FileChecksum,
  FileState,
  FileTypeInfo,
  JobId,
  Manifest,
  ModelProperty,
  Organization,
  Package,
  PackageState,
  PackageType,
  PayloadType,
  Role,
  Upload,
  User
}
import com.pennsieve.uploads._
import com.pennsieve.core.utilities.cleanS3Key
import com.pennsieve.web.Settings
import org.scalatra._
import org.scalatra.swagger.Swagger
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder

import java.util.UUID
import javax.servlet.http.HttpServletRequest
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

case class PreviewPackageRequest(files: List[S3File])

case class PreviewPackageResponse(packages: List[PackagePreview])

case class CompleteProxyLinkRequest(
  conceptId: String,
  instanceId: String,
  targets: List[ProxyTargetLinkRequest]
)

case class UploadCompleteResponse(
  manifest: Manifest,
  `package`: Option[PackageDTO]
)

case class UploadCompleteRequest(
  preview: PackagePreview,
  proxyLink: Option[CompleteProxyLinkRequest]
)

object FilesController {
  def manifestUploadKey(email: String, importId: JobId): String = {
    s"job-manifests/$email/$importId/manifest.json"
  }

  def storageDirectory(user: User, importId: String) =
    s"${user.email}/data/$importId/"

  def uploadsDirectory(
    user: User,
    importId: String,
    usingUploadService: Boolean
  ) =
    if (usingUploadService) s"${user.id}/$importId/"
    else s"${user.email}/$importId/"

  /**
    * Determines S3 location for uploaded files
    *
    * @param file: actual file name/folder name on platform
    * @param user
    * @param importId
    * @param hasPreview
    * @param usingUploadService
    * @return
    */
  def generateS3Key(
    file: String,
    user: User,
    importId: String,
    hasPreview: Boolean,
    usingUploadService: Boolean
  ): String = {
    if (hasPreview) {
      s"${FilesController
        .uploadsDirectory(user, importId, usingUploadService)}${cleanS3Key(file)}"
    } else {
      file
    }
  }

  def generateS3Keys(
    files: List[String],
    user: User,
    importId: String,
    hasPreview: Boolean,
    usingUploadService: Boolean
  ): List[String] =
    files.map(
      file =>
        generateS3Key(file, user, importId, hasPreview, usingUploadService)
    )
}

case class ProxyLinkPayload(
  token: Jwt.Token,
  concept: ConceptDTO,
  instance: ConceptInstanceDTO,
  targets: List[ProxyTargetLinkRequest]
)

class FilesController(
  val insecureContainer: InsecureAPIContainer,
  val secureContainerBuilder: SecureContainerBuilderType,
  system: ActorSystem,
  auditLogger: Auditor,
  objectStore: ObjectStore,
  modelServiceClient: ModelServiceClient,
  jobSchedulingServiceClient: JobsClient,
  asyncExecutor: ExecutionContext
)(implicit
  val swagger: Swagger
) extends ScalatraServlet
    with AuthenticatedController {

  override protected implicit def executor: ExecutionContext = asyncExecutor
  override val swaggerTag = "Files"

  private def generateManifest(
    dataset: Dataset,
    pkg: Package,
    user: User,
    organization: Organization,
    importId: JobId,
    files: List[String],
    packagePreview: PackagePreview,
    encryptionKey: String,
    jobType: PayloadType,
    hasPreview: Boolean,
    usingUploadService: Boolean
  )(implicit
    secureContainer: SecureAPIContainer
  ): EitherT[Future, CoreError, (PackageDTO, Manifest)] = {

    val s3Keys: List[String] = FilesController.generateS3Keys(
      files,
      user,
      importId.toString,
      hasPreview,
      usingUploadService
    )

    val s3URIs: List[String] =
      s3Keys.map(key => s"s3://${Settings.s3_upload_bucketName}/${key}")

    val payload =
      Upload(
        pkg.id,
        dataset.id,
        user.id,
        encryptionKey,
        s3URIs,
        packagePreview.groupSize
      )

    for {
      dto <- packageDTO(pkg, dataset)(asyncExecutor, secureContainer)
    } yield (dto, Manifest(jobType, importId, organization.id, payload))
  }

  private def createNewManifest(
    auditLogger: Auditor,
    traceId: TraceId,
    user: User,
    organization: Organization,
    dataset: Dataset,
    destination: Option[Package],
    packagePreview: PackagePreview,
    importId: UUID,
    encryptionKey: String,
    jobType: PayloadType,
    links: Option[ProxyLinkPayload],
    usingUploadService: Boolean,
    hasPreview: Boolean
  )(implicit
    secureContainer: SecureAPIContainer,
    request: HttpServletRequest
  ): EitherT[Future, CoreError, (Manifest, PackageDTO)] =
    for {
      files <- Either
        .fromOption[CoreError, NonEmptyList[String]](
          NonEmptyList.fromList(packagePreview.files.map(_.fileName)),
          ServiceError("missing list of uploaded files")
        )
        .toEitherT[Future]

      jobId = JobId(importId)

      pkg <- createPackage(
        user,
        dataset,
        destination,
        packagePreview,
        jobId,
        links,
        jobType
      )

      _ <- secureContainer.changelogManager
        .logEvent(dataset, ChangelogEventDetail.CreatePackage(pkg, destination))

      _ <- secureContainer.datasetManager
        .touchUpdatedAtTimestamp(dataset)

      dtoAndManifest <- generateManifest(
        dataset,
        pkg,
        user,
        organization,
        jobId,
        files.toList,
        packagePreview,
        encryptionKey,
        jobType,
        hasPreview,
        usingUploadService
      )

      (dto, manifest) = dtoAndManifest

      _ <- createPackageSourceFiles(
        secureContainer.fileManager,
        pkg,
        packagePreview,
        user,
        hasPreview,
        usingUploadService
      )

      token = JwtAuthenticator.generateServiceToken(
        1.minute,
        organization.id,
        Some(dataset.id)
      )

      tokenHeader = Authorization(OAuth2BearerToken(token.value))

      _ <- jobSchedulingServiceClient
        .create(
          organization.id,
          importId.toString,
          manifest.content,
          List(tokenHeader)
        )
        .leftMap[CoreError] {
          case Left(e) => ServiceError(e.getMessage)
          case Right(resp) => ServiceError(resp.toString)
        }

      _ <- auditLogger
        .message()
        .append("dataset-id", dataset.id)
        .append("dataset-node-id", dataset.nodeId)
        .append("package-id", pkg.id)
        .append("package-node-id", pkg.nodeId)
        .log(traceId)
        .toEitherT

    } yield (manifest, dto)

  private def getExistingManifest(
    auditLogger: Auditor,
    traceId: TraceId,
    user: User,
    organization: Organization,
    dataset: Dataset,
    packagePreview: PackagePreview,
    importId: UUID,
    encryptionKey: String,
    jobType: PayloadType,
    usingUploadService: Boolean,
    hasPreview: Boolean
  )(implicit
    secureContainer: SecureAPIContainer,
    request: HttpServletRequest
  ): EitherT[Future, CoreError, (Manifest, PackageDTO)] =
    for {
      pkg <- secureContainer.packageManager
        .getByImportId(dataset, importId)

      files <- Either
        .fromOption[CoreError, NonEmptyList[String]](
          NonEmptyList.fromList(packagePreview.files.map(_.fileName)),
          ServiceError("missing list of uploaded files")
        )
        .toEitherT[Future]

      dtoAndManifest <- generateManifest(
        dataset,
        pkg,
        user,
        organization,
        JobId(importId),
        files.toList,
        packagePreview,
        encryptionKey,
        jobType,
        hasPreview,
        usingUploadService
      )

      (dto, manifest) = dtoAndManifest

      _ <- auditLogger
        .message()
        .append("dataset-id", dataset.id)
        .append("dataset-node-id", dataset.nodeId)
        .append("package-id", pkg.id)
        .append("package-node-id", pkg.nodeId)
        .log(traceId)
        .toEitherT

    } yield (manifest: Manifest, dto)

  /**
    * Upon receiving a package preview, it is necessary to create the a representation of the upload files in the
    * platform before later processing downstream. To do this, once a package preview is received from the
    * upload-service, we create a source file per S3 file present in the preview.
    */
  private def createPackageSourceFiles(
    fm: FileManager,
    `package`: Package,
    preview: PackagePreview,
    user: User,
    hasPreview: Boolean,
    usingUploadService: Boolean
  ): EitherT[Future, CoreError, Option[Int]] = {

    // (name, s3-key, size, file-hash)
    val files: List[UploadSourceFile] =
      preview.files.map { s3File: S3File =>
        {
          val (_, extension) = splitFileName(s3File.fileName)
          val fileType = getFileType(extension)

          val checksum = for {
            h <- s3File.fileHash
            c <- s3File.chunkSize
          } yield FileChecksum(c, h.hash)

          UploadSourceFile(
            s3File.fileName,
            fileType,
            FilesController.generateS3Key(
              s3File.fileName,
              user,
              preview.importId,
              hasPreview,
              usingUploadService
            ),
            s3File.size.getOrElse(0L),
            checksum
          )
        }
      }
    fm.generateSourcesFiles(
      `package`.id,
      Settings.s3_upload_bucketName,
      files,
      FileState.SCANNING
    )
  }

  val uploadManifestOperation
    : OperationBuilder = (apiOperation[UploadCompleteResponse]("uploadManifest")
    summary "creates a manifest for an uploaded file group, and puts it in s3"
    parameters (
      pathParam[String]("importId")
        .description("the import ID of the uploaded files"),
      queryParam[String]("destinationId")
        .description("the ID of the package in which to place the files")
        .optional,
      queryParam[String]("datasetId")
        .description("the ID of the dataset")
        .optional,
      queryParam[Boolean]("append")
        .description("the ID of the package to append this data to")
        .defaultValue(false),
      queryParam[Boolean]("uploadService")
        .description("flag to force usage of new file path userId/importId")
        .defaultValue(false),
      queryParam[Boolean]("hasPreview")
        .description("flag to indicate that request contains PackagePreview")
        .defaultValue(false),
      bodyParam[CompleteProxyLinkRequest]("body").optional
        .description("describes the model to link this upload to")
  ))

  post("/upload/complete/:importId", operation(uploadManifestOperation)) {
    new AsyncResult {
      val manifest
        : EitherT[Future, ActionResult, List[UploadCompleteResponse]] = for {
        secureContainer <- getSecureContainer
        user = secureContainer.user
        organization = secureContainer.organization
        importId <- paramT[String]("importId")
        datasetId <- paramT[String]("datasetId")
        destinationNodeId <- optParamT[String]("destinationId")
        usingUploadService <- paramT[Boolean]("uploadService", default = true)
        hasPreview <- paramT[Boolean]("hasPreview", default = true)
        appendToPackage <- paramT[Boolean]("append", default = false)

        traceId <- getTraceId(request)

        proxyLinkRequestBody <- if (!request.body.isEmpty) {
          if (hasPreview) { // Request may contain a nested CompleteProxyLinkRequest
            extractOrErrorT[Option[CompleteProxyLinkRequest]](
              parsedBody \ "proxyLink"
            )
          } else { // Request may contain a CompleteProxyLinkRequest at the json body root
            extractOrErrorT[CompleteProxyLinkRequest](parsedBody)
              .map(t => Option(t))
          }
        } else { // Request may have no payload
          EitherT.liftF(Future.successful(None))
        }

        // If the hasPreview query param is set to true, then the request will contain a nested PackagePreview
        uploadServicePreview <- if (!request.body.isEmpty && hasPreview) {
          decodeOrErrorT[PackagePreview](parsedBody \ "preview")
            .map(t => Option(t))
        } else {
          EitherT
            .leftT[Future, Option[PackagePreview]](
              PackagePreviewExpected: CoreError
            )
            .coreErrorToActionResult()
        }

        _ = if (hasPreview)
          logger.info(
            s"FilesController ImportId: $importId | Package preview received: $uploadServicePreview"
          )

        destinationPackage <- destinationNodeId
          .traverse(
            packageId => secureContainer.packageManager.getByNodeId(packageId)
          )
          .coreErrorToActionResult

        datasetAndRole <- secureContainer.datasetManager
          .getByExternalIdWithMaxRole(ExternalId.nodeId(datasetId))
          .coreErrorToActionResult
        (dataset, role) = datasetAndRole

        _ <- secureContainer
          .authorizeDataset(Set(DatasetPermission.CreateDeleteFiles))(dataset)
          .coreErrorToActionResult

        // Because this endpoint gets the dataset id via a query parameter, not
        // the path, the JWT does not contain a dataset role. However,
        // `model-service` requires an explicit dataset role to verify requests,
        // so we need to add it to the token.
        //
        // The previous authorization check will not let execution get to this
        // line if the user does not have permissions on the dataset, so
        // it is safe to unpack the `Option[Role]` here.
        token = JwtAuthenticator.generateUserToken(
          1.minute,
          secureContainer.user,
          organization = organization,
          organizationRole = Role.Editor,
          dataset = dataset,
          datasetRole = role.get
        )

        links <- proxyLinkRequestBody.traverse { req =>
          for {
            // verify the concept exists:
            concept <- modelServiceClient
              .concept(token, datasetId, req.conceptId)
              .toEitherT[Future]
              .coreErrorToActionResult

            conceptInstance <- modelServiceClient
              .instance(token, datasetId, req.conceptId, req.instanceId)
              .toEitherT[Future]
              .coreErrorToActionResult

          } yield ProxyLinkPayload(token, concept, conceptInstance, req.targets)
        }

        trueDestination <- uploadServicePreview.traverse {
          preview: PackagePreview =>
            for {
              collections <- createCollections(
                preview,
                destinationPackage,
                dataset,
                user
              )(secureContainer).coreErrorToActionResult()
              _ = logger.info(
                s"ImportId: $importId | Package collections: ${collections
                  .map(c => s"${c.nodeId.toString}|${c.name}")
                  .mkString(", ")}"
              )
            } yield getTrueDestination(destinationPackage, collections, preview)
        }

        _ = if (hasPreview)
          logger.info(
            s"ImportId: $importId | Destination package: $trueDestination"
          )

        packagePreviews <- uploadServicePreview match {
          case Some(preview) =>
            EitherT.liftF(Future.successful(List(preview))) // preview was passed in by the upload-service
          case None => {
            EitherT
              .leftT[Future, List[PackagePreview]](
                PackagePreviewExpected: CoreError
              )
              .coreErrorToActionResult()
          }
        }

        // Note: This is a safe operation since List.tabulate(-1) returns an empty List.
        importIds = importId :: List.tabulate(packagePreviews.length - 1)(
          _ => UUID.randomUUID.toString
        )

        encryptionKey <- utilities
          .encryptionKey(organization)
          .toEitherT[Future]
          .coreErrorToActionResult

        manifests <- (importIds zip packagePreviews) traverse {
          case (importId: String, preview: PackagePreview) =>
            for {
              parsedImportId <- Try { UUID.fromString(importId) }.toEither
                .leftMap[CoreError](
                  _ => InvalidId("import id is not a valid UUID")
                )
                .toEitherT[Future]
                .coreErrorToActionResult

              jobType = if (appendToPackage) PayloadType.Append
              else PayloadType.Upload

              manifestAndPackage <- if (!appendToPackage)
                createNewManifest(
                  auditLogger,
                  traceId,
                  user,
                  organization,
                  dataset,
                  trueDestination.getOrElse(destinationPackage),
                  preview,
                  parsedImportId,
                  encryptionKey,
                  jobType,
                  links,
                  usingUploadService,
                  hasPreview
                )(secureContainer, request).recoverWith {
                  case e: IntegrityError => // unique constraint violation, package already exists
                    getExistingManifest(
                      auditLogger,
                      traceId,
                      user,
                      organization,
                      dataset,
                      preview,
                      parsedImportId,
                      encryptionKey,
                      jobType,
                      usingUploadService,
                      hasPreview
                    )(secureContainer, request)
                }.coreErrorToActionResult
              else
                createNewManifest(
                  auditLogger,
                  traceId,
                  user,
                  organization,
                  dataset,
                  trueDestination.getOrElse(destinationPackage),
                  preview,
                  parsedImportId,
                  encryptionKey,
                  jobType,
                  links,
                  usingUploadService,
                  hasPreview
                )(secureContainer, request).coreErrorToActionResult

            } yield manifestAndPackage
        }

      } yield
        manifests.map {
          case (m, p) => UploadCompleteResponse(m, Some(p))
        }

      override val is = manifest.value.map(OkResult)
    }
  }

  val getPackagePreviews
    : OperationBuilder = (apiOperation[PreviewPackageResponse](
    "getPackagePreviews"
  )
    summary "returns packages that will be created from a given list of files"
    parameters (
      queryParam[Boolean]("append")
        .description("if true, we append the items. if false, we replace"),
      bodyParam[PreviewPackageRequest]("body")
        .description("files to be uploaded")
  ))

  post("/upload/preview", operation(getPackagePreviews)) {

    new AsyncResult {
      val results: EitherT[Future, ActionResult, PreviewPackageResponse] =
        EitherT
          .leftT[Future, PreviewPackageResponse](
            OperationNoLongerSupported: CoreError
          )
          .coreErrorToActionResult

      override val is = results.value.map(OkResult)
    }
  }

  def tryToLinkConceptProxy(
    `package`: Package,
    dataSet: Dataset,
    links: ProxyLinkPayload
  ): Either[CoreError, Boolean] = {
    for {
      targets <- links.targets.traverse { t =>
        for {
          uuid <- Either
            .catchNonFatal(UUID.fromString(t.linkTarget))
            .leftMap(t => ServiceError(t.getMessage))
          linkTarget = ProxyLinkTarget.ConceptInstance(uuid)
          relationshipType = t.relationshipType
        } yield
          ProxyTarget(
            t.relationshipDirection,
            linkTarget,
            relationshipType,
            List()
          )
      }
      payload = CreateProxyInstancePayload(
        externalId = ExternalId.nodeId(`package`.nodeId),
        targets = targets
      )
      createLink <- modelServiceClient.link(
        links.token,
        dataSet.nodeId,
        "package",
        payload
      )
    } yield createLink
  }

  def createPackage(
    user: User,
    dataset: Dataset,
    destination: Option[Package],
    packagePreview: PackagePreview,
    importId: JobId,
    links: Option[ProxyLinkPayload],
    jobType: PayloadType
  )(implicit
    secureContainer: SecureAPIContainer
  ): EitherT[Future, CoreError, Package] = {
    jobType match {
      case PayloadType.Upload =>
        for {
          pkg <- secureContainer.packageManager
            .create(
              packagePreview.packageName,
              packagePreview.packageType,
              PackageState.UNAVAILABLE,
              dataset,
              Some(user.id),
              destination,
              Some(importId.value),
              attributes = ModelProperty.fromFileTypeInfo(
                FileTypeInfo.get(packagePreview.fileType)
              )
            )

          _ <- links
            .traverse { links =>
              tryToLinkConceptProxy(pkg, dataset, links)
            }
            .toEitherT[Future]

        } yield pkg

      case PayloadType.Append =>
        for {
          pkg <- Either
            .fromOption[CoreError, Package](
              destination,
              InvalidAction("missing destination package to append to")
            )
            .toEitherT[Future]

          _ <- checkOrErrorT[CoreError](pkg.`type` == PackageType.TimeSeries)(
            InvalidAction("append only supported for timeseries packages")
          )
        } yield pkg

      case payloadType =>
        EitherT.leftT[Future, Package](
          InvalidAction(s"Payload type '$payloadType' not supported"): CoreError
        )
    }
  }

  def createCollections(
    preview: PackagePreview,
    destination: Option[Package],
    dataset: Dataset,
    user: User
  )(implicit
    secureContainer: SecureAPIContainer
  ): EitherT[Future, CoreError, List[Package]] = {

    val collections = preview.parent match {
      case Some(p) =>
        preview.ancestors.getOrElse(List.empty[CollectionUpload]) ++ List(p)
      case None =>
        preview.ancestors.getOrElse(List.empty[CollectionUpload])
    }

    val sortedCollections: List[CollectionUpload] =
      collections.sortBy(_.depth)

    if (sortedCollections.isEmpty) { // create no collections
      EitherT.rightT[Future, CoreError](List.empty[Package])
    } else if (sortedCollections.length == 1) { // create a single collection
      List(
        secureContainer.packageManager
          .createSingleCollection(
            sortedCollections.head,
            dataset,
            Some(user.id)
          )
      ).sequence
    } else { // create multiple collections
      secureContainer.packageManager.createCollections(
        destination,
        dataset,
        sortedCollections,
        Some(user.id)
      )
    }
  }

  def getTrueDestination(
    givenDestination: Option[Package],
    newCollections: List[Package],
    preview: PackagePreview
  ): Option[Package] = {
    preview.parent match {
      case Some(parent) => {
        val result = newCollections.find(_.nodeId == parent.id)
        logger.info(s"New destination package: $result")
        result
      }
      case None => givenDestination
    }
  }

}
