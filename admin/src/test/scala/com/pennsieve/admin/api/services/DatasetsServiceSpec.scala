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

package com.pennsieve.admin.api.services
import akka.http.scaladsl.model.HttpMethods.{ DELETE, GET, POST }
import com.pennsieve.models.{ PublishStatus }
import io.circe.syntax._
import akka.http.scaladsl.model.StatusCodes.{ NoContent, OK }
import com.pennsieve.discover.client.definitions.{
  DatasetPublishStatus,
  SponsorshipRequest,
  SponsorshipResponse
}

class DatasetsServiceSpec extends AdminServiceSpec {

  "datasets service" should {
    "return all published datasets in an organization" in {
      testRequest(
        GET,
        s"/organizations/${organizationOne.id}/datasets",
        session = adminCognitoJwt
      ) ~>
        routes ~> check {

        import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

        status shouldEqual OK
        responseAs[List[DatasetPublishStatus]] should contain theSameElementsAs (List(
          DatasetPublishStatus(
            "PPMI",
            organizationOne.id,
            1,
            None,
            0,
            PublishStatus.PublishInProgress,
            None,
            Some(SponsorshipRequest(Some("foo"), Some("bar"), Some("baz"))),
            workflowId = 4
          ),
          DatasetPublishStatus(
            "TUSZ",
            organizationOne.id,
            2,
            None,
            0,
            PublishStatus.PublishInProgress,
            None,
            workflowId = 4
          )
        ))
      }

    }

    "add a sponsorship to a dataset" in {
      testRequest(
        POST,
        s"/organizations/${organizationOne.id}/datasets/1/sponsor",
        json = Some(SponsorshipRequest(Some("foo")).asJson),
        session = adminCognitoJwt
      ) ~>
        routes ~> check {

        import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

        status shouldEqual OK
        responseAs[SponsorshipResponse] shouldEqual SponsorshipResponse(1, 1)
      }

    }

    "remove a sponsorship from a dataset" in {
      testRequest(
        DELETE,
        s"/organizations/${organizationOne.id}/datasets/1/sponsor",
        session = adminCognitoJwt
      ) ~>
        routes ~> check {

        status shouldEqual NoContent
      }

    }
  }

}
