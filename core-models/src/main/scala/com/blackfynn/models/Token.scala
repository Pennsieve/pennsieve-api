package com.pennsieve.models

import java.time.ZonedDateTime

final case class Token(
  name: String,
  token: String,
  secret: String,
  organizationId: Int,
  userId: Int,
  lastUsed: Option[ZonedDateTime] = None,
  createdAt: ZonedDateTime = ZonedDateTime.now(),
  id: Int = 0
)
