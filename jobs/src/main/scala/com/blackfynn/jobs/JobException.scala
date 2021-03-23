// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.jobs

import com.pennsieve.utilities.AbstractError

sealed trait JobException extends AbstractError

case class JSONParseFailException(json: String, error: String)
    extends JobException {
  final override def getMessage: String =
    s"Failed to parse json:\njson: $json\nerror: $error"
}

case class ExceptionError(exception: Throwable) extends JobException {
  this.initCause(exception)
  final override def getMessage: String = exception.getMessage
}

case class InvalidOrganization(organizationId: Int) extends JobException {
  final override def getMessage: String =
    s"No organization with id ($organizationId) exists"
}

case class InvalidJob(message: String) extends JobException {
  final override def getMessage: String =
    s"No job type with body ($message) exists"
}
