// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.models

import java.time.ZonedDateTime

final case class TeamUser(
  teamId: Int,
  userId: Int,
  permission: DBPermission,
  createdAt: ZonedDateTime = ZonedDateTime.now,
  updatedAt: ZonedDateTime = ZonedDateTime.now
)
