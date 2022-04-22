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

package com.pennsieve.dtos

import java.time.ZonedDateTime

import com.pennsieve.models.{
  Dataset,
  DatasetState,
  DatasetStatus,
  DatasetType,
  DefaultDatasetStatus,
  License
}
import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

case class WrappedDataset(
  id: String,
  name: String,
  description: Option[String],
  state: DatasetState = DatasetState.READY,
  createdAt: ZonedDateTime,
  updatedAt: ZonedDateTime,
  packageType: String = "DataSet", // DO NOT SET: For backwards compatibility with the frontend (Polymer v.1) application
  datasetType: DatasetType, // Do not change the name of this property. If you change it to something like `type` it'll break the python client
  status: String,
  automaticallyProcessPackages: Boolean = false,
  license: Option[License],
  tags: List[String] = List.empty,
  dataUseAgreementId: Option[Int] = None,
  intId: Int
)

object WrappedDataset {
  def apply(dataset: Dataset, status: DatasetStatus): WrappedDataset = {
    WrappedDataset(
      id = dataset.nodeId,
      name = dataset.name,
      description = dataset.description,
      state = dataset.state,
      createdAt = dataset.createdAt,
      updatedAt = dataset.updatedAt,
      datasetType = dataset.`type`,
      status = status.name,
      automaticallyProcessPackages = dataset.automaticallyProcessPackages,
      license = dataset.license,
      tags = dataset.tags,
      dataUseAgreementId = dataset.dataUseAgreementId,
      intId = dataset.id
    )
  }

  implicit val encoder: Encoder[WrappedDataset] = deriveEncoder[WrappedDataset]
  implicit val decoder: Decoder[WrappedDataset] = deriveDecoder[WrappedDataset]

}
