// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.models

import java.time.ZonedDateTime

final case class OrganizationTeam(
  organizationId: Int,
  teamId: Int,
  permission: DBPermission,
  systemTeamType: Option[SystemTeamType] = None,
  createdAt: ZonedDateTime = ZonedDateTime.now(),
  updatedAt: ZonedDateTime = ZonedDateTime.now()
)
