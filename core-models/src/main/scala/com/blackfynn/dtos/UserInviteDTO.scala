// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.dtos

import java.time.ZonedDateTime

import com.pennsieve.models.{ DBPermission, UserInvite }

case class UserInviteDTO(
  id: String,
  email: String,
  firstName: String,
  lastName: String,
  permission: DBPermission,
  validUntil: ZonedDateTime
)

object UserInviteDTO {
  def apply(userInvite: UserInvite): UserInviteDTO = {
    new UserInviteDTO(
      id = userInvite.nodeId,
      email = userInvite.email,
      firstName = userInvite.firstName,
      lastName = userInvite.lastName,
      permission = userInvite.permission,
      validUntil = userInvite.validUntil
    )
  }
}
