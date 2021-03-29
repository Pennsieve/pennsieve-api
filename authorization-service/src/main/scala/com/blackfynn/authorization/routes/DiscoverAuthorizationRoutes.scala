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

package com.pennsieve.authorization.routes
import akka.http.scaladsl.model.StatusCodes.{ OK, Unauthorized }
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import com.pennsieve.db._
import com.pennsieve.models._
import cats.implicits._
import com.pennsieve.authorization.Router.ResourceContainer
import com.pennsieve.authorization.utilities.exceptions.{
  InvalidDatasetId,
  PreviewNotAllowed
}
import com.pennsieve.authorization.utilities.exceptions._
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.db.{ DatasetsMapper, OrganizationUserMapper, UserMapper }
import com.pennsieve.models.{ Organization, User }
import com.pennsieve.traits.PostgresProfile.api._

import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

object DiscoverAuthorizationRoutes {

  import AuthorizationQueries._

  /**
    * Verify that a user is authorized to preview embargoed datasets on
    * Discover. There are two ways a user can preview embargoed datasets:
    *
    *  1) By preview rights granted via the `dataset_previewer` table
    *
    *  2) By having access to the dataset from within the platform
    *
    * These permissions are checked using this endpoint instead of with a new
    * role type in the JWT because it is not entirely clear how public datasets
    * should be represented - should permissions be tied to the original
    * dataset? Are Discover datasets new entities?
    *
    * We need to revisit this and refactor heavily before building more
    * functionality on top of this authorization endpoint.
    *
    */
  def apply(
    user: User
  )(implicit
    container: ResourceContainer,
    executionContext: ExecutionContext,
    materializer: ActorMaterializer
  ): Route =
    path(
      "authorization" / "organizations" / IntNumber / "datasets" / IntNumber / "discover" / "preview"
    ) { (organizationId, datasetId) =>
      val result = for {
        organization <- getPassedOrganization(organizationId)

        authorized <- canPreviewOnDiscover(organization, user, datasetId)
          .recoverWith {
            case _ =>
              hasInternalDatasetAccess(organization, user, datasetId)
          }
      } yield (authorized)

      onComplete(result) {
        case Success(_) => complete(OK)
        case Failure(exception) => complete(exception.toResponse)
      }
    }

  private def getPassedOrganization(
    organizationId: Int
  )(implicit
    container: ResourceContainer,
    executionContext: ExecutionContext
  ): Future[Organization] = {
    container.db
      .run(OrganizationsMapper.get(organizationId))
      .flatMap {
        case Some(org) => Future.successful((org))
        case None => Future.failed(new OrganizationNotFound(organizationId))
      }
  }

  private def canPreviewOnDiscover(
    organization: Organization,
    user: User,
    datasetId: Int
  )(implicit
    container: ResourceContainer,
    executionContext: ExecutionContext
  ): Future[Unit] = {
    val datasetPreview = new DatasetPreviewerMapper(organization)

    container.db
      .run(datasetPreview.canPreview(datasetId = datasetId, userId = user.id))
      .flatMap {
        case true => Future.successful(())
        case false => Future.failed(new PreviewNotAllowed(user.id, datasetId))
      }
  }

  private def hasInternalDatasetAccess(
    organization: Organization,
    user: User,
    datasetId: Int
  )(implicit
    container: ResourceContainer,
    executionContext: ExecutionContext
  ): Future[Unit] =
    for {
      organizationRole <- getOrganizationRole(user, organization, None)

      datasetRole <- getDatasetRole(user, organization, datasetId.toString)
        .recoverWith {
          case e: InvalidDatasetId =>
            Future.failed(new PreviewNotAllowed(user.id, datasetId))
        }

      _ <- if (datasetRole.role >= Role.Viewer)
        Future.successful(())
      else
        Future.failed(new PreviewNotAllowed(user.id, datasetId))
    } yield ()

}
