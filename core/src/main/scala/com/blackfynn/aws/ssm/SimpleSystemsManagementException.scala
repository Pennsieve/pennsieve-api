// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.aws.ssm

import com.blackfynn.utilities.AbstractError

sealed trait SimpleSystemsManagementException extends AbstractError

case class InvalidParameters(invalidParameters: List[String])
    extends SimpleSystemsManagementException {
  val invalid: String = invalidParameters.mkString(", ")

  final override def getMessage: String =
    s"Invalid parameters requested from SimpleSystemsManagement: $invalid"
}

case class SimpleSystemsManagementServiceException(exception: Exception)
    extends SimpleSystemsManagementException {
  this.initCause(exception)
}

case class InvalidParameterResponse(parameter: String)
    extends SimpleSystemsManagementException {
  final override def getMessage: String =
    s"Non-requested parameter returned from SimpleSystemsManagementException: $parameter"
}
