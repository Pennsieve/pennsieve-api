// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.authorization.routes
import akka.http.scaladsl.model.StatusCodes.{ OK, Unauthorized }
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import com.blackfynn.db._
import com.blackfynn.models._
import cats.implicits._
import com.blackfynn.authorization.Router.ResourceContainer
import com.blackfynn.authorization.utilities.exceptions.{
  InvalidDatasetId,
  PreviewNotAllowed
}
import com.blackfynn.authorization.utilities.exceptions._
import com.blackfynn.core.utilities.FutureEitherHelpers.implicits._
import com.blackfynn.db.{ DatasetsMapper, OrganizationUserMapper, UserMapper }
import com.blackfynn.models.{ Organization, User }
import com.blackfynn.traits.PostgresProfile.api._

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
