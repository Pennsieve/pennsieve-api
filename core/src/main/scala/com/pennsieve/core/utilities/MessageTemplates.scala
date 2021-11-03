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

package com.pennsieve.core.utilities

import cats.data._
import cats.implicits._
import com.pennsieve.domain.{ CoreError, ThrowableError }
import com.pennsieve.models.{ Dataset, Organization, User }

import com.pennsieve.templates.GeneratedMessageTemplates

import com.pennsieve.aws.email._

class MessageTemplates(
  host: String,
  discoverHost: String,
  val supportEmail: Email
) {

  // TODO: move to Cognito and remove. This email is now unused
  def newAccount(
    emailAddress: String,
    orgId: String,
    newUserToken: String
  ): String =
    GeneratedMessageTemplates.newAccountCreation(
      host = host,
      emailAddress = emailAddress,
      organizationNodeId = orgId,
      newUserToken = newUserToken
    )

  def addedToOrganization(
    emailAddress: String,
    administrator: String,
    org: Organization
  ): String =
    GeneratedMessageTemplates.addedToOrganization(
      host = host,
      emailAddress = emailAddress,
      organizationName = org.name,
      organizationNodeId = org.nodeId,
      administrator = administrator
    )

  def addedToTeam(
    emailAddress: String,
    administrator: String,
    team: String,
    org: Organization
  ): String =
    GeneratedMessageTemplates.addedToTeam(
      host = host,
      emailAddress = emailAddress,
      teamName = team,
      organizationNodeId = org.nodeId,
      administrator = administrator
    )

  def passwordReset(emailAddress: String, token: String): String =
    GeneratedMessageTemplates.passwordReset(
      host = host,
      token = token,
      emailAddress = emailAddress
    )

  def datasetOwnerChangedNotification(
    emailAddress: String,
    previousOwner: User,
    dataset: Dataset,
    org: Organization
  ): String =
    GeneratedMessageTemplates.changeOfDatasetOwner(
      host = host,
      emailAddress = emailAddress,
      datasetName = dataset.name,
      datasetNodeId = dataset.nodeId,
      organizationName = org.name,
      organizationNodeId = org.nodeId,
      previousOwnerName = previousOwner.fullName
    )

  def datasetContributorPublicationNotification(
    owner: User,
    emailAddress: String,
    dataset: Dataset,
    discoverDatasetId: Int
  ): String =
    GeneratedMessageTemplates.notificationOfPublicationToContributor(
      ownerName = owner.fullName,
      discoverHost = discoverHost,
      datasetName = dataset.name,
      discoverDatasetId = discoverDatasetId.toString,
      emailAddress = emailAddress
    )

  def datasetRevisionNeeded(
    dataset: Dataset,
    reviewer: User,
    date: String,
    emailAddress: String,
    org: Organization,
    message: String
  ): String =
    GeneratedMessageTemplates.datasetRevisionNeeded(
      host = host,
      datasetNodeId = dataset.nodeId,
      datasetName = dataset.name,
      reviewerName = reviewer.fullName,
      date = date,
      emailAddress = emailAddress,
      organizationNodeId = org.nodeId,
      message = message
    )

  def datasetAcceptedForPublication(
    dataset: Dataset,
    reviewer: User,
    date: String,
    emailAddress: String
  ): String =
    GeneratedMessageTemplates.datasetPublicationAccepted(
      datasetName = dataset.name,
      reviewerName = reviewer.fullName,
      date = date,
      emailAddress = emailAddress
    )

  def datasetEmbargoed(
    dataset: Dataset,
    date: String,
    embargoDate: String,
    emailAddress: String
  ): String =
    GeneratedMessageTemplates.datasetEmbargoAccepted(
      datasetName = dataset.name,
      embargoDate = embargoDate,
      emailAddress = emailAddress
    )

  def datasetSubmittedForPublication(
    dataset: Dataset,
    org: Organization,
    owner: User,
    date: String,
    emailAddress: String
  ): String =
    GeneratedMessageTemplates.datasetPublicationInReview(
      host = host,
      datasetName = dataset.name,
      datasetNodeId = dataset.nodeId,
      ownerName = owner.fullName,
      organizationNodeId = org.nodeId,
      date = date,
      emailAddress = emailAddress
    )

  def datasetRevisionNotification(
    emailAddress: String,
    discoverDatasetId: Option[Int]
  ): String =
    GeneratedMessageTemplates.datasetRevision(
      emailAddress = emailAddress,
      discoverHost = discoverHost,
      discoverDatasetId = discoverDatasetId.getOrElse(0).toString
    )

  def embargoedDatasetReleased(
    dataset: Dataset,
    emailAddress: String,
    discoverDatasetId: Int
  ): String =
    GeneratedMessageTemplates.embargoedDatasetReleased(
      discoverHost = discoverHost,
      discoverDatasetId = discoverDatasetId.toString,
      datasetName = dataset.name,
      emailAddress = emailAddress
    )

  def embargoedDatasetReleaseAccepted(
    dataset: Dataset,
    date: String,
    reviewer: User,
    emailAddress: String
  ): String =
    GeneratedMessageTemplates.embargoDatasetReleaseAccepted(
      datasetName = dataset.name,
      reviewerName = reviewer.fullName,
      date = date,
      emailAddress = emailAddress
    )

  def embargoAccessRequested(
    dataset: Dataset,
    user: User,
    org: Organization,
    date: String,
    emailAddress: String
  ): String =
    GeneratedMessageTemplates.embargoAccessRequested(
      host = host,
      datasetName = dataset.name,
      datasetNodeId = dataset.nodeId,
      organizationNodeId = org.nodeId,
      userName = user.fullName,
      date = date,
      emailAddress = emailAddress
    )

  def embargoAccessApproved(
    dataset: Dataset,
    discoverDatasetId: Int,
    manager: User,
    org: Organization,
    date: String,
    emailAddress: String
  ): String =
    GeneratedMessageTemplates.embargoAccessApproved(
      discoverHost = discoverHost,
      datasetName = dataset.name,
      discoverDatasetId = discoverDatasetId.toString,
      date = date,
      managerName = manager.fullName,
      emailAddress = emailAddress
    )

  def embargoAccessDenied(
    dataset: Dataset,
    manager: User,
    org: Organization,
    date: String,
    emailAddress: String
  ): String =
    GeneratedMessageTemplates.embargoAccessDenied(
      datasetName = dataset.name,
      date = date,
      managerName = manager.fullName,
      emailAddress = emailAddress
    )
}
