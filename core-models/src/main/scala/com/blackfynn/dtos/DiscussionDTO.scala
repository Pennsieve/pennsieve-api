// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.dtos

import java.time.ZonedDateTime

import com.blackfynn.models.Discussion

case class DiscussionDTO(
  package_id: Int,
  annotation_id: Option[Int],
  timeSeries_annotation_id: Option[Int],
  createdAt: ZonedDateTime = ZonedDateTime.now(),
  updatedAt: ZonedDateTime = ZonedDateTime.now(),
  id: Int = 0
)

object DiscussionDTO {
  def apply(discussion: Discussion): DiscussionDTO = {
    new DiscussionDTO(
      discussion.packageId,
      discussion.annotationId,
      discussion.timeSeriesAnnotationId,
      discussion.createdAt,
      discussion.updatedAt,
      discussion.id
    )
  }
}
