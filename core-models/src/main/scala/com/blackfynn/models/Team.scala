// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.models

import java.time.ZonedDateTime

final case class Team(
  nodeId: String,
  name: String,
  createdAt: ZonedDateTime = ZonedDateTime.now(),
  updatedAt: ZonedDateTime = ZonedDateTime.now(),
  id: Int = 0
)
