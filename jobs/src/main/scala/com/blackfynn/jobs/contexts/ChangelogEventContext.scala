package com.pennsieve.jobs.contexts

import com.pennsieve.audit.middleware.TraceId
import com.pennsieve.service.utilities.LogContext

final case class ChangelogEventContext(
  organizationId: Int,
  userId: String,
  traceId: TraceId,
  datasetId: Int
) extends LogContext {
  override val values: Map[String, String] = inferValues(this)
}
