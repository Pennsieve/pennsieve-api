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

package com.pennsieve.models

import cats.data._
import cats.implicits._
import enumeratum._
import enumeratum.EnumEntry._
import java.util.UUID
import scala.collection.immutable
import io.circe._
import io.circe.syntax._
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

import com.pennsieve.dtos.ContributorDTO

/**
  * Try and keep this spreadsheet up to date with enums/details
  * https://docs.google.com/spreadsheets/d/1GO9wdjAy4cGPDNR5gntHJeq9JuQuYmcLvmWd1XTKA9Y/edit
  */
sealed trait ChangelogEventDetail { self =>
  val eventType: ChangelogEventName
}

object ChangelogEventDetail {
  import ChangelogEventName._

  implicit val encoder: Encoder[ChangelogEventDetail] =
    Encoder.instance {
      case d: CreateDataset => d.asJson
      case d: UpdateMetadata => d.asJson
      case d: UpdateName => d.asJson
      case d: UpdateDescription => d.asJson
      case d: UpdateLicense => d.asJson
      case d: AddTag => d.asJson
      case d: RemoveTag => d.asJson
      case d: UpdateReadme => d.asJson
      case d: UpdateBannerImage => d.asJson
      case d: AddCollection => d.asJson
      case d: RemoveCollection => d.asJson
      case d: AddContributor => d.asJson
      case d: RemoveContributor => d.asJson
      case d: AddExternalPublication => d.asJson
      case d: RemoveExternalPublication => d.asJson
      case d: UpdateIgnoreFiles => d.asJson
      case d: UpdateStatus => d.asJson
      case d: UpdatePermission => d.asJson
      case d: UpdateOwner => d.asJson
      case d: CreatePackage => d.asJson
      case d: RenamePackage => d.asJson
      case d: MovePackage => d.asJson
      case d: DeletePackage => d.asJson
      case d: CreateModel => d.asJson
      case d: UpdateModel => d.asJson
      case d: DeleteModel => d.asJson
      case d: CreateModelProperty => d.asJson
      case d: UpdateModelProperty => d.asJson
      case d: DeleteModelProperty => d.asJson
      case d: CreateRecord => d.asJson
      case d: UpdateRecord => d.asJson
      case d: DeleteRecord => d.asJson
      case d: RequestPublication => d.asJson
      case d: AcceptPublication => d.asJson
      case d: RejectPublication => d.asJson
      case d: CancelPublication => d.asJson
      case d: RequestEmbargo => d.asJson
      case d: AcceptEmbargo => d.asJson
      case d: RejectEmbargo => d.asJson
      case d: CancelEmbargo => d.asJson
      case d: ReleaseEmbargo => d.asJson
      case d: RequestRemoval => d.asJson
      case d: AcceptRemoval => d.asJson
      case d: RejectRemoval => d.asJson
      case d: CancelRemoval => d.asJson
      case d: RequestRevision => d.asJson
      case d: AcceptRevision => d.asJson
      case d: RejectRevision => d.asJson
      case d: CancelRevision => d.asJson
      case d: CustomEvent => d.asJson
    }

  def decoder[A <: ChangelogEventDetail](
    eventType: ChangelogEventName
  ): Decoder[ChangelogEventDetail] =
    eventType match {
      case CREATE_DATASET => CreateDataset.decoder.widen
      case UPDATE_METADATA => UpdateMetadata.decoder.widen
      case UPDATE_NAME => UpdateName.decoder.widen
      case UPDATE_DESCRIPTION => UpdateDescription.decoder.widen
      case UPDATE_LICENSE => UpdateLicense.decoder.widen
      case ADD_TAG => AddTag.decoder.widen
      case REMOVE_TAG => RemoveTag.decoder.widen
      case UPDATE_README => UpdateReadme.decoder.widen
      case UPDATE_BANNER_IMAGE => UpdateBannerImage.decoder.widen
      case ADD_COLLECTION => AddCollection.decoder.widen
      case REMOVE_COLLECTION => RemoveCollection.decoder.widen
      case ADD_CONTRIBUTOR => AddContributor.decoder.widen
      case REMOVE_CONTRIBUTOR => RemoveContributor.decoder.widen
      case ADD_EXTERNAL_PUBLICATION => AddExternalPublication.decoder.widen
      case REMOVE_EXTERNAL_PUBLICATION =>
        RemoveExternalPublication.decoder.widen
      case UPDATE_IGNORE_FILES => UpdateIgnoreFiles.decoder.widen
      case UPDATE_STATUS => UpdateStatus.decoder.widen
      case UPDATE_PERMISSION => UpdatePermission.decoder.widen
      case UPDATE_OWNER => UpdateOwner.decoder.widen
      case CREATE_PACKAGE => CreatePackage.decoder.widen
      case RENAME_PACKAGE => RenamePackage.decoder.widen
      case MOVE_PACKAGE => MovePackage.decoder.widen
      case DELETE_PACKAGE => DeletePackage.decoder.widen
      case CREATE_MODEL => CreateModel.decoder.widen
      case UPDATE_MODEL => UpdateModel.decoder.widen
      case DELETE_MODEL => DeleteModel.decoder.widen
      case CREATE_MODEL_PROPERTY => CreateModelProperty.decoder.widen
      case UPDATE_MODEL_PROPERTY => UpdateModelProperty.decoder.widen
      case DELETE_MODEL_PROPERTY => DeleteModelProperty.decoder.widen
      case CREATE_RECORD => CreateRecord.decoder.widen
      case UPDATE_RECORD => UpdateRecord.decoder.widen
      case DELETE_RECORD => DeleteRecord.decoder.widen
      case REQUEST_PUBLICATION => RequestPublication.decoder.widen
      case ACCEPT_PUBLICATION => AcceptPublication.decoder.widen
      case REJECT_PUBLICATION => RejectPublication.decoder.widen
      case CANCEL_PUBLICATION => CancelPublication.decoder.widen
      case REQUEST_EMBARGO => RequestEmbargo.decoder.widen
      case ACCEPT_EMBARGO => AcceptEmbargo.decoder.widen
      case REJECT_EMBARGO => RejectEmbargo.decoder.widen
      case CANCEL_EMBARGO => CancelEmbargo.decoder.widen
      case RELEASE_EMBARGO => ReleaseEmbargo.decoder.widen
      case REQUEST_REMOVAL => RequestRemoval.decoder.widen
      case ACCEPT_REMOVAL => AcceptRemoval.decoder.widen
      case REJECT_REMOVAL => RejectRemoval.decoder.widen
      case CANCEL_REMOVAL => CancelRemoval.decoder.widen
      case REQUEST_REVISION => RequestRevision.decoder.widen
      case ACCEPT_REVISION => AcceptRevision.decoder.widen
      case REJECT_REVISION => RejectRevision.decoder.widen
      case CANCEL_REVISION => CancelRevision.decoder.widen
      case CUSTOM_EVENT => CustomEvent.decoder.widen
    }

  def fromPublicationStatus(
    status: DatasetPublicationStatus
  ): Option[ChangelogEventDetail] = {
    import PublicationStatus._
    import PublicationType._

    (status.publicationStatus, status.publicationType) match {

      case (Requested, Publication) => Some(RequestPublication(status.id))
      case (Accepted, Publication) => Some(AcceptPublication(status.id))
      case (Rejected, Publication) => Some(RejectPublication(status.id))
      case (Cancelled, Publication) => Some(CancelPublication(status.id))

      case (Requested, Embargo) => Some(RequestEmbargo(status.id))
      case (Accepted, Embargo) => Some(AcceptEmbargo(status.id))
      case (Rejected, Embargo) => Some(RejectEmbargo(status.id))
      case (Cancelled, Embargo) => Some(CancelEmbargo(status.id))
      case (Completed, Release) => Some(ReleaseEmbargo(status.id))

      case (Requested, Removal) => Some(RequestRemoval(status.id))
      case (Accepted, Removal) => Some(AcceptRemoval(status.id))
      case (Rejected, Removal) => Some(RejectRemoval(status.id))
      case (Cancelled, Removal) => Some(CancelRemoval(status.id))

      case (Requested, Revision) => Some(RequestRevision(status.id))
      case (Accepted, Revision) => Some(AcceptRevision(status.id))
      case (Rejected, Revision) => Some(RejectRevision(status.id))
      case (Cancelled, Revision) => Some(CancelRevision(status.id))

      case _ => None
    }
  }

  case class CreateDataset() extends ChangelogEventDetail {
    val eventType = CREATE_DATASET
  }

  object CreateDataset {
    implicit val encoder: Encoder[CreateDataset] = deriveEncoder[CreateDataset]
    implicit val decoder: Decoder[CreateDataset] = deriveDecoder[CreateDataset]
  }

  case class UpdateName(oldName: String, newName: String)
      extends ChangelogEventDetail {
    val eventType = UPDATE_NAME
  }

  object UpdateName {
    implicit val encoder: Encoder[UpdateName] = deriveEncoder[UpdateName]
    implicit val decoder: Decoder[UpdateName] = deriveDecoder[UpdateName]
  }

  case class UpdateDescription(
    oldDescription: Option[String],
    newDescription: Option[String]
  ) extends ChangelogEventDetail {
    val eventType = UPDATE_DESCRIPTION
  }

  object UpdateDescription {
    implicit val encoder: Encoder[UpdateDescription] =
      deriveEncoder[UpdateDescription]
    implicit val decoder: Decoder[UpdateDescription] =
      deriveDecoder[UpdateDescription]
  }

  case class UpdateLicense(
    oldLicense: Option[License],
    newLicense: Option[License]
  ) extends ChangelogEventDetail {
    val eventType = UPDATE_LICENSE
  }

  object UpdateLicense {
    implicit val encoder: Encoder[UpdateLicense] = deriveEncoder[UpdateLicense]
    implicit val decoder: Decoder[UpdateLicense] = deriveDecoder[UpdateLicense]
  }

  case class AddTag(name: String) extends ChangelogEventDetail {
    val eventType = ADD_TAG
  }

  object AddTag {
    implicit val encoder: Encoder[AddTag] = deriveEncoder[AddTag]
    implicit val decoder: Decoder[AddTag] = deriveDecoder[AddTag]
  }

  case class CustomEvent(event_type: String, message: String)
      extends ChangelogEventDetail {
    val eventType = CUSTOM_EVENT
  }

  object CustomEvent {
    implicit val encoder: Encoder[CustomEvent] = deriveEncoder[CustomEvent]
    implicit val decoder: Decoder[CustomEvent] = deriveDecoder[CustomEvent]
  }

  case class RemoveTag(name: String) extends ChangelogEventDetail {
    val eventType = REMOVE_TAG
  }

  object RemoveTag {
    implicit val encoder: Encoder[RemoveTag] = deriveEncoder[RemoveTag]
    implicit val decoder: Decoder[RemoveTag] = deriveDecoder[RemoveTag]
  }

  case class UpdateReadme(oldReadme: Option[String], newReadme: Option[String])
      extends ChangelogEventDetail {
    val eventType = UPDATE_README
  }

  object UpdateReadme {
    implicit val encoder: Encoder[UpdateReadme] = deriveEncoder[UpdateReadme]
    implicit val decoder: Decoder[UpdateReadme] = deriveDecoder[UpdateReadme]
  }

  case class UpdateBannerImage(
    oldBanner: Option[String],
    newBanner: Option[String]
  ) extends ChangelogEventDetail {
    val eventType = UPDATE_BANNER_IMAGE
  }

  object UpdateBannerImage {
    implicit val encoder: Encoder[UpdateBannerImage] =
      deriveEncoder[UpdateBannerImage]
    implicit val decoder: Decoder[UpdateBannerImage] =
      deriveDecoder[UpdateBannerImage]
  }

  case class AddCollection(id: Int, name: String) extends ChangelogEventDetail {
    val eventType = ADD_COLLECTION
  }

  object AddCollection {
    def apply(collection: Collection): AddCollection =
      AddCollection(id = collection.id, name = collection.name)

    implicit val encoder: Encoder[AddCollection] = deriveEncoder[AddCollection]
    implicit val decoder: Decoder[AddCollection] = deriveDecoder[AddCollection]
  }

  case class RemoveCollection(id: Int, name: String)
      extends ChangelogEventDetail {
    val eventType = REMOVE_COLLECTION
  }

  object RemoveCollection {
    def apply(collection: Collection): RemoveCollection =
      RemoveCollection(id = collection.id, name = collection.name)

    implicit val encoder: Encoder[RemoveCollection] =
      deriveEncoder[RemoveCollection]
    implicit val decoder: Decoder[RemoveCollection] =
      deriveDecoder[RemoveCollection]
  }

  case class AddContributor(
    id: Int,
    firstName: String,
    middleInitial: Option[String],
    lastName: String,
    degree: Option[Degree]
  ) extends ChangelogEventDetail {
    val eventType = ADD_CONTRIBUTOR
  }

  object AddContributor {
    def apply(c: ContributorDTO): AddContributor =
      AddContributor(
        id = c.id,
        firstName = c.firstName,
        middleInitial = c.middleInitial,
        lastName = c.lastName,
        degree = c.degree
      )

    implicit val encoder: Encoder[AddContributor] =
      deriveEncoder[AddContributor]
    implicit val decoder: Decoder[AddContributor] =
      deriveDecoder[AddContributor]
  }

  case class RemoveContributor(
    id: Int,
    firstName: String,
    middleInitial: Option[String],
    lastName: String,
    degree: Option[Degree]
  ) extends ChangelogEventDetail {
    val eventType = REMOVE_CONTRIBUTOR
  }

  object RemoveContributor {
    def apply(c: ContributorDTO): RemoveContributor =
      RemoveContributor(
        id = c.id,
        firstName = c.firstName,
        middleInitial = c.middleInitial,
        lastName = c.lastName,
        degree = c.degree
      )

    implicit val encoder: Encoder[RemoveContributor] =
      deriveEncoder[RemoveContributor]
    implicit val decoder: Decoder[RemoveContributor] =
      deriveDecoder[RemoveContributor]
  }

  case class AddExternalPublication(
    doi: Doi,
    relationshipType: RelationshipType
  ) extends ChangelogEventDetail {
    val eventType = ADD_EXTERNAL_PUBLICATION
  }

  object AddExternalPublication {
    implicit val encoder: Encoder[AddExternalPublication] =
      deriveEncoder[AddExternalPublication]
    implicit val decoder: Decoder[AddExternalPublication] =
      deriveDecoder[AddExternalPublication]
  }

  case class RemoveExternalPublication(
    doi: Doi,
    relationshipType: RelationshipType
  ) extends ChangelogEventDetail {
    val eventType = REMOVE_EXTERNAL_PUBLICATION
  }

  object RemoveExternalPublication {
    implicit val encoder: Encoder[RemoveExternalPublication] =
      deriveEncoder[RemoveExternalPublication]
    implicit val decoder: Decoder[RemoveExternalPublication] =
      deriveDecoder[RemoveExternalPublication]
  }

  case class UpdateIgnoreFiles(totalCount: Int) extends ChangelogEventDetail {
    val eventType = UPDATE_IGNORE_FILES
  }

  object UpdateIgnoreFiles {
    implicit val encoder: Encoder[UpdateIgnoreFiles] =
      deriveEncoder[UpdateIgnoreFiles]
    implicit val decoder: Decoder[UpdateIgnoreFiles] =
      deriveDecoder[UpdateIgnoreFiles]
  }

  case class UpdateStatus(oldStatus: StatusDetail, newStatus: StatusDetail)
      extends ChangelogEventDetail {
    val eventType = UPDATE_STATUS
  }

  object UpdateStatus {
    def apply(
      oldStatus: DatasetStatus,
      newStatus: DatasetStatus
    ): UpdateStatus =
      UpdateStatus(
        oldStatus = StatusDetail(oldStatus),
        newStatus = StatusDetail(newStatus)
      )

    implicit val encoder: Encoder[UpdateStatus] = deriveEncoder[UpdateStatus]
    implicit val decoder: Decoder[UpdateStatus] = deriveDecoder[UpdateStatus]
  }

  case class StatusDetail(id: Int, name: String, displayName: String)

  object StatusDetail {
    def apply(status: DatasetStatus): StatusDetail =
      StatusDetail(
        id = status.id,
        name = status.name,
        displayName = status.displayName
      )

    implicit val encoder: Encoder[StatusDetail] = deriveEncoder[StatusDetail]
    implicit val decoder: Decoder[StatusDetail] = deriveDecoder[StatusDetail]
  }

  /**
    * Backwards-compatible, deprecated. No longer used for new events.
    */
  case class UpdateMetadata() extends ChangelogEventDetail {
    val eventType = UPDATE_METADATA
  }

  object UpdateMetadata {
    implicit val encoder: Encoder[UpdateMetadata] =
      deriveEncoder[UpdateMetadata]
    implicit val decoder: Decoder[UpdateMetadata] =
      deriveDecoder[UpdateMetadata]
  }

  case class UpdatePermission(
    oldRole: Option[Role],
    newRole: Option[Role],
    userId: Option[Int],
    teamId: Option[Int],
    organizationId: Option[Int]
  ) extends ChangelogEventDetail {
    val eventType = UPDATE_PERMISSION
  }

  object UpdatePermission {
    implicit val encoder: Encoder[UpdatePermission] =
      deriveEncoder[UpdatePermission]
    implicit val decoder: Decoder[UpdatePermission] =
      deriveDecoder[UpdatePermission]
  }

  case class UpdateOwner(oldOwner: Int, newOwner: Int)
      extends ChangelogEventDetail {
    val eventType = UPDATE_OWNER
  }

  object UpdateOwner {
    implicit val encoder: Encoder[UpdateOwner] = deriveEncoder[UpdateOwner]
    implicit val decoder: Decoder[UpdateOwner] = deriveDecoder[UpdateOwner]
  }

  case class CreatePackage(
    id: Int,
    nodeId: Option[String],
    name: Option[String],
    parent: Option[PackageDetail]
  ) extends ChangelogEventDetail {
    val eventType = CREATE_PACKAGE
  }

  object CreatePackage {
    def apply(pkg: Package, parent: Option[Package]): CreatePackage =
      CreatePackage(
        id = pkg.id,
        nodeId = Some(pkg.nodeId),
        name = Some(pkg.name),
        parent = parent.map(PackageDetail(_))
      )

    implicit val encoder: Encoder[CreatePackage] = deriveEncoder[CreatePackage]
    implicit val decoder: Decoder[CreatePackage] = deriveDecoder[CreatePackage]
  }

  case class RenamePackage(
    id: Int,
    nodeId: Option[String],
    oldName: String,
    newName: String,
    parent: Option[PackageDetail]
  ) extends ChangelogEventDetail {
    val eventType = RENAME_PACKAGE
  }

  object RenamePackage {
    def apply(
      pkg: Package,
      oldName: String,
      newName: String,
      parent: Option[Package]
    ): RenamePackage =
      RenamePackage(
        id = pkg.id,
        nodeId = Some(pkg.nodeId),
        oldName = oldName,
        newName = newName,
        parent = parent.map(PackageDetail(_))
      )
    implicit val encoder: Encoder[RenamePackage] = deriveEncoder[RenamePackage]
    implicit val decoder: Decoder[RenamePackage] = deriveDecoder[RenamePackage]
  }

  case class MovePackage(
    id: Int,
    nodeId: Option[String],
    name: Option[String],
    oldParent: Option[PackageDetail],
    newParent: Option[PackageDetail]
  ) extends ChangelogEventDetail {
    val eventType = MOVE_PACKAGE
  }

  object MovePackage {
    def apply(
      pkg: Package,
      oldParent: Option[Package],
      newParent: Option[Package]
    ): MovePackage =
      MovePackage(
        id = pkg.id,
        nodeId = Some(pkg.nodeId),
        name = Some(pkg.name),
        oldParent = oldParent.map(PackageDetail(_)),
        newParent = newParent.map(PackageDetail(_))
      )

    implicit val encoder: Encoder[MovePackage] = deriveEncoder[MovePackage]
    implicit val decoder: Decoder[MovePackage] = deriveDecoder[MovePackage]
  }

  case class PackageDetail(
    id: Int,
    nodeId: Option[String],
    name: Option[String]
  )

  object PackageDetail {
    implicit val encoder: Encoder[PackageDetail] = deriveEncoder[PackageDetail]
    implicit val decoder: Decoder[PackageDetail] = deriveDecoder[PackageDetail]

    def apply(pkg: Package): PackageDetail =
      PackageDetail(
        id = pkg.id,
        nodeId = Some(pkg.nodeId),
        name = Some(pkg.name)
      )
  }

  case class DeletePackage(
    id: Int,
    nodeId: Option[String],
    name: Option[String],
    parent: Option[PackageDetail]
  ) extends ChangelogEventDetail {
    val eventType = DELETE_PACKAGE
  }

  object DeletePackage {
    def apply(pkg: Package, parent: Option[Package]): DeletePackage =
      DeletePackage(
        id = pkg.id,
        nodeId = Some(pkg.nodeId),
        name = Some(pkg.name),
        parent = parent.map(PackageDetail(_))
      )

    implicit val encoder: Encoder[DeletePackage] = deriveEncoder[DeletePackage]
    implicit val decoder: Decoder[DeletePackage] = deriveDecoder[DeletePackage]
  }

  case class CreateModel(id: UUID, name: String) extends ChangelogEventDetail {
    val eventType = CREATE_MODEL
  }

  object CreateModel {
    implicit val encoder: Encoder[CreateModel] = deriveEncoder[CreateModel]
    implicit val decoder: Decoder[CreateModel] = deriveDecoder[CreateModel]
  }

  case class UpdateModel(id: UUID, name: String) extends ChangelogEventDetail {
    val eventType = UPDATE_MODEL
  }

  object UpdateModel {
    implicit val encoder: Encoder[UpdateModel] = deriveEncoder[UpdateModel]
    implicit val decoder: Decoder[UpdateModel] = deriveDecoder[UpdateModel]
  }

  case class DeleteModel(id: UUID, name: String) extends ChangelogEventDetail {
    val eventType = DELETE_MODEL
  }

  object DeleteModel {
    implicit val encoder: Encoder[DeleteModel] = deriveEncoder[DeleteModel]
    implicit val decoder: Decoder[DeleteModel] = deriveDecoder[DeleteModel]
  }

  case class CreateModelProperty(
    modelId: UUID,
    modelName: String,
    propertyName: String
  ) extends ChangelogEventDetail {
    val eventType = CREATE_MODEL_PROPERTY
  }

  object CreateModelProperty {
    implicit val encoder: Encoder[CreateModelProperty] =
      deriveEncoder[CreateModelProperty]
    implicit val decoder: Decoder[CreateModelProperty] =
      deriveDecoder[CreateModelProperty]
  }

  case class UpdateModelProperty(
    modelId: UUID,
    modelName: String,
    propertyName: String
  ) extends ChangelogEventDetail {
    val eventType = UPDATE_MODEL_PROPERTY
  }

  object UpdateModelProperty {
    implicit val encoder: Encoder[UpdateModelProperty] =
      deriveEncoder[UpdateModelProperty]
    implicit val decoder: Decoder[UpdateModelProperty] =
      deriveDecoder[UpdateModelProperty]
  }

  case class DeleteModelProperty(
    modelId: UUID,
    modelName: String,
    propertyName: String
  ) extends ChangelogEventDetail {
    val eventType = DELETE_MODEL_PROPERTY
  }

  object DeleteModelProperty {
    implicit val encoder: Encoder[DeleteModelProperty] =
      deriveEncoder[DeleteModelProperty]
    implicit val decoder: Decoder[DeleteModelProperty] =
      deriveDecoder[DeleteModelProperty]
  }

  case class CreateRecord(id: UUID, name: Option[String], modelId: Option[UUID])
      extends ChangelogEventDetail {
    val eventType = CREATE_RECORD
  }

  object CreateRecord {
    implicit val encoder: Encoder[CreateRecord] = deriveEncoder[CreateRecord]
    implicit val decoder: Decoder[CreateRecord] = deriveDecoder[CreateRecord]
  }

  case class UpdateRecord(
    id: UUID,
    name: Option[String],
    modelId: Option[UUID],
    properties: Option[List[PropertyDiff]]
  ) extends ChangelogEventDetail {
    val eventType = UPDATE_RECORD
  }

  object UpdateRecord {
    implicit val encoder: Encoder[UpdateRecord] = deriveEncoder[UpdateRecord]
    implicit val decoder: Decoder[UpdateRecord] = deriveDecoder[UpdateRecord]
  }

  case class PropertyDiff(
    name: String,
    dataType: Option[Json],
    oldValue: Json,
    newValue: Json
  )

  object PropertyDiff {
    implicit val encoder: Encoder[PropertyDiff] =
      deriveEncoder[PropertyDiff]
    implicit val decoder: Decoder[PropertyDiff] =
      deriveDecoder[PropertyDiff]
  }

  case class DeleteRecord(id: UUID, name: Option[String], modelId: Option[UUID])
      extends ChangelogEventDetail {
    val eventType = DELETE_RECORD
  }

  object DeleteRecord {
    implicit val encoder: Encoder[DeleteRecord] = deriveEncoder[DeleteRecord]
    implicit val decoder: Decoder[DeleteRecord] = deriveDecoder[DeleteRecord]
  }

  // Publishing events

  case class RequestPublication(publicationStatusId: Int)
      extends ChangelogEventDetail {
    val eventType = REQUEST_PUBLICATION
  }

  object RequestPublication {
    implicit val encoder: Encoder[RequestPublication] =
      deriveEncoder[RequestPublication]
    implicit val decoder: Decoder[RequestPublication] =
      deriveDecoder[RequestPublication]
  }

  case class AcceptPublication(publicationStatusId: Int)
      extends ChangelogEventDetail {
    val eventType = ACCEPT_PUBLICATION
  }

  object AcceptPublication {
    implicit val encoder: Encoder[AcceptPublication] =
      deriveEncoder[AcceptPublication]
    implicit val decoder: Decoder[AcceptPublication] =
      deriveDecoder[AcceptPublication]
  }

  case class RejectPublication(publicationStatusId: Int)
      extends ChangelogEventDetail {
    val eventType = REJECT_PUBLICATION
  }

  object RejectPublication {
    implicit val encoder: Encoder[RejectPublication] =
      deriveEncoder[RejectPublication]
    implicit val decoder: Decoder[RejectPublication] =
      deriveDecoder[RejectPublication]
  }

  case class CancelPublication(publicationStatusId: Int)
      extends ChangelogEventDetail {
    val eventType = CANCEL_PUBLICATION
  }

  object CancelPublication {
    implicit val encoder: Encoder[CancelPublication] =
      deriveEncoder[CancelPublication]
    implicit val decoder: Decoder[CancelPublication] =
      deriveDecoder[CancelPublication]
  }

  case class RequestEmbargo(publicationStatusId: Int)
      extends ChangelogEventDetail {
    val eventType = REQUEST_EMBARGO
  }

  object RequestEmbargo {
    implicit val encoder: Encoder[RequestEmbargo] =
      deriveEncoder[RequestEmbargo]
    implicit val decoder: Decoder[RequestEmbargo] =
      deriveDecoder[RequestEmbargo]
  }

  case class AcceptEmbargo(publicationStatusId: Int)
      extends ChangelogEventDetail {
    val eventType = ACCEPT_EMBARGO
  }

  object AcceptEmbargo {
    implicit val encoder: Encoder[AcceptEmbargo] = deriveEncoder[AcceptEmbargo]
    implicit val decoder: Decoder[AcceptEmbargo] = deriveDecoder[AcceptEmbargo]
  }

  case class RejectEmbargo(publicationStatusId: Int)
      extends ChangelogEventDetail {
    val eventType = REJECT_EMBARGO
  }

  object RejectEmbargo {
    implicit val encoder: Encoder[RejectEmbargo] = deriveEncoder[RejectEmbargo]
    implicit val decoder: Decoder[RejectEmbargo] = deriveDecoder[RejectEmbargo]
  }

  case class ReleaseEmbargo(publicationStatusId: Int)
      extends ChangelogEventDetail {
    val eventType = RELEASE_EMBARGO
  }

  object ReleaseEmbargo {
    implicit val encoder: Encoder[ReleaseEmbargo] =
      deriveEncoder[ReleaseEmbargo]
    implicit val decoder: Decoder[ReleaseEmbargo] =
      deriveDecoder[ReleaseEmbargo]
  }

  case class CancelEmbargo(publicationStatusId: Int)
      extends ChangelogEventDetail {
    val eventType = CANCEL_EMBARGO
  }

  object CancelEmbargo {
    implicit val encoder: Encoder[CancelEmbargo] = deriveEncoder[CancelEmbargo]
    implicit val decoder: Decoder[CancelEmbargo] = deriveDecoder[CancelEmbargo]
  }

  case class RequestRemoval(publicationStatusId: Int)
      extends ChangelogEventDetail {
    val eventType = REQUEST_REMOVAL
  }

  object RequestRemoval {
    implicit val encoder: Encoder[RequestRemoval] =
      deriveEncoder[RequestRemoval]
    implicit val decoder: Decoder[RequestRemoval] =
      deriveDecoder[RequestRemoval]
  }

  case class AcceptRemoval(publicationStatusId: Int)
      extends ChangelogEventDetail {
    val eventType = ACCEPT_REMOVAL
  }

  object AcceptRemoval {
    implicit val encoder: Encoder[AcceptRemoval] = deriveEncoder[AcceptRemoval]
    implicit val decoder: Decoder[AcceptRemoval] = deriveDecoder[AcceptRemoval]
  }

  case class RejectRemoval(publicationStatusId: Int)
      extends ChangelogEventDetail {
    val eventType = REJECT_REMOVAL
  }

  object RejectRemoval {
    implicit val encoder: Encoder[RejectRemoval] = deriveEncoder[RejectRemoval]
    implicit val decoder: Decoder[RejectRemoval] = deriveDecoder[RejectRemoval]
  }

  case class CancelRemoval(publicationStatusId: Int)
      extends ChangelogEventDetail {
    val eventType = CANCEL_REMOVAL
  }

  object CancelRemoval {
    implicit val encoder: Encoder[CancelRemoval] = deriveEncoder[CancelRemoval]
    implicit val decoder: Decoder[CancelRemoval] = deriveDecoder[CancelRemoval]
  }

  case class RequestRevision(publicationStatusId: Int)
      extends ChangelogEventDetail {
    val eventType = REQUEST_REVISION
  }

  object RequestRevision {
    implicit val encoder: Encoder[RequestRevision] =
      deriveEncoder[RequestRevision]
    implicit val decoder: Decoder[RequestRevision] =
      deriveDecoder[RequestRevision]
  }

  case class AcceptRevision(publicationStatusId: Int)
      extends ChangelogEventDetail {
    val eventType = ACCEPT_REVISION
  }

  object AcceptRevision {
    implicit val encoder: Encoder[AcceptRevision] =
      deriveEncoder[AcceptRevision]
    implicit val decoder: Decoder[AcceptRevision] =
      deriveDecoder[AcceptRevision]
  }

  case class RejectRevision(publicationStatusId: Int)
      extends ChangelogEventDetail {
    val eventType = REJECT_REVISION
  }

  object RejectRevision {
    implicit val encoder: Encoder[RejectRevision] =
      deriveEncoder[RejectRevision]
    implicit val decoder: Decoder[RejectRevision] =
      deriveDecoder[RejectRevision]
  }

  case class CancelRevision(publicationStatusId: Int)
      extends ChangelogEventDetail {
    val eventType = CANCEL_REVISION
  }

  object CancelRevision {
    implicit val encoder: Encoder[CancelRevision] =
      deriveEncoder[CancelRevision]
    implicit val decoder: Decoder[CancelRevision] =
      deriveDecoder[CancelRevision]
  }

}
