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

package com.pennsieve.api

import cats.data._
import cats.implicits._
import com.pennsieve.helpers.ResultHandlers._
import com.pennsieve.helpers.either.EitherErrorHandler.implicits._
import com.pennsieve.helpers.either.EitherTErrorHandler.implicits._
import com.pennsieve.helpers.either.EitherThrowableErrorConverter.implicits._
import com.pennsieve.domain.{ CoreError, OperationNoLongerSupported }
import com.pennsieve.helpers.APIContainers.{
  InsecureAPIContainer,
  SecureContainerBuilderType
}
import com.pennsieve.helpers.ResultHandlers.OkResult
import com.pennsieve.models.User
import org.scalatra.swagger.Swagger
import org.scalatra.{ ActionResult, AsyncResult, ScalatraServlet }

import scala.concurrent.{ ExecutionContext, Future }

class SecurityController(
  val insecureContainer: InsecureAPIContainer,
  val secureContainerBuilder: SecureContainerBuilderType,
  asyncExecutor: ExecutionContext
)(implicit
  val swagger: Swagger
) extends ScalatraServlet
    with AuthenticatedController {

  override val swaggerTag = "Security"

  protected implicit def executor: ExecutionContext = asyncExecutor

  get("/ping") {
    // this "hidden" endpoint exists to check if a given session
    // is alive. since this is a base authenticated controller,
    // if the session is no longer alive, a 401 will be thrown.
    // if a valid session, the execution thread is permitted
    // through to this handler and returns a 200 response.
    // since we do not invoke getUser on the base class like
    // every other controller, we do not refresh the TTL on
    // the session ID.
  }

  val getTemporaryUploadCredentials = (apiOperation[Unit](
    "getTemporaryUploadCredentials"
  )
    summary "(DEPRECATED) gets temporary credentials for a users folder in the s3 bucket"
    parameters (
      pathParam[String]("dataset")
    ))

  get(
    "/user/credentials/upload/:dataset",
    operation(getTemporaryUploadCredentials)
  ) {

    new AsyncResult {
      val results: EitherT[Future, ActionResult, Unit] = {
        EitherT
          .leftT[Future, Unit](OperationNoLongerSupported: CoreError)
          .coreErrorToActionResult()
      }
      override val is = results.value.map(OkResult)
    }
  }
}
