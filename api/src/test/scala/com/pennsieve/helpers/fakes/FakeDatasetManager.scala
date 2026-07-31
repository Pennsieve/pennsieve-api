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

import com.pennsieve.db.DatasetsMapper
import com.pennsieve.managers.DatasetManager
import com.pennsieve.models.{ Organization, User }
import com.pennsieve.traits.PostgresProfile.api.Database

/**
  * Skeleton fake for `DatasetManager`. Provides abstract trait members
  * (db throws, datasetsMapper lazily constructs from organization) so the
  * cake DI in `BaseApiUnitTest` can resolve. Specific tests should subclass
  * or amend this fake to override the methods they exercise; any unstubbed
  * method will fall through to the trait body, hit `db`, and fail loudly.
  */
class FakeDatasetManager(
  val state: InMemoryState,
  org: Organization,
  val actor: User
) extends DatasetManager {

  def db: Database =
    sys.error(
      "FakeDatasetManager: a method not yet stubbed by your test tried to " +
        "use the database. Override the method on this fake."
    )

  // The Slick TableQuery itself doesn't touch `db` at construction; only on
  // `db.run(...)`. So we can supply a real one without breaking the
  // throwing-`db` invariant. The trait derives `organization` from this.
  override lazy val datasetsMapper: DatasetsMapper =
    new DatasetsMapper(org)
}
