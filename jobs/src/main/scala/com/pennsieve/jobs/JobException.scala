/*
 * Copyright 2021 University of Pennsylvania
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
