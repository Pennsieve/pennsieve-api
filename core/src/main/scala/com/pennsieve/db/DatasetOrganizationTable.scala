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

package com.pennsieve.db

import com.pennsieve.models.Organization
import com.pennsieve.traits.PostgresProfile.api._

/**
  * Global, indexed map from a dataset's (globally-unique) node id to the id of
  * the organization whose numbered schema owns that dataset. Lives in the shared
  * `pennsieve` schema and is maintained by an AFTER INSERT/DELETE/UPDATE trigger
  * on each `"<org>".datasets` table (see pennsieve-db-migrations), so it is a
  * deterministic, request-independent source for dataset -> org resolution in
  * the authorizer.
  */
final class DatasetOrganizationTable(tag: Tag)
    extends Table[(String, Int)](tag, Some("pennsieve"), "dataset_organization") {

  def datasetNodeId = column[String]("dataset_node_id", O.PrimaryKey)
  def organizationId = column[Int]("organization_id")

  def * = (datasetNodeId, organizationId)
}

object DatasetOrganizationMapper
    extends TableQuery(new DatasetOrganizationTable(_)) {

  /**
    * Resolve the owning organization for a dataset node id in a single query, by
    * joining the map to the organizations table. Returns None when no dataset
    * with that node id exists (node ids are globally unique and the map is
    * trigger-maintained, so a miss means the dataset does not exist). The
    * `organization_id` FK guarantees a map row always references a live
    * organization, so a present map row always yields its organization.
    */
  def getOrganization(datasetNodeId: String): DBIO[Option[Organization]] =
    this
      .filter(_.datasetNodeId === datasetNodeId)
      .join(OrganizationsMapper)
      .on(_.organizationId === _.id)
      .map { case (_, organization) => organization }
      .result
      .headOption
}
