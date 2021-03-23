// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.models

import java.time.ZonedDateTime

final case class Comment(
  discussionId: Int,
  creatorId: Int,
  message: String,
  createdAt: ZonedDateTime = ZonedDateTime.now(),
  updatedAt: ZonedDateTime = ZonedDateTime.now(),
  id: Int = 0
)
