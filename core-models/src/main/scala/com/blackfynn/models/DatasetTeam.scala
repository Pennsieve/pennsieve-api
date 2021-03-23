// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.models

import java.time.ZonedDateTime

case class DatasetTeam(
  datasetId: Int,
  teamId: Int,
  permission: DBPermission,
  role: Option[Role],
  createdAt: ZonedDateTime = ZonedDateTime.now,
  updatedAt: ZonedDateTime = ZonedDateTime.now
)
