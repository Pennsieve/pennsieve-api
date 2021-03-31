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

package com.pennsieve.authorization.utilities

import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCodes.{
  Forbidden,
  InternalServerError,
  NotFound,
  Unauthorized
}
import com.pennsieve.domain.FeatureNotEnabled
import com.pennsieve.models.{ Organization, User }
import com.typesafe.scalalogging.LazyLogging

trait AuthorizationException {

  implicit class ExceptionHttpResponseMapper(exception: Throwable)
      extends LazyLogging {
    def toResponse: HttpResponse = exception match {

      case _: APITokenNotFound =>
        logger.warn(exception.getMessage)
        HttpResponse(Unauthorized, entity = "No such API token exists.")

      case BadPassword =>
        HttpResponse(Forbidden, entity = "Incorrect password supplied.") // 403

      case BadSecret =>
        HttpResponse(Forbidden, entity = "Incorrect secret supplied.") // 403

      case _: FeatureNotEnabled =>
        logger.warn(exception.getMessage)
        HttpResponse(Forbidden, entity = "Forbidden.") // 403

      case InvalidLoginAttemps =>
        logger.warn(exception.getMessage)
        HttpResponse(Forbidden, entity = "Too many invalid login attemps.") // 403

      case _: InvalidSession =>
        logger.warn(exception.getMessage)
        HttpResponse(Unauthorized, entity = "Unauthorized.") // 401

      // The "Invalid*" errors should be "Not Found", but NGINX auth_request only
      // supports 2XX, 401, 403 response codes. NGINX translates all other codes
      // to 500.

      case _: InvalidOrganizationId =>
        logger.warn(exception.getMessage)
        HttpResponse(Unauthorized)

      case _: InvalidDatasetId =>
        logger.warn(exception.getMessage)
        HttpResponse(Unauthorized)

      case _: InvalidWorkspaceId =>
        logger.warn(exception.getMessage)
        HttpResponse(Unauthorized) // 401

      case NonBrowserSession =>
        logger.warn(exception.getMessage)
        HttpResponse(Forbidden)

      case _: OrganizationNotFound =>
        logger.warn(exception.getMessage)
        HttpResponse(Unauthorized, entity = "Organization does not exist.")

      case _: SessionTokenNotFound =>
        logger.warn(exception.getMessage)
        HttpResponse(Unauthorized, entity = "No such session exists.")

      case _: UserNotFound =>
        logger.warn(exception.getMessage)
        HttpResponse(Unauthorized, entity = "User does not exist.")

      case _: PreviewNotAllowed =>
        logger.warn(exception.getMessage)
        HttpResponse(Forbidden, entity = "Preview not allowed.")

      case _ =>
        logger.error("Unexpected exception", exception)
        HttpResponse(InternalServerError, entity = "Internal server error.")
    }
  }

  class APITokenNotFound(token: String) extends Exception {
    override def getMessage: String = s"No such API token exists: $token"
  }

  object BadPassword extends Exception
  object BadSecret extends Exception

  object InvalidLoginAttemps extends Exception {
    override def getMessage: String =
      "User has exceeded the maximum number of login attempts."
  }

  class InvalidSession(user: User, organization: Organization)
      extends Exception {
    override def getMessage: String =
      s"invalid session -- user ${user.id} does not have access to organization ${organization.id}."
  }

  class InvalidOrganizationId(organizationId: String) extends Exception {
    override def getMessage: String =
      s"invalid organization_id $organizationId -- does not match current organization of session."
  }

  class InvalidDatasetId(user: User, datasetId: String) extends Exception {
    override def getMessage: String =
      s"invalid dataset_id $datasetId -- either the dataset does not exist or user ${user.id} does not have permissions to access it."
  }

  class InvalidWorkspaceId(user: User, workspaceId: Int) extends Exception {
    override def getMessage: String =
      s"invalid workspace_id $workspaceId -- either the workspace does not exist or user ${user.id} does not have permissions to access it."
  }

  object NonBrowserSession extends Exception {
    override def getMessage: String =
      s"invalid session -- API sessions are not permitted to switch organizations"
  }

  class OrganizationNotFound(organizationId: Int) extends Exception {
    override def getMessage: String = s"No such organization: $organizationId"
  }

  class SessionTokenNotFound(token: String) extends Exception {
    override def getMessage: String = s"No such session exists: $token"
  }

  class UserNotFound(email: String) extends Exception {
    override def getMessage: String =
      s"User $email does not exist."
  }

  class PreviewNotAllowed(userId: Int, datasetId: Int) extends Exception {
    override def getMessage: String =
      s"User $userId cannot preview dataset $datasetId"
  }
}
