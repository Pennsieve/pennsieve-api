package com.pennsieve.dtos

case class CollaboratorsDTO(
  users: List[UserDTO],
  organizations: List[OrganizationDTO],
  teams: List[TeamDTO]
)
