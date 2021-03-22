package com.blackfynn.jobs.contexts

import com.blackfynn.audit.middleware.TraceId
import com.blackfynn.service.utilities.LogContext

final case class PackageDeleteContext(
  organizationId: Int,
  userId: String,
  traceId: TraceId,
  packageId: Option[Int] = None
) extends LogContext {
  override val values: Map[String, String] = inferValues(this)
}
