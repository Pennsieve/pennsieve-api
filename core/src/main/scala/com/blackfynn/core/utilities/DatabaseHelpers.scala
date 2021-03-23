package com.pennsieve.core.utilities

import slick.jdbc.meta._

object DatabaseHelpers {

  def getSchema(database: Option[String], schema: String) = {
    MSchema.getSchemas(database, Some(schema)).headOption
  }

}
