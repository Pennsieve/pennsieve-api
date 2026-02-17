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

package com.pennsieve.managers

import cats.data.EitherT
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.db._
import com.pennsieve.domain.CoreError
import com.pennsieve.models.{ Dataset, Organization, User }
import com.pennsieve.traits.PostgresProfile.api._

import scala.concurrent.{ ExecutionContext, Future }

// matches discover-service PublishRequest.modelCount parameter
case class ModelRecordCount(modelName: String, count: Int)

class MetadataManager(
  val db: Database,
  val actor: User,
  val organization: Organization
) {

  val modelsMapper: ModelsMapper = new ModelsMapper(organization)
  val recordsMapper: RecordsMapper = new RecordsMapper(organization)

  /**
    * Get models with their current record counts for a dataset
    */
  def getModelRecordCounts(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[ModelRecordCount]] = {
    val query = recordsMapper
      .filter(r => r.datasetId === dataset.id && r.validTo.isEmpty)
      .join(modelsMapper)
      .on(_.modelId === _.id)
      .groupBy { case (_, model) => model.name }
      .map { case (modelName, records) => (modelName, records.length) }
      .result
      .map(_.map { case (name, count) => ModelRecordCount(name, count) })

    db.run(query).toEitherT
  }
}
