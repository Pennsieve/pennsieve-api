package com.pennsieve.models

import java.time.ZonedDateTime
import com.pennsieve.dtos.ExternalFileDTO

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
