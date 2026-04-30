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

import cats.data.EitherT
import com.pennsieve.domain.CoreError
import com.pennsieve.managers.{ MetadataManager, ModelRecordCount }
import com.pennsieve.models.{ Dataset, Organization, User }
import com.pennsieve.traits.PostgresProfile.api.Database

import scala.concurrent.{ ExecutionContext, Future }

/**
  * Stub `MetadataManager` — never queries the model/record tables. Returns
  * an empty Seq for `getModelRecordCounts`. Tests don't currently exercise
  * the model/record surface; this just keeps the publish flow happy.
  */
class FakeMetadataManager(db: Database, actor: User, org: Organization)
    extends MetadataManager(db, actor, org) {
  override def getModelRecordCounts(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[ModelRecordCount]] =
    EitherT.rightT(Seq.empty)
}
