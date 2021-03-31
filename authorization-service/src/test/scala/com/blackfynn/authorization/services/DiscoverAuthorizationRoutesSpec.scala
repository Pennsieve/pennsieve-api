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

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.HttpMethods.{ GET, PUT }
import akka.http.scaladsl.model.StatusCodes.{
  Forbidden,
  NotFound,
  OK,
  Unauthorized
}
import akka.stream._
import akka.testkit.TestKitBase
import cats.data._
import cats.implicits._
import com.pennsieve.akka.http.EitherValue._
import com.pennsieve.db.DatasetsMapper
import com.pennsieve.managers.{
  DatasetManager,
  DatasetPreviewManager,
  UserManager
}
import com.pennsieve.models.{
  DatasetPreviewer,
  EmbargoAccess,
  PackageState,
  Role
}
import com.pennsieve.traits.PostgresProfile.api._

import scala.concurrent.duration._
import scala.concurrent._

class DiscoverAuthorizationRoutesSpec
    extends AuthorizationServiceSpec
    with TestKitBase {

  "GET /authorization/organizations/:id/datasets/:id/discover/preview route" should {

    "return 401 Unauthorized if no credentials are provided" in {
      testRequest(
        GET,
        s"/authorization/organizations/${organizationOne.id}/datasets/999/discover/preview"
      ) ~>
        routes ~> check {
        status shouldEqual Unauthorized
      }
    }

    "return 403 Forbidden if session organization does not match requested organization" in {
      testRequest(
        GET,
        s"/authorization/organizations/${organizationOne.id}/datasets/999/discover/preview",
        session = nonAdminSession
      ) ~>
        routes ~> check {
        status shouldEqual Forbidden
      }
    }

    "return 200 if user owns dataset" in {

      val datasetsMapper = new DatasetsMapper(organizationTwo)
      val datasetManager =
        new DatasetManager(db, nonAdmin, datasetsMapper)
      val dataset = datasetManager
        .create("Test Dataset")
        .await
        .value

      testRequest(
        GET,
        s"/authorization/organizations/${organizationTwo.id}/datasets/${dataset.id}/discover/preview",
        session = nonAdminSession
      ) ~>
        routes ~> check {
        status shouldEqual OK
      }
    }

    "return 403 Forbidden if dataset is not shared with user" in {

      val datasetsMapper = new DatasetsMapper(organizationTwo)
      val datasetManager =
        new DatasetManager(db, admin, datasetsMapper)
      val dataset = datasetManager
        .create("Test Dataset")
        .await
        .value

      testRequest(
        GET,
        s"/authorization/organizations/${organizationTwo.id}/datasets/${dataset.id}/discover/preview",
        session = nonAdminSession
      ) ~>
        routes ~> check {
        status shouldEqual Forbidden
      }
    }

    "return 200 if dataset is shared with user" in {

      val datasetsMapper = new DatasetsMapper(organizationTwo)
      val datasetManager =
        new DatasetManager(db, admin, datasetsMapper)
      val dataset = datasetManager
        .create("Test Dataset")
        .await
        .value

      datasetManager
        .addUserCollaborator(dataset, nonAdmin, Role.Viewer)
        .await
        .value

      testRequest(
        GET,
        s"/authorization/organizations/${organizationTwo.id}/datasets/${dataset.id}/discover/preview",
        session = nonAdminSession
      ) ~>
        routes ~> check {
        status shouldEqual OK
      }
    }

    "return 200 if user is a dataset previewer" in {

      val datasetsMapper = new DatasetsMapper(organizationTwo)
      val datasetManager = new DatasetManager(db, admin, datasetsMapper)
      val dataset = datasetManager
        .create("Test Dataset")
        .await
        .value

      val datasetPreviewManager =
        new DatasetPreviewManager(db, datasetsMapper)
      datasetPreviewManager.grantAccess(dataset, nonAdmin).await.value

      organizationManager.removeUser(organizationOne, nonAdmin).await.value

      testRequest(
        GET,
        s"/authorization/organizations/${organizationTwo.id}/datasets/${dataset.id}/discover/preview",
        session = nonAdminSession
      ) ~>
        routes ~> check {
        status shouldEqual OK
      }
    }

    "return 200 if user is authorized to preview, even if he is logged on the DM platform in another organization" in {
      val datasetsMapper = new DatasetsMapper(organizationOne)
      val datasetManager = new DatasetManager(db, admin, datasetsMapper)
      val dataset = datasetManager
        .create("Test Dataset")
        .await
        .value

      val datasetPreviewManager =
        new DatasetPreviewManager(db, datasetsMapper)

      val preview =
        datasetPreviewManager.grantAccess(dataset, nonAdmin).await.value

      //nonAdmin preferred Org is Org #2
      testRequest(
        GET,
        s"/authorization/organizations/${organizationOne.id}/datasets/${dataset.id}/discover/preview",
        session = nonAdminSession
      ) ~>
        routes ~> check {
        status shouldEqual OK
      }
    }

    "return 403 Forbidden if preview access is requested but not accepted" in {

      val datasetsMapper = new DatasetsMapper(organizationTwo)
      val datasetManager = new DatasetManager(db, admin, datasetsMapper)
      val dataset = datasetManager
        .create("Test Dataset")
        .await
        .value

      val datasetPreviewManager =
        new DatasetPreviewManager(db, datasetsMapper)

      db.run(
          datasetPreviewManager.previewer.insertOrUpdate(
            DatasetPreviewer(
              datasetId = dataset.id,
              userId = nonAdmin.id,
              embargoAccess = EmbargoAccess.Requested,
              dataUseAgreementId = None
            )
          )
        )
        .await

      testRequest(
        GET,
        s"/authorization/organizations/${organizationTwo.id}/datasets/${dataset.id}/discover/preview",
        session = nonAdminSession
      ) ~>
        routes ~> check {
        status shouldEqual Forbidden
      }
    }

    "return 403 if preview access is rejected" in {

      val datasetsMapper = new DatasetsMapper(organizationTwo)
      val datasetManager = new DatasetManager(db, admin, datasetsMapper)
      val dataset = datasetManager
        .create("Test Dataset")
        .await
        .value

      val datasetPreviewManager =
        new DatasetPreviewManager(db, datasetsMapper)

      db.run(
          datasetPreviewManager.previewer.insertOrUpdate(
            DatasetPreviewer(
              datasetId = dataset.id,
              userId = nonAdmin.id,
              embargoAccess = EmbargoAccess.Refused,
              dataUseAgreementId = None
            )
          )
        )
        .await

      testRequest(
        GET,
        s"/authorization/organizations/${organizationTwo.id}/datasets/${dataset.id}/discover/preview",
        session = nonAdminSession
      ) ~>
        routes ~> check {
        status shouldEqual Forbidden
      }
    }
  }

}
