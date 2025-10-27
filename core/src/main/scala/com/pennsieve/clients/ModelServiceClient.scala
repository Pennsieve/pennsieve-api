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

package com.pennsieve.clients

import cats.Monoid
import com.pennsieve.domain.CoreError
import com.pennsieve.dtos.ConceptDTO
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.syntax._
import io.circe.{ Decoder, Encoder }
import org.apache.http.client.HttpClient

case class DatasetDeletionCounts(
  models: Int = 0,
  properties: Int = 0,
  records: Int = 0,
  packages: Int = 0,
  relationshipStubs: Int = 0
) {
  def +(that: DatasetDeletionCounts): DatasetDeletionCounts = {
    new DatasetDeletionCounts(
      models = this.models + that.models,
      properties = this.properties + that.properties,
      records = this.records + that.records,
      packages = this.packages + that.packages,
      relationshipStubs = this.relationshipStubs + that.relationshipStubs
    )
  }
}

object DatasetDeletionCounts {
  implicit val encoder: Encoder[DatasetDeletionCounts] =
    deriveEncoder[DatasetDeletionCounts]
  implicit val decoder: Decoder[DatasetDeletionCounts] =
    deriveDecoder[DatasetDeletionCounts]

  def empty: DatasetDeletionCounts = DatasetDeletionCounts()
}

case class DatasetDeletionSummary(done: Boolean, counts: DatasetDeletionCounts)

object DatasetDeletionSummary {
  implicit val encoder: Encoder[DatasetDeletionSummary] =
    deriveEncoder[DatasetDeletionSummary]
  implicit val decoder: Decoder[DatasetDeletionSummary] =
    deriveDecoder[DatasetDeletionSummary]

  def empty: DatasetDeletionSummary =
    DatasetDeletionSummary(done = false, counts = DatasetDeletionCounts.empty)

  def done: DatasetDeletionSummary = empty.copy(done = true)
}

trait ModelServiceV1Client {

  def allConcepts[B: ToBearer](
    token: B,
    datasetId: String
  ): Either[CoreError, List[ConceptDTO]]

  def getModelStats[B: ToBearer](
    token: B,
    datasetId: String
  ): Either[CoreError, Map[String, Int]]
}

trait ModelServiceV2Client {
  def deleteDataset[B: ToBearer](
    token: B,
    organizationId: Int,
    datasetId: Int
  ): Either[CoreError, DatasetDeletionSummary]
}

class ModelServiceClient(client: HttpClient, host: String, port: Int)
    extends BaseServiceClient(client)
    with ModelServiceV1Client
    with ModelServiceV2Client {

  override def allConcepts[B: ToBearer](
    token: B,
    datasetId: String
  ): Either[CoreError, List[ConceptDTO]] =
    get[B, List[ConceptDTO]](token, s"$host:$port/datasets/$datasetId/concepts")

  override def getModelStats[B: ToBearer](
    token: B,
    datasetId: String
  ): Either[CoreError, Map[String, Int]] =
    allConcepts(token, datasetId).map { concepts =>
      concepts.map { concept =>
        concept.name -> concept.count
      }.toMap
    }

  override def deleteDataset[B: ToBearer](
    token: B,
    organizationId: Int,
    datasetId: Int
  ): Either[CoreError, DatasetDeletionSummary] =
    delete[B, DatasetDeletionSummary](
      token,
      s"$host:$port/internal/organizations/$organizationId/datasets/$datasetId?batchSize=1000&duration=5000"
    )
}
