// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.models

import java.time.ZonedDateTime

final case class Discussion(
  packageId: Int,
  annotationId: Option[Int],
  timeSeriesAnnotationId: Option[Int],
  createdAt: ZonedDateTime = ZonedDateTime.now(),
  updatedAt: ZonedDateTime = ZonedDateTime.now(),
  id: Int = 0
)
