package com.blackfynn.models

import java.time.ZonedDateTime
import com.blackfynn.dtos.ExternalFileDTO

final case class ExternalFile(
  packageId: Int,
  location: String,
  description: Option[String] = None,
  createdAt: ZonedDateTime = ZonedDateTime.now(),
  updatedAt: ZonedDateTime = ZonedDateTime.now()
) {
  def toDTO =
    ExternalFileDTO(packageId, location, description, createdAt, updatedAt)
}
