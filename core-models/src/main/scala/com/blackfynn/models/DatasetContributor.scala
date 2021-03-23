// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.models

import java.time.ZonedDateTime

case class DatasetContributor(
  datasetId: Int,
  contributorId: Int,
  order: Int,
  createdAt: ZonedDateTime = ZonedDateTime.now,
  updatedAt: ZonedDateTime = ZonedDateTime.now
)
