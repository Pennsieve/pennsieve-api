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

import com.pennsieve.models.{ DatasetStatus, DatasetStatusInUse }

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

case class DatasetStatusDTO(
  id: Int,
  name: String,
  displayName: String,
  color: String,
  inUse: DatasetStatusInUse
)

object DatasetStatusDTO {
  def apply(
    status: DatasetStatus,
    inUse: DatasetStatusInUse
  ): DatasetStatusDTO =
    DatasetStatusDTO(
      id = status.id,
      name = status.name,
      displayName = status.displayName,
      color = status.color,
      inUse = inUse
    )

  def apply(
    statusAndUsage: (DatasetStatus, DatasetStatusInUse)
  ): DatasetStatusDTO =
    DatasetStatusDTO(statusAndUsage._1, statusAndUsage._2)

  implicit val encoder: Encoder[DatasetStatusDTO] =
    deriveEncoder[DatasetStatusDTO]
  implicit val decoder: Decoder[DatasetStatusDTO] =
    deriveDecoder[DatasetStatusDTO]
}
