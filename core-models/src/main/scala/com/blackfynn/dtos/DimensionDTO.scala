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

import com.pennsieve.models.{ Dimension, DimensionAssignment, Package }

case class DimensionDTO(
  id: Int,
  name: String,
  length: Long,
  resolution: Option[Double],
  unit: Option[String],
  assignment: DimensionAssignment,
  createdAt: ZonedDateTime = ZonedDateTime.now(),
  packageId: String
)

object DimensionDTO {
  def apply(dimension: Dimension, `package`: Package): DimensionDTO =
    DimensionDTO(
      dimension.id,
      dimension.name,
      dimension.length,
      dimension.resolution,
      dimension.unit,
      dimension.assignment,
      dimension.createdAt,
      `package`.nodeId
    )

  def apply(
    dimensions: List[Dimension],
    `package`: Package
  ): List[DimensionDTO] =
    dimensions.map(DimensionDTO(_, `package`))
}
