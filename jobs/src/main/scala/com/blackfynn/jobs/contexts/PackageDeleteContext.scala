package com.pennsieve.jobs.contexts

import com.pennsieve.audit.middleware.TraceId
import com.pennsieve.service.utilities.LogContext

final case class PackageDeleteContext(
  organizationId: Int,
  userId: String,
  traceId: TraceId,
  packageId: Option[Int] = None
) extends LogContext {
  override val values: Map[String, String] = inferValues(this)
}
