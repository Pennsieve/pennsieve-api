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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import akka.Done
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.headers.{ Authorization, OAuth2BearerToken }
import akka.actor.ActorSystem
import cats.data.EitherT
import cats.implicits._
import com.pennsieve.auth.middleware.{ DatasetPermission, Jwt }
import com.pennsieve.aws.email.{ Email, SesMessageResult }
import com.pennsieve.clients.{ DatasetAssetClient, ModelServiceClient }
import com.pennsieve.core.utilities.{
  checkOrError,
  checkOrErrorT,
  JwtAuthenticator
}
import com.pennsieve.discover.client.definitions.{
  BucketConfig,
  DatasetPublishStatus,
  DatasetsPage,
  InternalCollection,
  InternalContributor,
  InternalExternalPublication,
  ModelCount,
  PublicDatasetDto,
  PublishRequest,
  ReleaseRequest,
  ReviseRequest,
  UnpublishRequest
}
import com.pennsieve.discover.client.publish.PublishClient
import com.pennsieve.discover.client.search.SearchClient
import com.pennsieve.domain
import com.pennsieve.domain.{ Error => CoreServerError, _ }
import com.pennsieve.dtos.{
  CollectionDTO,
  ContributorDTO,
  DataSetDTO,
  DiscoverPublishedDatasetDTO
}
import com.pennsieve.helpers.APIContainers.{
  InsecureAPIContainer,
  SecureAPIContainer
}
import com.pennsieve.helpers.either.EitherTErrorHandler.implicits.EitherTCoreErrorHandler
import com.pennsieve.managers.{ DatasetAssetsManager, DatasetManager }
import com.pennsieve.models.PublicationStatus.Requested
import com.pennsieve.models.PublicationType.Revision
import com.pennsieve.models._
import com.pennsieve.notifications.{
  DiscoverPublishNotification,
  MessageType,
  NotificationMessage
}
import com.pennsieve.web.Settings
import com.typesafe.scalalogging.LazyLogging
import io.scalaland.chimney.dsl._
import org.apache.commons.io.IOUtils

import javax.servlet.http.HttpServletRequest
import org.scalatra.{ ActionResult, InternalServerError }

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

object PublishingWorkflows {
  val Version4: Long = 4
  val Version5: Long = 5
}

case class ValidatedPublicationStatusRequest(
  publicationStatus: PublicationStatus,
  publicationType: PublicationType,
  dataset: Dataset,
  owner: User,
  embargoReleaseDate: Option[LocalDate] = None
)

case object DataSetPublishingHelper extends LazyLogging {

  private def today(): String = {
    val format = new java.text.SimpleDateFormat("MM/dd/yyyy")
    format.format(new java.util.Date())
  }

  private def resolveBucketConfig(
    organization: Organization
  ): Option[BucketConfig] =
    (organization.publishBucket, organization.embargoBucket) match {
      case (Some(publish), Some(embargo)) =>
        Some(BucketConfig(publish, embargo))
      case (None, None) => None
      case _ =>
        throw new IllegalStateException(
          s"organizations must supply both publish and embargo buckets or neither: ${organization.name}."
        )
    }

  def uniqueEmails(owner: User, otherEmails: Seq[String]): List[String] = {
    (otherEmails :+ owner.email).toSet.toList.filter(!_.trim.isEmpty)
  }

  def emailContributorsDatasetPublished(
    insecureContainer: InsecureAPIContainer,
    contributors: Seq[ContributorDTO],
    owner: User,
    dataset: Dataset,
    discoverDatasetId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, ActionResult, List[SesMessageResult]] = {

    uniqueEmails(owner, contributors.map(_.email))
      .traverse { contributorEmail =>
        {
          val message = insecureContainer.messageTemplates
            .datasetContributorPublicationNotification(
              owner,
              contributorEmail,
              dataset,
              discoverDatasetId
            )

          logger.info(
            s"Sent email to ${contributorEmail} after succesfully publishing dataset ${dataset.nodeId} to discover"
          )

          insecureContainer.emailer
            .sendEmail(
              to = Email(contributorEmail),
              from = Settings.support_email,
              message = message,
              subject = s"Dataset Published to Pennsieve Discover"
            )
            .leftMap(error => InternalServerError(error.getMessage))
            .toEitherT[Future]

        }
      }
  }

  def emailContributorsDatasetAccepted(
    insecureContainer: InsecureAPIContainer,
    contributors: Seq[ContributorDTO],
    dataset: Dataset,
    version: Int,
    reviewer: User,
    owner: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, ActionResult, List[SesMessageResult]] = {
    uniqueEmails(owner, contributors.map(_.email))
      .traverse { contributorEmail =>
        {
          val message = insecureContainer.messageTemplates
            .datasetAcceptedForPublication(
              dataset,
              reviewer,
              today(),
              contributorEmail
            )

          val subject = "Dataset Accepted"

          logger.info(
            s"Sent email to ${contributorEmail} after the dataset ${dataset.nodeId} was accepted for publication to discover"
          )

          insecureContainer.emailer
            .sendEmail(
              to = Email(contributorEmail),
              from = Settings.support_email,
              message = message,
              subject = subject
            )
            .leftMap(error => InternalServerError(error.getMessage))
            .toEitherT[Future]

        }
      }
  }

  def emailContributorsDatasetEmbargoed(
    insecureContainer: InsecureAPIContainer,
    contributors: Seq[ContributorDTO],
    dataset: Dataset,
    owner: User,
    embargoDate: LocalDate
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, ActionResult, List[SesMessageResult]] = {
    uniqueEmails(owner, contributors.map(_.email))
      .traverse { contributorEmail =>
        {
          val message = insecureContainer.messageTemplates
            .datasetEmbargoed(
              dataset = dataset,
              date = today(),
              embargoDate =
                embargoDate.format(DateTimeFormatter.ofPattern("MM/dd/yyyy")),
              emailAddress = contributorEmail
            )

          val subject = "Dataset Under Embargo"

          logger.info(
            s"Sent email to ${contributorEmail} after the dataset ${dataset.nodeId} was embargoed for publication to discover"
          )

          insecureContainer.emailer
            .sendEmail(
              to = Email(contributorEmail),
              from = Settings.support_email,
              message = message,
              subject = subject
            )
            .leftMap(error => InternalServerError(error.getMessage))
            .toEitherT[Future]

        }
      }
  }
  def emailContributorsEmbargoedDatasetReleasedAccepted(
    insecureContainer: InsecureAPIContainer,
    contributors: Seq[ContributorDTO],
    dataset: Dataset,
    reviewer: User,
    owner: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, ActionResult, List[SesMessageResult]] = {
    uniqueEmails(owner, contributors.map(_.email))
      .traverse { contributorEmail =>
        {
          val message = insecureContainer.messageTemplates
            .embargoedDatasetReleaseAccepted(
              dataset,
              today(),
              reviewer,
              contributorEmail
            )
          val subject = "Dataset Release Accepted"

          logger.info(
            s"Sent email to ${contributorEmail} after the request to release embargoed dataset ${dataset.nodeId} for publication to discover was accepted"
          )

          insecureContainer.emailer
            .sendEmail(
              to = Email(contributorEmail),
              from = Settings.support_email,
              message = message,
              subject = subject
            )
            .leftMap(error => InternalServerError(error.getMessage))
            .toEitherT[Future]
        }
      }
  }

  def emailContributorsEmbargoedDatasetReleased(
    insecureContainer: InsecureAPIContainer,
    contributors: Seq[ContributorDTO],
    dataset: Dataset,
    owner: User,
    discoverDatasetId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, ActionResult, List[SesMessageResult]] = {
    uniqueEmails(owner, contributors.map(_.email))
      .traverse { contributorEmail =>
        {
          val message = insecureContainer.messageTemplates
            .embargoedDatasetReleased(
              dataset,
              contributorEmail,
              discoverDatasetId
            )
          val subject = "Dataset Released"

          logger.info(
            s"Sent email to ${contributorEmail} after the embargoed dataset ${dataset.nodeId} was released for publication to discover"
          )

          insecureContainer.emailer
            .sendEmail(
              to = Email(contributorEmail),
              from = Settings.support_email,
              message = message,
              subject = subject
            )
            .leftMap(error => InternalServerError(error.getMessage))
            .toEitherT[Future]
        }
      }
  }

  def emailContributorsDatasetRejected(
    insecureContainer: InsecureAPIContainer,
    contributors: Seq[ContributorDTO],
    dataset: Dataset,
    reviewer: User,
    owner: User,
    org: Organization,
    message: Option[String]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, ActionResult, List[SesMessageResult]] = {
    uniqueEmails(owner, contributors.map(_.email))
      .traverse { contributorEmail =>
        {
          val certainMessage = message.getOrElse("No information provided")
          val content = insecureContainer.messageTemplates
            .datasetRevisionNeeded(
              dataset,
              reviewer,
              today(),
              contributorEmail,
              org,
              certainMessage
            )

          val subject =
            "Dataset Revision needed"

          logger.info(
            s"Sent email to ${contributorEmail} after the dataset ${dataset.nodeId} was rejected for publication to discover"
          )

          insecureContainer.emailer
            .sendEmail(
              to = Email(contributorEmail),
              from = Settings.support_email,
              message = content,
              subject = subject
            )
            .leftMap(error => InternalServerError(error.getMessage))
            .toEitherT[Future]

        }
      }
  }

  def emailContributorsRevisionAccepted(
    insecureContainer: InsecureAPIContainer,
    organization: Organization,
    contributors: Seq[ContributorDTO],
    dataset: Dataset,
    owner: User,
    discoverDatasetId: Option[Int]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, ActionResult, List[SesMessageResult]] = {
    uniqueEmails(owner, contributors.map(_.email))
      .traverse { contributorEmail =>
        {
          val message = insecureContainer.messageTemplates
            .datasetRevisionNotification(
              contributorEmail,
              discoverDatasetId,
              organization,
              dataset
            )

          val subject =
            "Dataset Revision"

          logger.info(
            s"Sent email to ${contributorEmail} after accepting a request for revision for the dataset ${dataset.nodeId}"
          )

          insecureContainer.emailer
            .sendEmail(
              to = Email(contributorEmail),
              from = Settings.support_email,
              message = message,
              subject = subject
            )
            .leftMap(error => InternalServerError(error.getMessage))
            .toEitherT[Future]

        }
      }
  }

  def emailContributorsPublicationRequest(
    insecureContainer: InsecureAPIContainer,
    contributors: Seq[ContributorDTO],
    dataset: Dataset,
    organization: Organization,
    owner: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, ActionResult, List[SesMessageResult]] = {
    uniqueEmails(owner, contributors.map(_.email))
      .traverse { contributorEmail =>
        {
          val message =
            insecureContainer.messageTemplates
              .datasetSubmittedForPublication(
                dataset,
                organization,
                owner,
                today(),
                contributorEmail
              )

          val subject =
            "Dataset Submitted for Review"

          logger.info(
            s"Sent email to ${contributorEmail} after receiving a request for publication for the dataset ${dataset.nodeId}"
          )

          insecureContainer.emailer
            .sendEmail(
              to = Email(contributorEmail),
              from = Settings.support_email,
              message = message,
              subject = subject
            )
            .leftMap(error => InternalServerError(error.getMessage))
            .toEitherT[Future]

        }
      }
  }

  def emailPublishersPublicationRequest(
    insecureContainer: InsecureAPIContainer,
    owner: User,
    dataset: Dataset,
    organization: Organization,
    publishers: Seq[User]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, ActionResult, Seq[SesMessageResult]] = {
    publishers
      .traverse { publisher =>
        {
          val message =
            insecureContainer.messageTemplates
              .datasetSubmittedForReview(
                organizationName = organization.name,
                organizationNodeId = organization.nodeId,
                datasetName = dataset.name,
                datasetNodeId = dataset.nodeId,
                ownerName = owner.fullName,
                ownerEmailAddress = owner.email,
                date = today(),
                emailAddress = publisher.email
              )

          val subject =
            "Dataset Submitted to Publishers for Review"

          logger.info(
            s"Sent email to Publisher ${publisher.email} after receiving a request for publication for the dataset ${dataset.nodeId}"
          )

          insecureContainer.emailer
            .sendEmail(
              to = Email(publisher.email),
              from = Settings.support_email,
              message = message,
              subject = subject
            )
            .leftMap(error => InternalServerError(error.getMessage))
            .toEitherT[Future]
        }
      }
  }

  def emailManagersEmbargoAccessRequested(
    insecureContainer: InsecureAPIContainer,
    requestingUser: User,
    owner: User,
    managers: List[User],
    organization: Organization,
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, ActionResult, List[SesMessageResult]] = {
    uniqueEmails(owner, managers.map(_.email))
      .traverse { email =>
        {
          val message = insecureContainer.messageTemplates
            .embargoAccessRequested(
              dataset = dataset,
              user = requestingUser,
              org = organization,
              date = today(),
              emailAddress = email
            )

          logger.info(
            s"Sent email to ${email} after receiving a request to preview dataset ${dataset.nodeId}"
          )

          insecureContainer.emailer
            .sendEmail(
              to = Email(email),
              from = Settings.support_email,
              message = message,
              subject = "Request to Access Data"
            )
            .leftMap(error => InternalServerError(error.getMessage))
            .toEitherT[Future]

        }
      }
  }

  def emailPreviewerEmbargoAccessAccepted(
    insecureContainer: InsecureAPIContainer,
    requestingUser: User,
    manager: User,
    organization: Organization,
    dataset: Dataset,
    discoverDatasetId: Option[Int]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, ActionResult, SesMessageResult] = {

    val message = insecureContainer.messageTemplates
      .embargoAccessApproved(
        dataset = dataset,
        discoverDatasetId = discoverDatasetId.getOrElse(0),
        manager = manager,
        org = organization,
        date = today(),
        emailAddress = requestingUser.email
      )

    logger.info(
      s"Sent email to ${requestingUser.email} after accepting request to preview dataset ${dataset.nodeId}"
    )

    insecureContainer.emailer
      .sendEmail(
        to = Email(requestingUser.email),
        from = Settings.support_email,
        message = message,
        subject = "Request Accepted"
      )
      .leftMap(error => InternalServerError(error.getMessage))
      .toEitherT[Future]
  }

  def emailPreviewerEmbargoAccessDenied(
    insecureContainer: InsecureAPIContainer,
    requestingUser: User,
    manager: User,
    organization: Organization,
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, ActionResult, SesMessageResult] = {
    val message = insecureContainer.messageTemplates
      .embargoAccessDenied(
        dataset = dataset,
        manager = manager,
        org = organization,
        date = today(),
        emailAddress = requestingUser.email
      )

    logger.info(
      s"Sent email to ${requestingUser.email} after denying a request to preview dataset ${dataset.nodeId}"
    )

    insecureContainer.emailer
      .sendEmail(
        to = Email(requestingUser.email),
        from = Settings.support_email,
        message = message,
        subject = "Request Denied"
      )
      .leftMap(error => InternalServerError(error.getMessage))
      .toEitherT[Future]
  }

  def addPublisherTeam(
    secureContainer: SecureAPIContainer,
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] = {
    for {
      publisherTeam <- secureContainer.organizationManager
        .getPublisherTeam(secureContainer.organization)
      _ <- secureContainer.datasetManager
        .addTeamCollaborator(dataset, publisherTeam._1, Role.Manager)
    } yield ()
  }

  def removePublisherTeam(
    secureContainer: SecureAPIContainer,
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] = {
    for {
      publisherTeam <- secureContainer.organizationManager
        .getPublisherTeam(secureContainer.organization)
      _ <- secureContainer.datasetManager
        .deleteTeamCollaborator(dataset, publisherTeam._1)
    } yield ()
  }

  /**
    *  Validate the following:
    *
    *  1. the request is well formed,
    *  2. the user has access to perform the specified publication action,
    *  3. the type of publication requested matches the current publication workflow
    *  4. the requested publication status is valid given the current publication status
    */
  def validatePublicationStatusRequest(
    secureContainer: SecureAPIContainer,
    datasetId: String,
    requestedStatus: PublicationStatus,
    requestedType: PublicationType,
    datasetAssetClient: DatasetAssetClient
  )(implicit
    request: HttpServletRequest,
    ec: ExecutionContext,
    system: ActorSystem,
    jwtConfig: Jwt.Config
  ): EitherT[Future, CoreError, ValidatedPublicationStatusRequest] = {

    def mustBePublisher(requestedStatus: PublicationStatus) =
      PublicationStatus.publisherStatuses.contains(requestedStatus)

    def mustBeOwner(requestedStatus: PublicationStatus) =
      !mustBePublisher(requestedStatus)

    def mustBeManager(
      requestedStatus: PublicationStatus,
      requestedType: PublicationType
    ) =
      requestedStatus == Requested && requestedType == Revision

    for {
      dataset <- secureContainer.datasetManager
        .getByNodeId(datasetId)

      changelogAsset <- secureContainer.datasetAssetsManager
        .getChangelog(dataset)

      _ = changelogAsset match {
        case Some(_) =>
        case None =>
          secureContainer.datasetAssetsManager
            .createOrUpdateChangelog(
              dataset,
              datasetAssetClient.bucket,
              DatasetAssetsManager.defaultChangelogFileName,
              asset =>
                datasetAssetClient.uploadAsset(
                  asset,
                  DatasetAssetsManager.defaultChangelogText
                    .getBytes("utf-8")
                    .length,
                  Some("text/plain"),
                  IOUtils.toInputStream(
                    DatasetAssetsManager.defaultChangelogText,
                    "utf-8"
                  )
                )
            )
            .coreErrorToActionResult()
      }

      _ <- secureContainer
        .authorizeDataset(
          Set(
            if (mustBeManager(requestedStatus, requestedType))
              DatasetPermission.RequestRevise
            else if (mustBeOwner(requestedStatus))
              DatasetPermission.RequestCancelPublishRevise
            else DatasetPermission.ViewFiles
          )
        )(dataset)

      owner <- secureContainer.datasetManager
        .getOwner(dataset)

      _ <- if (mustBePublisher(requestedStatus)) {
        secureContainer.organizationManager
          .isPublisher(secureContainer.organization)
          .flatMap[CoreError, Unit] {
            case true => EitherT.rightT[Future, CoreError](())
            case false =>
              EitherT.leftT[Future, Unit](
                InvalidAction("Must be a publisher to perform this action")
              )
          }
      } else {
        EitherT.rightT[Future, CoreError](())
      }

      publicationLog <- secureContainer.datasetPublicationStatusManager
        .getLogByDataset(dataset.id)

      _ <- validatePublicationStateTransition(
        dataset,
        publicationLog,
        requestedStatus,
        requestedType
      ).toEitherT[Future]

    } yield
      ValidatedPublicationStatusRequest(
        requestedStatus,
        requestedType,
        dataset,
        owner,
        embargoReleaseDate =
          publicationLog.headOption.flatMap(_.embargoReleaseDate)
      )
  }

  /**
    * Check that the requested state transition for this dataset is valid.
    */
  def validatePublicationStateTransition(
    dataset: Dataset,
    publicationLog: Seq[DatasetPublicationStatus],
    requestedStatus: PublicationStatus,
    requestedType: PublicationType
  ): Either[CoreError, Unit] =
    for {
      _ <- checkOrError(
        publicationLog
          .sortBy(_.createdAt.toInstant)
          .reverse == publicationLog
      )(CoreServerError("Publication log not sorted in descending order"))

      // Current state of the dataset is the most recent entry in the publication log
      currentState = publicationLog.headOption

      // Find the last publication workflow that completed successfully
      lastCompletedPublicationType = publicationLog
        .find(_.publicationStatus in PublicationStatus.Completed)
        .map(_.publicationType)

      validTransitions <- PublicationStatus.validTransitions
        .get(currentState.map(_.publicationStatus))
        .toRight(
          CoreServerError(
            s"could not find $currentState in ${PublicationStatus.validTransitions}, the ADT needs to be updated"
          )
        )

      _ <- checkOrError(validTransitions contains requestedStatus)(
        PredicateError(
          s"$requestedStatus is not a valid transition from the current publication status of ${currentState
            .map(_.publicationStatus.entryName)
            .getOrElse(None.toString)}"
        )
      )

      _ <- checkOrError(currentState.forall(p => {
        if (PublicationStatus.lockedStatuses contains p.publicationStatus) {
          p.publicationType == requestedType
        } else true
      }))(
        PredicateError(
          s"$requestedType does not match the current workflow of ${currentState
            .map(_.publicationType.entryName)
            .getOrElse(None.toString)}"
        )
      )

      // The dataset must exist on Discover in any form in order to remove it
      _ <- checkOrError[CoreError](
        if (requestedType == PublicationType.Removal)
          lastCompletedPublicationType.exists(
            _ in Seq(
              PublicationType.Publication,
              PublicationType.Revision,
              PublicationType.Embargo,
              PublicationType.Release
            )
          )
        else true
      )(PredicateError(s"Only published datasets can be removed"))

      // The dataset must exist on Discover but not be under embargo to revise it
      _ <- checkOrError[CoreError](
        if (requestedType == PublicationType.Revision)
          lastCompletedPublicationType.exists(
            _ in Seq(
              PublicationType.Publication,
              PublicationType.Revision,
              PublicationType.Release
            )
          )
        else true
      )(PredicateError(s"Only published datasets can be revised"))

      // The dataset cannot be embargoed if it already exists on Discover as a proper publication
      _ <- checkOrError[CoreError](
        if (requestedType == PublicationType.Embargo)
          !lastCompletedPublicationType.exists(
            _ in Seq(
              PublicationType.Publication,
              PublicationType.Revision,
              PublicationType.Release
            )
          )
        else true
      )(PredicateError(s"May not embargo dataset that is currently published"))

      // The dataset cannot be released unless it is embargoed to Discover
      _ <- checkOrError[CoreError](
        if (requestedType == PublicationType.Release && requestedStatus == PublicationStatus.Requested) {
          val currentStateTuple =
            currentState.map(s => (s.publicationType, s.publicationStatus))
          List(
            Some((PublicationType.Embargo, PublicationStatus.Completed)),
            Some((PublicationType.Release, PublicationStatus.Rejected)),
            Some((PublicationType.Release, PublicationStatus.Cancelled))
          ).contains(currentStateTuple)
        } else true
      )(PredicateError(s"Only embargoed datasets can be released"))

    } yield ()

  case class PublicationInfo(
    description: String,
    license: License,
    ownerOrcid: String
  )

  def gatherPublicationInfo(
    validated: ValidatedPublicationStatusRequest,
    contributors: Seq[ContributorDTO],
    isPublisher: Boolean
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, PublicationInfo] = {

    for {
      description <- validated.dataset.description
        .filter(!_.isEmpty) match {
        case Some(desc) =>
          EitherT.rightT[Future, CoreError](desc)
        case None =>
          EitherT.leftT[Future, String](
            PredicateError("Dataset cannot be published without a subtitle")
          )
      }

      license <- validated.dataset.license match {
        case Some(l) => EitherT.rightT[Future, CoreError](l)
        case None =>
          EitherT.leftT[Future, License](
            PredicateError("Dataset cannot be published without a license")
          )
      }

      _ <- validated.dataset.bannerId match {
        case Some(_) => EitherT.rightT[Future, CoreError](())
        case None =>
          EitherT.leftT[Future, Unit](
            PredicateError("Dataset cannot be published without a banner image")
          )
      }

      _ <- validated.dataset.readmeId match {
        case Some(_) => EitherT.rightT[Future, CoreError](())
        case None =>
          EitherT.leftT[Future, Unit](
            PredicateError("Dataset cannot be published without a description")
          )
      }

      ownerOrcid <- validated.owner.orcidAuthorization match {
        case Some(orcidAuthorization) =>
          EitherT.rightT[Future, CoreError](orcidAuthorization.orcid)
        case None =>
          if (isPublisher)
            EitherT.rightT[Future, CoreError]("0000-0000-0000-0000")
          else
            EitherT.leftT[Future, String](
              PredicateError(
                "Dataset cannot be published if owner does not have an ORCID"
              )
            )

      }

      _ <- if (validated.dataset.tags.isEmpty)
        EitherT.leftT[Future, List[String]](
          PredicateError("Dataset cannot be published without tags")
        )
      else EitherT.rightT[Future, CoreError](validated.dataset.tags)

      _ <- if (contributors.isEmpty)
        EitherT
          .leftT[Future, List[ContributorDTO]](
            PredicateError("Dataset cannot be published without contributors")
          )
          .leftWiden[CoreError]
      else EitherT.rightT[Future, CoreError](contributors)
    } yield PublicationInfo(description, license, ownerOrcid)
  }

  /**
    * Build and send a request to Discover Service to publish a dataset.
    */
  def sendPublishRequest(
    secureContainer: SecureAPIContainer,
    dataset: Dataset,
    owner: User,
    ownerOrcid: String,
    ownerBearerToken: String,
    description: String,
    license: License,
    contributors: List[ContributorDTO],
    embargo: Boolean,
    modelServiceClient: ModelServiceClient,
    publishClient: PublishClient,
    sendNotification: NotificationMessage => EitherT[Future, CoreError, Done],
    embargoReleaseDate: Option[LocalDate] = None,
    collections: Seq[CollectionDTO],
    externalPublications: Seq[ExternalPublication],
    defaultPublishingWorkflow: String
  )(implicit
    ec: ExecutionContext,
    system: ActorSystem,
    jwtConfig: Jwt.Config
  ): EitherT[Future, CoreError, DatasetPublishStatus] = {
    val organization = secureContainer.organization

    for {
      totalSizes <- secureContainer.storageManager
        .getStorage(StorageAggregation.sdatasets, List(dataset.id))

      totalDatasetSize <- totalSizes
        .get(dataset.id)
        .toRight(
          ServiceError(s"Could not look up dataset size for ds: ${dataset.id}"): CoreError
        )
        .toEitherT[Future]

      fileCount <- secureContainer.datasetManager
        .sourceFileCount(dataset)

      modelCount <- modelServiceClient
        .getModelStats(ownerBearerToken, dataset.nodeId)
        .toEitherT[Future]

      newWorkflowEnabled <- secureContainer.organizationManager
        .hasFeatureFlagEnabled(organization.id, Feature.Publishing50Feature)

      workflowId = defaultPublishingWorkflow match {
        case "4" => Some(PublishingWorkflows.Version4)
        case "5" => Some(PublishingWorkflows.Version5)
        case "flag" =>
          newWorkflowEnabled match {
            case true => Some(PublishingWorkflows.Version5)
            case false => Some(PublishingWorkflows.Version4)
          }
        case _ => Some(PublishingWorkflows.Version5)
      }

      discoverRequest = PublishRequest(
        name = dataset.name,
        description = description,
        ownerId = owner.id,
        modelCount = modelCount.map {
          case (k, v) => ModelCount(k, v.toLong)
        }.toVector,
        recordCount = modelCount.values.sum.toLong,
        fileCount = fileCount,
        size = totalDatasetSize.getOrElse(0L),
        license = license,
        contributors =
          contributors.map(_.into[InternalContributor].transform).toVector,
        tags = dataset.tags.toVector,
        ownerNodeId = owner.nodeId,
        ownerFirstName = owner.firstName,
        ownerLastName = owner.lastName,
        ownerOrcid = ownerOrcid,
        organizationNodeId = organization.nodeId,
        organizationName = organization.name,
        bucketConfig = resolveBucketConfig(organization),
        datasetNodeId = dataset.nodeId,
        collections =
          Some(collections.map(_.into[InternalCollection].transform).toVector),
        externalPublications = Some(
          externalPublications
            .map(
              ep =>
                InternalExternalPublication(
                  ep.doi.value,
                  Some(ep.relationshipType)
                )
            )
            .toVector
        ),
        workflowId = workflowId
      )

      _ = logger.info(
        s"Sending request to discover to publish dataset ${dataset.nodeId} with request: ${discoverRequest}"
      )

      response <- publishClient
        .publish(
          organizationId = organization.id,
          datasetId = dataset.id,
          embargo = Some(embargo),
          body = discoverRequest,
          headers = getTokenHeaders(organization, dataset),
          embargoReleaseDate = embargoReleaseDate
        )
        .leftSemiflatMap(handleGuardrailError)
        .flatMap {
          _.fold[EitherT[Future, CoreError, DatasetPublishStatus]](
            handleBadRequest = msg => EitherT.leftT(PredicateError(msg)),
            handleCreated = response => EitherT.pure(response),
            handleInternalServerError = msg => EitherT.leftT(ServiceError(msg)),
            handleForbidden = msg =>
              EitherT.leftT(
                DatasetRolePermissionError(
                  secureContainer.user.nodeId,
                  dataset.id
                )
              ),
            handleUnauthorized =
              EitherT.leftT(UnauthorizedError("Publish unauthorized"))
          )
        }

      _ = logger.info(
        s"Started publish of dataset ${dataset.nodeId} ${response.publishedDatasetId match {
          case Some(id) => s"to public dataset id=$id"
          case None => ""
        }} version=${response.publishedVersionCount + 1} embargo=$embargo"
      )

      notification = DiscoverPublishNotification(
        List(secureContainer.user.id),
        MessageType.DatasetUpdate,
        "Dataset publish job submitted.",
        dataset.id,
        response.publishedDatasetId,
        response.publishedVersionCount,
        response.lastPublishedDate,
        response.status,
        success = true,
        None
      )

      _ <- sendNotification(notification)

    } yield response
  }

  /**
    * Build and send a request to Discover Service to revise a dataset
    */
  def sendReviseRequest(
    secureContainer: SecureAPIContainer,
    dataset: Dataset,
    owner: User,
    ownerOrcid: String,
    description: String,
    license: License,
    contributors: List[ContributorDTO],
    publishClient: PublishClient,
    datasetAssetClient: DatasetAssetClient,
    sendNotification: NotificationMessage => EitherT[Future, CoreError, Done],
    collections: Seq[CollectionDTO],
    externalPublications: Seq[ExternalPublication]
  )(implicit
    ec: ExecutionContext,
    system: ActorSystem,
    jwtConfig: Jwt.Config
  ): EitherT[Future, CoreError, DatasetPublishStatus] = {

    // Discover needs presigned URLs to access dataset assets.
    // It does not have any permissions on the bucket.
    for {
      maybeReadme <- secureContainer.datasetAssetsManager
        .getReadme(dataset)

      readme <- maybeReadme match {
        case Some(r) => EitherT.rightT[Future, CoreError](r)
        case None =>
          EitherT.leftT[Future, DatasetAsset](
            PredicateError("Dataset cannot be revised without a description")
          )
      }
      readmePresignedUrl <- datasetAssetClient
        .generatePresignedUrl(readme, 1.minute)
        .leftMap(ThrowableError(_): CoreError)
        .toEitherT[Future]

      maybeBanner <- secureContainer.datasetAssetsManager
        .getBanner(dataset)

      banner <- maybeBanner match {
        case Some(b) => EitherT.rightT[Future, CoreError](b)
        case None =>
          EitherT.leftT[Future, DatasetAsset](
            PredicateError("Dataset cannot be revised without a banner image")
          )
      }
      bannerPresignedUrl <- datasetAssetClient
        .generatePresignedUrl(banner, 1.minute)
        .leftMap(ThrowableError(_): CoreError)
        .toEitherT[Future]

      maybeChangelog <- secureContainer.datasetAssetsManager
        .getChangelog(dataset)

      changelog <- maybeChangelog match {
        case Some(r) => EitherT.rightT[Future, CoreError](r)
        case None =>
          EitherT.leftT[Future, DatasetAsset](
            PredicateError("Dataset cannot be revised without a changelog")
          )
      }

      changelogPresignedUrl <- datasetAssetClient
        .generatePresignedUrl(changelog, 1.minute)
        .leftMap(ThrowableError(_): CoreError)
        .toEitherT[Future]

      discoverRequest = ReviseRequest(
        name = dataset.name,
        description = description,
        ownerId = owner.id,
        license = license,
        contributors = contributors
          .map(_.into[InternalContributor].transform)
          .toVector,
        tags = dataset.tags.toVector,
        ownerFirstName = owner.firstName,
        ownerLastName = owner.lastName,
        ownerOrcid = ownerOrcid,
        bannerPresignedUrl = bannerPresignedUrl.toString,
        readmePresignedUrl = readmePresignedUrl.toString,
        changelogPresignedUrl = changelogPresignedUrl.toString,
        collections =
          Some(collections.map(_.into[InternalCollection].transform).toVector),
        externalPublications = Some(
          externalPublications
            .map(
              ep =>
                InternalExternalPublication(
                  ep.doi.value,
                  Some(ep.relationshipType)
                )
            )
            .toVector
        )
      )

      response <- publishClient
        .revise(
          secureContainer.organization.id,
          dataset.id,
          discoverRequest,
          getTokenHeaders(secureContainer.organization, dataset)
        )
        .leftSemiflatMap(handleGuardrailError)
        .flatMap {
          _.fold[EitherT[Future, CoreError, DatasetPublishStatus]](
            handleBadRequest = msg => EitherT.leftT(PredicateError(msg)),
            handleCreated = response => EitherT.pure(response),
            handleInternalServerError = msg => EitherT.leftT(ServiceError(msg)),
            handleForbidden = msg => EitherT.leftT(InvalidAction(msg)),
            handleUnauthorized = EitherT
              .leftT(UnauthorizedError("Revision unauthorized"))
          )
        }

      _ = logger.info(
        s"Revised dataset ${dataset.nodeId} ${response.publishedDatasetId match {
          case Some(id) => s"to public dataset id=$id"
          case None => ""
        }} version=${response.publishedVersionCount}"
      )

      _ <- sendNotification(
        DiscoverPublishNotification(
          List(secureContainer.user.id),
          MessageType.DatasetUpdate,
          "Dataset revised",
          dataset.id,
          response.publishedDatasetId,
          response.publishedVersionCount,
          response.lastPublishedDate,
          response.status,
          success = true,
          None
        )
      )

    } yield response

  }

  /**
    * Build and send a request to Discover Service to unpublish a dataset.
    */
  def sendUnpublishRequest(
    organization: Organization,
    dataset: Dataset,
    user: User,
    publishClient: PublishClient,
    sendNotification: NotificationMessage => EitherT[Future, CoreError, Done]
  )(implicit
    ec: ExecutionContext,
    system: ActorSystem,
    jwtConfig: Jwt.Config
  ): EitherT[Future, CoreError, Option[DatasetPublishStatus]] = {

    for {
      status <- publishClient
        .unpublish(
          organization.id,
          dataset.id,
          UnpublishRequest(resolveBucketConfig(organization)),
          getTokenHeaders(organization, dataset)
        )
        .leftSemiflatMap(handleGuardrailError)
        .flatMap {
          _.fold[EitherT[Future, CoreError, Option[DatasetPublishStatus]]](
            handleBadRequest = msg => EitherT.leftT(PredicateError(msg)),
            handleOK = response => EitherT.pure(Some(response)),
            handleInternalServerError = msg => EitherT.leftT(ServiceError(msg)),
            handleForbidden = msg =>
              EitherT
                .leftT(DatasetRolePermissionError(user.nodeId, dataset.id)),
            handleUnauthorized =
              EitherT.leftT(UnauthorizedError("Unpublish unauthorized")),
            handleNoContent = EitherT
              .pure[Future, CoreError](None: Option[DatasetPublishStatus])
          )
        }

      _ <- status match {
        case Some(status) => {
          val notification = DiscoverPublishNotification(
            List(user.id),
            MessageType.DatasetUpdate,
            s"Dataset ${dataset.name} has been unpublished.",
            dataset.id,
            status.publishedDatasetId,
            status.publishedVersionCount,
            status.lastPublishedDate,
            status.status,
            success = true,
            None
          )
          sendNotification(notification)
        }
        case None =>
          EitherT
            .pure[Future, CoreError](Done)
      }

    } yield status
  }

  /**
    * Build and send a request to Discover Service to unpublish a dataset.
    */
  def sendReleaseRequest(
    organization: Organization,
    dataset: Dataset,
    user: User,
    publishClient: PublishClient,
    sendNotification: NotificationMessage => EitherT[Future, CoreError, Done]
  )(implicit
    ec: ExecutionContext,
    system: ActorSystem,
    jwtConfig: Jwt.Config
  ): EitherT[Future, CoreError, Option[DatasetPublishStatus]] = {

    for {
      status <- publishClient
        .release(
          organization.id,
          dataset.id,
          ReleaseRequest(resolveBucketConfig(organization)),
          getTokenHeaders(organization, dataset)
        )
        .leftSemiflatMap(handleGuardrailError)
        .flatMap {
          _.fold[EitherT[Future, CoreError, Option[DatasetPublishStatus]]](
            handleAccepted = response => EitherT.pure(Some(response)),
            handleInternalServerError = msg => EitherT.leftT(ServiceError(msg)),
            handleForbidden = msg =>
              EitherT
                .leftT(DatasetRolePermissionError(user.nodeId, dataset.id)),
            handleUnauthorized = EitherT
              .leftT(UnauthorizedError("Release unauthorized")),
            handleNotFound = EitherT.leftT(NotFound(dataset.nodeId))
          )
        }

      _ <- status match {
        case Some(status) => {
          val notification = DiscoverPublishNotification(
            List(user.id),
            MessageType.DatasetUpdate,
            s"Dataset ${dataset.name} is being released to Discover",
            dataset.id,
            status.publishedDatasetId,
            status.publishedVersionCount,
            status.lastPublishedDate,
            status.status,
            success = true,
            None
          )
          sendNotification(notification)
        }
        case None =>
          EitherT
            .pure[Future, CoreError](Done)
      }

    } yield status
  }

  def getPublishedStatus(
    publishClient: PublishClient,
    organization: Organization,
    dataset: Dataset,
    user: User
  )(implicit
    ec: ExecutionContext,
    system: ActorSystem,
    jwtConfig: Jwt.Config
  ): EitherT[Future, CoreError, DatasetPublishStatus] = {

    for {

      status <- publishClient
        .getStatus(
          organization.id,
          dataset.id,
          getTokenHeaders(organization, dataset)
        )
        .leftSemiflatMap(handleGuardrailError)
        .flatMap {
          _.fold[EitherT[Future, CoreError, DatasetPublishStatus]](
            handleOK = response => EitherT.pure(response),
            handleInternalServerError = msg => EitherT.leftT(ServiceError(msg)),
            handleForbidden = msg =>
              EitherT
                .leftT(DatasetRolePermissionError(user.nodeId, dataset.id)),
            handleUnauthorized = EitherT
              .leftT(UnauthorizedError("getPublishStatus unauthorized"))
          )
        }
    } yield status
  }

  def getPublishedStatusForOrganization(
    publishClient: PublishClient,
    organization: Organization,
    user: User
  )(implicit
    ec: ExecutionContext,
    system: ActorSystem,
    jwtConfig: Jwt.Config
  ): EitherT[Future, CoreError, List[DatasetPublishStatus]] = {

    val token =
      JwtAuthenticator.generateServiceToken(1.minute, organization.id, None)

    val tokenHeader = Authorization(OAuth2BearerToken(token.value))

    publishClient
      .getStatuses(organization.id, List(tokenHeader))
      .leftSemiflatMap(handleGuardrailError)
      .flatMap {
        _.fold[EitherT[Future, CoreError, List[DatasetPublishStatus]]](
          handleOK = response => EitherT.pure(response.toList),
          handleInternalServerError = msg => EitherT.leftT(ServiceError(msg)),
          handleForbidden = msg =>
            EitherT
              .leftT(OrganizationPermissionError(user.nodeId, organization.id)),
          handleUnauthorized = EitherT
            .leftT(UnauthorizedError("getPublishStatuses unauthorized"))
        )
      }
  }

  def getPublishedDatasetsForOrganization(
    searchClient: SearchClient,
    organization: Organization,
    limit: Option[Int],
    offset: Option[Int],
    orderBy: (DatasetManager.OrderByColumn, DatasetManager.OrderByDirection),
    query: Option[String]
  )(implicit
    ec: ExecutionContext,
    system: ActorSystem,
    jwtConfig: Jwt.Config
  ): EitherT[Future, CoreError, DatasetsPage] = {

    val mappedOrderByColumn = orderBy._1 match {
      case DatasetManager.OrderByColumn.Name => "name"
      case DatasetManager.OrderByColumn.UpdatedAt => "date"
    }

    val mappedOrderByDirection = orderBy._2 match {
      case DatasetManager.OrderByDirection.Asc => "Asc"
      case DatasetManager.OrderByDirection.Desc => "Desc"
    }

    searchClient
      .searchDatasets(
        limit = limit,
        offset = offset,
        query = query,
        organization = None,
        organizationId = Some(organization.id),
        tags = None,
        embargo = None,
        orderBy = Some(mappedOrderByColumn),
        orderDirection = Some(mappedOrderByDirection)
      )
      .leftSemiflatMap(handleGuardrailError)
      .flatMap {
        _.fold[EitherT[Future, CoreError, DatasetsPage]](
          handleOK = response => EitherT.pure(response),
          handleBadRequest = msg => EitherT.leftT(PredicateError(msg))
        )
      }
  }

  /**
    * Return a map of dataset ID -> status of the dataset on Discover
    */
  def getPublishedDatasetsFromDiscover(
    publishClient: PublishClient
  )(
    organization: Organization,
    user: User
  )(implicit
    ec: ExecutionContext,
    system: ActorSystem,
    jwtConfig: Jwt.Config
  ): EitherT[Future, CoreError, Map[Int, DiscoverPublishedDatasetDTO]] =
    getPublishedStatusForOrganization(publishClient, organization, user).map(
      _.map(
        ps =>
          ps.sourceDatasetId -> DiscoverPublishedDatasetDTO(
            ps.publishedDatasetId,
            ps.publishedVersionCount,
            ps.lastPublishedDate
          )
      ).toMap
    )

  def getTokenHeaders(
    organization: Organization,
    dataset: Dataset
  )(implicit
    ec: ExecutionContext,
    system: ActorSystem,
    jwtConfig: Jwt.Config
  ): List[Authorization] = {
    val token = JwtAuthenticator.generateServiceToken(
      1.minute,
      organization.id,
      Some(dataset.id)
    )

    List(Authorization(OAuth2BearerToken(token.value)))
  }

  /**
    * Handle errors from the Guardrail client.
    *
    * These are either HTTP responses that are not documented in the Swagger
    * file, or the error thrown by a failed Future during the request.
    */
  private def handleGuardrailError(
    implicit
    ec: ExecutionContext,
    system: ActorSystem
  ): Either[Throwable, HttpResponse] => Future[CoreError] =
    _.fold(
      error => Future.successful(ServiceError(error.toString)),
      resp =>
        resp.entity.toStrict(1.second).map { entity =>
          ServiceError(s"HTTP ${resp.status}: ${entity.data.utf8String}")
        }
    )
}
