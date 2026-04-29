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

import com.pennsieve.db.{ ChangelogEventMapper, DatasetPublicationStatusMapper }
import com.pennsieve.managers.DatasetPublicationStatusManager
import com.pennsieve.models.{ Organization, User }
import com.pennsieve.traits.PostgresProfile.api.Database

/** Skeleton fake. */
class FakeDatasetPublicationStatusManager(
  val state: InMemoryState,
  organization: Organization,
  val actor: User
) extends DatasetPublicationStatusManager {

  def db: Database =
    sys.error(
      "FakeDatasetPublicationStatusManager: a method not yet stubbed by " +
        "your test tried to use the database. Override the method on this fake."
    )

  // Slick mappers; only touch db on `.run(...)`.
  override lazy val datasetPublicationStatusMapper
    : DatasetPublicationStatusMapper =
    new DatasetPublicationStatusMapper(organization)
  override lazy val changelogEventMapper: ChangelogEventMapper =
    new ChangelogEventMapper(organization)
}
