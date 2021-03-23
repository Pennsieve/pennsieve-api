// Copyright (c) 2019 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.api

import com.pennsieve.helpers.APIContainers.{
  InsecureAPIContainer,
  SecureContainerBuilderType
}
import com.pennsieve.helpers.either.EitherTErrorHandler.implicits._
import org.scalatra._
import org.scalatra.swagger.Swagger
import scala.concurrent._

/**
  * Base controller for internal, service-only endpoints.
  */
trait InternalAuthenticatedController extends AuthenticatedController {

  before() {
    isServiceClaim(request) match {
      case true => () // OK
      case _ => halt(Forbidden("Internal use only"))
    }
  }
}
