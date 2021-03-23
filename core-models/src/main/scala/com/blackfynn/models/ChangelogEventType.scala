// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.models

import java.time.ZonedDateTime

case class ChangelogEventType(
  name: ChangelogEventName,
  createdAt: ZonedDateTime = ZonedDateTime.now(),
  id: Int = 0
)
