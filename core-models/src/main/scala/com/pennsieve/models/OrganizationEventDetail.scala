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

import cats.implicits._
import io.circe._
import io.circe.syntax._
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

/**
  * See docs/changelog-events.md Part 2 for the full design (pennsieve-api#405).
  *
  * Mirrors `ChangelogEventDetail`'s case-class-per-event-name pattern. Scoped
  * in this first implementation pass to the Part 1+2 vocabulary in
  * `OrganizationEventName` -- see that file's scaladoc for what's deferred.
  */
sealed trait OrganizationEventDetail { self =>
  val eventType: OrganizationEventName
}

object OrganizationEventDetail {
  import OrganizationEventName._

  implicit val encoder: Encoder[OrganizationEventDetail] =
    Encoder.instance {
      case d: AddOrganizationUser => d.asJson
      case d: AddExternalOrganizationUser => d.asJson
      case d: RemoveOrganizationUser => d.asJson
      case d: UpdateOrganizationPermission => d.asJson
      case d: AddTeamUser => d.asJson
      case d: RemoveTeamUser => d.asJson
      case d: CreateTeam => d.asJson
      case d: UpdateTeam => d.asJson
      case d: DeleteTeam => d.asJson
      case d: AddedToDataset => d.asJson
      case d: RemovedFromDataset => d.asJson
      case d: DatasetOwnershipTransferred => d.asJson
    }

  def decoder[A <: OrganizationEventDetail](
    eventType: OrganizationEventName
  ): Decoder[OrganizationEventDetail] =
    eventType match {
      case ADD_ORGANIZATION_USER => AddOrganizationUser.decoder.widen
      case ADD_EXTERNAL_ORGANIZATION_USER =>
        AddExternalOrganizationUser.decoder.widen
      case REMOVE_ORGANIZATION_USER => RemoveOrganizationUser.decoder.widen
      case UPDATE_ORGANIZATION_PERMISSION =>
        UpdateOrganizationPermission.decoder.widen
      case ADD_TEAM_USER => AddTeamUser.decoder.widen
      case REMOVE_TEAM_USER => RemoveTeamUser.decoder.widen
      case CREATE_TEAM => CreateTeam.decoder.widen
      case UPDATE_TEAM => UpdateTeam.decoder.widen
      case DELETE_TEAM => DeleteTeam.decoder.widen
      case ADDED_TO_DATASET => AddedToDataset.decoder.widen
      case REMOVED_FROM_DATASET => RemovedFromDataset.decoder.widen
      case DATASET_OWNERSHIP_TRANSFERRED =>
        DatasetOwnershipTransferred.decoder.widen
    }

  // Part 1: organization/team administration

  case class AddOrganizationUser(userId: Int, permission: DBPermission)
      extends OrganizationEventDetail {
    val eventType = ADD_ORGANIZATION_USER
  }

  object AddOrganizationUser {
    implicit val encoder: Encoder[AddOrganizationUser] =
      deriveEncoder[AddOrganizationUser]
    implicit val decoder: Decoder[AddOrganizationUser] =
      deriveDecoder[AddOrganizationUser]
  }

  case class AddExternalOrganizationUser(userId: Int, permission: DBPermission)
      extends OrganizationEventDetail {
    val eventType = ADD_EXTERNAL_ORGANIZATION_USER
  }

  object AddExternalOrganizationUser {
    implicit val encoder: Encoder[AddExternalOrganizationUser] =
      deriveEncoder[AddExternalOrganizationUser]
    implicit val decoder: Decoder[AddExternalOrganizationUser] =
      deriveDecoder[AddExternalOrganizationUser]
  }

  case class RemoveOrganizationUser(userId: Int)
      extends OrganizationEventDetail {
    val eventType = REMOVE_ORGANIZATION_USER
  }

  object RemoveOrganizationUser {
    implicit val encoder: Encoder[RemoveOrganizationUser] =
      deriveEncoder[RemoveOrganizationUser]
    implicit val decoder: Decoder[RemoveOrganizationUser] =
      deriveDecoder[RemoveOrganizationUser]
  }

  case class UpdateOrganizationPermission(
    userId: Int,
    oldPermission: DBPermission,
    newPermission: DBPermission
  ) extends OrganizationEventDetail {
    val eventType = UPDATE_ORGANIZATION_PERMISSION
  }

  object UpdateOrganizationPermission {
    implicit val encoder: Encoder[UpdateOrganizationPermission] =
      deriveEncoder[UpdateOrganizationPermission]
    implicit val decoder: Decoder[UpdateOrganizationPermission] =
      deriveDecoder[UpdateOrganizationPermission]
  }

  case class AddTeamUser(teamId: Int, teamName: String, userId: Int)
      extends OrganizationEventDetail {
    val eventType = ADD_TEAM_USER
  }

  object AddTeamUser {
    implicit val encoder: Encoder[AddTeamUser] = deriveEncoder[AddTeamUser]
    implicit val decoder: Decoder[AddTeamUser] = deriveDecoder[AddTeamUser]
  }

  case class RemoveTeamUser(teamId: Int, teamName: String, userId: Int)
      extends OrganizationEventDetail {
    val eventType = REMOVE_TEAM_USER
  }

  object RemoveTeamUser {
    implicit val encoder: Encoder[RemoveTeamUser] =
      deriveEncoder[RemoveTeamUser]
    implicit val decoder: Decoder[RemoveTeamUser] =
      deriveDecoder[RemoveTeamUser]
  }

  case class CreateTeam(teamId: Int, teamName: String)
      extends OrganizationEventDetail {
    val eventType = CREATE_TEAM
  }

  object CreateTeam {
    implicit val encoder: Encoder[CreateTeam] = deriveEncoder[CreateTeam]
    implicit val decoder: Decoder[CreateTeam] = deriveDecoder[CreateTeam]
  }

  case class UpdateTeam(teamId: Int, oldName: String, newName: String)
      extends OrganizationEventDetail {
    val eventType = UPDATE_TEAM
  }

  object UpdateTeam {
    implicit val encoder: Encoder[UpdateTeam] = deriveEncoder[UpdateTeam]
    implicit val decoder: Decoder[UpdateTeam] = deriveDecoder[UpdateTeam]
  }

  case class DeleteTeam(teamId: Int, teamName: String)
      extends OrganizationEventDetail {
    val eventType = DELETE_TEAM
  }

  object DeleteTeam {
    implicit val encoder: Encoder[DeleteTeam] = deriveEncoder[DeleteTeam]
    implicit val decoder: Decoder[DeleteTeam] = deriveDecoder[DeleteTeam]
  }

  // Part 2: workspace-scoped notice of a personal dataset grant

  case class AddedToDataset(
    datasetId: Int,
    datasetNodeId: String,
    datasetName: String,
    role: Role
  ) extends OrganizationEventDetail {
    val eventType = ADDED_TO_DATASET
  }

  object AddedToDataset {
    implicit val encoder: Encoder[AddedToDataset] =
      deriveEncoder[AddedToDataset]
    implicit val decoder: Decoder[AddedToDataset] =
      deriveDecoder[AddedToDataset]
  }

  case class RemovedFromDataset(
    datasetId: Int,
    datasetNodeId: String,
    datasetName: String
  ) extends OrganizationEventDetail {
    val eventType = REMOVED_FROM_DATASET
  }

  object RemovedFromDataset {
    implicit val encoder: Encoder[RemovedFromDataset] =
      deriveEncoder[RemovedFromDataset]
    implicit val decoder: Decoder[RemovedFromDataset] =
      deriveDecoder[RemovedFromDataset]
  }

  case class DatasetOwnershipTransferred(
    datasetId: Int,
    datasetNodeId: String,
    datasetName: String,
    previousOwnerUserId: Int
  ) extends OrganizationEventDetail {
    val eventType = DATASET_OWNERSHIP_TRANSFERRED
  }

  object DatasetOwnershipTransferred {
    implicit val encoder: Encoder[DatasetOwnershipTransferred] =
      deriveEncoder[DatasetOwnershipTransferred]
    implicit val decoder: Decoder[DatasetOwnershipTransferred] =
      deriveDecoder[DatasetOwnershipTransferred]
  }
}
