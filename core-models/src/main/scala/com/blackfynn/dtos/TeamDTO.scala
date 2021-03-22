// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.dtos

import com.blackfynn.models.{ OrganizationTeam, SystemTeamType, Team }

case class TeamDTO(
  id: String,
  name: String,
  systemTeamType: Option[SystemTeamType]
)

object TeamDTO {
  def apply(teamOrgTeamTuple: (Team, OrganizationTeam)): TeamDTO = {
    new TeamDTO(
      id = teamOrgTeamTuple._1.nodeId,
      name = teamOrgTeamTuple._1.name,
      systemTeamType = teamOrgTeamTuple._2.systemTeamType
    )
  }
}
