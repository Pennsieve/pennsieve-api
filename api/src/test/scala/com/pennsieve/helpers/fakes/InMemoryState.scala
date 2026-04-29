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

import com.pennsieve.db.{ CustomTermsOfService, PennsieveTermsOfService }
import com.pennsieve.models.{
  ChangelogEventAndType,
  Collection,
  Contributor,
  DBPermission,
  DataUseAgreement,
  Dataset,
  DatasetAsset,
  DatasetPreviewer,
  DatasetPublicationStatus,
  DatasetStatus,
  ExternalPublication,
  Feature,
  File,
  Organization,
  OrganizationUser,
  Package,
  Role,
  Team,
  Token,
  User,
  Webhook,
  WebhookEventSubcription
}

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.concurrent.TrieMap

/**
  * Shared in-memory state that fake managers read from and write to.
  *
  * Tests construct one InMemoryState per spec and pass it to every Fake*Manager
  * they wire up — that way fakes can interact (e.g., FakeOrganizationManager
  * sees a user FakeUserManager just created).
  */
class InMemoryState {
  val organizations: TrieMap[Int, Organization] = new TrieMap()
  val users: TrieMap[Int, User] = new TrieMap()
  val orgUsers: TrieMap[(Int, Int), OrganizationUser] = new TrieMap()
  val orgUserPermissions: TrieMap[(Int, Int), DBPermission] = new TrieMap()
  val tokens: TrieMap[String, Token] = new TrieMap()

  // Collections are scoped to an organization; keyed by (orgId, collectionId).
  val collections: TrieMap[(Int, Int), Collection] = new TrieMap()

  // Contributors are scoped to an organization; keyed by (orgId, contributorId).
  val contributors: TrieMap[(Int, Int), Contributor] = new TrieMap()

  // Active feature flags per organization. `(orgId, feature) -> true` means
  // the flag is enabled. Tests configure as needed.
  val featureFlags: TrieMap[(Int, Feature), Boolean] = new TrieMap()

  // Pennsieve ToS acceptance per user.
  val pennsieveTos: TrieMap[Int, PennsieveTermsOfService] = new TrieMap()

  // Custom ToS acceptance per (userId, organizationId).
  val customTos: TrieMap[(Int, Int), CustomTermsOfService] = new TrieMap()

  // ---- Dataset surface (used by TestDataSetsController) ----
  // Everything below is scoped to an organization unless noted.
  val datasets: TrieMap[(Int, Int), Dataset] = new TrieMap()
  val packages: TrieMap[(Int, Int), Package] = new TrieMap()
  val files: TrieMap[(Int, Int), File] = new TrieMap()
  val datasetStatuses: TrieMap[(Int, Int), DatasetStatus] = new TrieMap()
  val datasetPublicationStatuses
    : TrieMap[(Int, Int), DatasetPublicationStatus] = new TrieMap()
  val datasetPreviewers: TrieMap[(Int, Int, Int), DatasetPreviewer] =
    new TrieMap() // (orgId, datasetId, userId)
  val dataUseAgreements: TrieMap[(Int, Int), DataUseAgreement] = new TrieMap()
  val datasetAssets: TrieMap[(Int, Int), DatasetAsset] = new TrieMap()
  val externalPublications: TrieMap[(Int, Int, String), ExternalPublication] =
    new TrieMap() // (orgId, datasetId, doi+rel)
  val webhooks: TrieMap[(Int, Int), Webhook] = new TrieMap()
  val changelogEvents: TrieMap[(Int, Int), ChangelogEventAndType] =
    new TrieMap()

  // Per-org list of default DatasetStatus options that have been seeded.
  val datasetStatusDefaultsSeeded: TrieMap[Int, Boolean] = new TrieMap()

  // Roles linking principals to datasets.
  // (orgId, userId, datasetId) -> Role
  val datasetUserRoles: TrieMap[(Int, Int, Int), Role] = new TrieMap()
  // (orgId, teamId, datasetId) -> Role
  val datasetTeamRoles: TrieMap[(Int, Int, Int), Role] = new TrieMap()
  // (orgId, datasetId) -> Role (the org-wide default role for the dataset)
  val datasetOrgRoles: TrieMap[(Int, Int), Role] = new TrieMap()

  // (orgId, datasetId, contributorId) -> order
  val datasetContributors: TrieMap[(Int, Int, Int), Int] = new TrieMap()
  // (orgId, datasetId, collectionId) -> Unit (membership)
  val datasetCollections: TrieMap[(Int, Int, Int), Unit] = new TrieMap()

  // (orgId, teamId) -> Team
  val teams: TrieMap[(Int, Int), Team] = new TrieMap()

  // (orgId, packageId) -> ExternalFile
  val externalFiles: TrieMap[(Int, Int), com.pennsieve.models.ExternalFile] =
    new TrieMap()

  // (orgId, datasetAssetUuid) -> DatasetAsset (UUID-keyed instead of Int)
  val datasetAssetsByUuid
    : TrieMap[(Int, java.util.UUID), com.pennsieve.models.DatasetAsset] =
    new TrieMap()

  // (orgId, datasetId) -> Seq[DatasetIgnoreFile]
  val datasetIgnoreFiles
    : TrieMap[(Int, Int), Seq[com.pennsieve.models.DatasetIgnoreFile]] =
    new TrieMap()

  // (orgId, datasetId, releaseId) -> DatasetRelease
  val datasetReleases
    : TrieMap[(Int, Int, Int), com.pennsieve.models.DatasetRelease] =
    new TrieMap()

  // (orgId, datasetId) -> ExternalRepository
  val externalRepositories
    : TrieMap[(Int, Int), com.pennsieve.models.ExternalRepository] =
    new TrieMap()

  private val ids = new AtomicInteger(1)
  def newId(): Int = ids.getAndIncrement()
}
