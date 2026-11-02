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

import com.pennsieve.email.client.{ EmailRequest, Messages, Recipient }
import com.pennsieve.models.{ Dataset, Organization, User }

/**
  * Builds the email-service request for each email the API sends. The request
  * names the template (messageId) and carries the values to fill into it; the
  * email-service owns the template, subject, and delivery. The template
  * variables are defined in the email-templates repo.
  */
class MessageTemplates(host: String, discoverHost: String) {

  // The recipient's name is not used by the email-service, only the address.
  private def to(emailAddress: String): Recipient = Recipient("", emailAddress)

  def addedToOrganization(
    emailAddress: String,
    administrator: String,
    org: Organization,
    customMessage: String
  ): EmailRequest =
    Messages.addedToOrganization(
      to = to(emailAddress),
      host = host,
      organizationName = org.name,
      organizationNodeId = org.nodeId,
      administrator = administrator,
      customMessage = customMessage
    )

  def addedToTeam(
    emailAddress: String,
    administrator: String,
    team: String,
    org: Organization
  ): EmailRequest =
    Messages.addedToTeam(
      to = to(emailAddress),
      host = host,
      teamName = team,
      organizationNodeId = org.nodeId,
      administrator = administrator
    )

  def datasetOwnerChangedNotification(
    emailAddress: String,
    previousOwner: User,
    dataset: Dataset,
    org: Organization
  ): EmailRequest =
    Messages.changeOfDatasetOwner(
      to = to(emailAddress),
      host = host,
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
  ): EmailRequest =
    Messages.notificationOfPublicationToContributor(
      to = to(emailAddress),
      ownerName = owner.fullName,
      discoverHost = discoverHost,
      datasetName = dataset.name,
      discoverDatasetId = discoverDatasetId.toString
    )

  def datasetRevisionNeeded(
    dataset: Dataset,
    reviewer: User,
    date: String,
    emailAddress: String,
    org: Organization,
    message: String
  ): EmailRequest =
    Messages.datasetRevisionNeeded(
      to = to(emailAddress),
      host = host,
      datasetNodeId = dataset.nodeId,
      datasetName = dataset.name,
      reviewerName = reviewer.fullName,
      date = date,
      organizationNodeId = org.nodeId,
      message = message
    )

  def datasetAcceptedForPublication(
    dataset: Dataset,
    reviewer: User,
    date: String,
    emailAddress: String
  ): EmailRequest =
    Messages.datasetPublicationAccepted(
      to = to(emailAddress),
      datasetName = dataset.name,
      reviewerName = reviewer.fullName,
      date = date
    )

  def datasetEmbargoed(
    dataset: Dataset,
    date: String,
    embargoDate: String,
    emailAddress: String
  ): EmailRequest =
    Messages.datasetEmbargoAccepted(
      to = to(emailAddress),
      datasetName = dataset.name,
      embargoDate = embargoDate
    )

  def datasetSubmittedForPublication(
    dataset: Dataset,
    org: Organization,
    owner: User,
    date: String,
    emailAddress: String
  ): EmailRequest =
    Messages.datasetPublicationInReview(
      to = to(emailAddress),
      host = host,
      datasetName = dataset.name,
      datasetNodeId = dataset.nodeId,
      ownerName = owner.fullName,
      organizationNodeId = org.nodeId,
      date = date
    )

  def datasetRevisionNotification(
    emailAddress: String,
    discoverDatasetId: Option[Int],
    org: Organization,
    dataset: Dataset
  ): EmailRequest =
    Messages.datasetRevision(
      to = to(emailAddress),
      host = host,
      discoverHost = discoverHost,
      discoverDatasetId = discoverDatasetId.getOrElse(0).toString,
      organizationNodeId = org.nodeId,
      datasetNodeId = dataset.nodeId
    )

  def embargoedDatasetReleased(
    dataset: Dataset,
    emailAddress: String,
    discoverDatasetId: Int
  ): EmailRequest =
    Messages.embargoedDatasetReleased(
      to = to(emailAddress),
      discoverHost = discoverHost,
      discoverDatasetId = discoverDatasetId.toString,
      datasetName = dataset.name
    )

  def embargoedDatasetReleaseAccepted(
    dataset: Dataset,
    date: String,
    reviewer: User,
    emailAddress: String
  ): EmailRequest =
    Messages.embargoDatasetReleaseAccepted(
      to = to(emailAddress),
      datasetName = dataset.name,
      reviewerName = reviewer.fullName,
      date = date
    )

  def embargoAccessRequested(
    dataset: Dataset,
    user: User,
    org: Organization,
    date: String,
    emailAddress: String
  ): EmailRequest =
    Messages.embargoAccessRequested(
      to = to(emailAddress),
      host = host,
      datasetName = dataset.name,
      datasetNodeId = dataset.nodeId,
      organizationNodeId = org.nodeId,
      userName = user.fullName,
      date = date
    )

  def embargoAccessApproved(
    dataset: Dataset,
    discoverDatasetId: Int,
    manager: User,
    org: Organization,
    date: String,
    emailAddress: String
  ): EmailRequest =
    Messages.embargoAccessApproved(
      to = to(emailAddress),
      discoverHost = discoverHost,
      datasetName = dataset.name,
      discoverDatasetId = discoverDatasetId.toString,
      date = date,
      managerName = manager.fullName
    )

  def embargoAccessDenied(
    dataset: Dataset,
    manager: User,
    org: Organization,
    date: String,
    emailAddress: String
  ): EmailRequest =
    Messages.embargoAccessDenied(
      to = to(emailAddress),
      datasetName = dataset.name,
      date = date,
      managerName = manager.fullName
    )

  def emailAddressChanged(
    previousEmailAddress: String,
    currentEmailAddress: String,
    transactionNumber: String,
    emailAddress: String
  ): EmailRequest =
    Messages.emailAddressChanged(
      to = to(emailAddress),
      previousEmailAddress = previousEmailAddress,
      currentEmailAddress = currentEmailAddress,
      transactionNumber = transactionNumber
    )

  def inviteExternalExistingUserToDataset(
    emailAddress: String,
    invitingPerson: String,
    invitingEmailAddress: String,
    workspaceName: String,
    datasetName: String,
    customMessage: String
  ): EmailRequest =
    Messages.inviteExternalExistingUserToDataset(
      to = to(emailAddress),
      invitingPerson = invitingPerson,
      invitingEmailAddress = invitingEmailAddress,
      workspaceName = workspaceName,
      datasetName = datasetName,
      customMessage = customMessage
    )

  def datasetSubmittedForReview(
    organizationName: String,
    organizationNodeId: String,
    datasetName: String,
    datasetNodeId: String,
    ownerName: String,
    ownerEmailAddress: String,
    emailAddress: String,
    date: String
  ): EmailRequest =
    Messages.datasetSubmittedForReview(
      to = to(emailAddress),
      host = host,
      organizationName = organizationName,
      organizationNodeId = organizationNodeId,
      datasetName = datasetName,
      datasetNodeId = datasetNodeId,
      ownerName = ownerName,
      ownerEmailAddress = ownerEmailAddress,
      date = date
    )
}
