// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.api

abstract class ApiError

final case class Error(message: String) extends ApiError

final case class FatalError(
  message: String = "Something horrible has happened. Please contact support."
) extends ApiError
