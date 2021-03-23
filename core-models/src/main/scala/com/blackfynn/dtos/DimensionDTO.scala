// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

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
