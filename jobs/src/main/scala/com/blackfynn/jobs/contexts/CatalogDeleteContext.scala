package com.blackfynn.jobs.contexts

import com.blackfynn.service.utilities.LogContext

final case class CatalogDeleteContext(organizationId: Int, userId: String)
    extends LogContext {
  override val values: Map[String, String] = inferValues(this)
}
