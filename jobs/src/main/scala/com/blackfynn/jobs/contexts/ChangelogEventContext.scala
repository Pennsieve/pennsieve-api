package com.blackfynn.jobs.contexts

import com.blackfynn.audit.middleware.TraceId
import com.blackfynn.service.utilities.LogContext

final case class ChangelogEventContext(
  organizationId: Int,
  userId: String,
  traceId: TraceId,
  datasetId: Int
) extends LogContext {
  override val values: Map[String, String] = inferValues(this)
}
