package com.blackfynn.jobs.contexts

import com.blackfynn.service.utilities.LogContext

final case class StorageCacheContext(
  organizationId: Option[Int],
  packageId: Option[Int] = None,
  fileId: Option[Int] = None
) extends LogContext {
  override val values: Map[String, String] = inferValues(this)
}

object StorageCacheContext {
  def apply(organizationId: Int): StorageCacheContext =
    StorageCacheContext(Some(organizationId))
}
