package com.pennsieve.dtos

import java.time.ZonedDateTime

import com.pennsieve.models.{ Annotation, ModelProperty, PathElement }

case class AnnotationDTO(
  userId: String,
  layer_id: Int,
  description: String,
  path: List[PathElement],
  attributes: List[ModelProperty],
  createdAt: ZonedDateTime,
  updatedAt: ZonedDateTime,
  id: Int
)

object AnnotationDTO {
  def apply(annotation: Annotation, creatorId: String): AnnotationDTO = {
    AnnotationDTO(
      creatorId,
      annotation.layerId,
      annotation.description,
      annotation.path,
      annotation.attributes,
      annotation.createdAt,
      annotation.updatedAt,
      annotation.id
    )
  }

}
