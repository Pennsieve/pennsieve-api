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

import com.pennsieve.managers.DatasetStatusManager
import com.pennsieve.models.Organization
import com.pennsieve.traits.PostgresProfile.api.Database

/** Skeleton fake. */
class FakeDatasetStatusManager(
  val state: InMemoryState,
  override val organization: Organization
) extends DatasetStatusManager {

  def db: Database =
    sys.error(
      "FakeDatasetStatusManager: a method not yet stubbed by your test tried " +
        "to use the database. Override the method on this fake."
    )
}
