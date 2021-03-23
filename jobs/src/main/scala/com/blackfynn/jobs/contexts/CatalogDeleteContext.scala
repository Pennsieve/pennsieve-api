package com.pennsieve.jobs.contexts

import com.pennsieve.service.utilities.LogContext

final case class CatalogDeleteContext(organizationId: Int, userId: String)
    extends LogContext {
  override val values: Map[String, String] = inferValues(this)
}
