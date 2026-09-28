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

package com.pennsieve.helpers

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ HttpRequest, StatusCodes }
import com.pennsieve.discover.client.definitions.{
  DatasetsPage,
  UnpublishRequest
}
import com.pennsieve.discover.client.publish.{
  GetStatusResponse,
  GetStatusesResponse,
  UnpublishResponse
}
import com.pennsieve.discover.client.search.SearchDatasetsResponse
import com.pennsieve.models.PublishStatus
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Seconds, Span }

import scala.concurrent.{ Await, ExecutionContext }
import scala.concurrent.duration._

class TestDisabledDiscoverClients
    extends AnyFlatSpec
    with Matchers
    with ScalaFutures
    with BeforeAndAfterAll {

  implicit val system: ActorSystem = ActorSystem("disabled-discover-test")
  implicit val ec: ExecutionContext = system.dispatcher
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds))

  override def afterAll(): Unit = {
    Await.result(system.terminate(), 10.seconds)
    super.afterAll()
  }

  val publish = new DisabledPublishClient(defaultWorkflowId = 5L)
  val search = new DisabledSearchClient()

  "a disabled publish client" should "report no published datasets" in {
    publish.getStatuses(1, Nil).value.futureValue shouldBe Right(
      GetStatusesResponse.OK(Vector.empty)
    )
  }

  it should "report a dataset as not published" in {
    val Right(GetStatusResponse.OK(status)) =
      publish.getStatus(1, 42, Nil).value.futureValue
    status.sourceOrganizationId shouldBe 1
    status.sourceDatasetId shouldBe 42
    status.status shouldBe PublishStatus.NotPublished
    status.publishedDatasetId shouldBe None
    status.publishedVersionCount shouldBe 0
    status.workflowId shouldBe 5L
  }

  // Dataset delete always unpublishes first; with nothing published that has
  // to succeed without a Discover to ask.
  it should "treat unpublish as a no-op" in {
    publish
      .unpublish(1, 42, UnpublishRequest(None), Nil)
      .value
      .futureValue shouldBe Right(UnpublishResponse.NoContent)
  }

  "a disabled search client" should "find nothing" in {
    search
      .searchDatasets(
        limit = Some(25),
        offset = Some(50),
        query = Some("anything"),
        organization = None,
        organizationId = Some(1),
        tags = None,
        embargo = None,
        orderBy = None,
        orderDirection = None,
        headers = Nil
      )
      .value
      .futureValue shouldBe Right(
      SearchDatasetsResponse.OK(DatasetsPage(25, 50, 0L, Vector.empty))
    )
  }

  "an unanswered call" should "get a 503 naming the reason" in {
    val response = DisabledDiscoverClients
      .refusingHttpClient(HttpRequest())
      .futureValue
    response.status shouldBe StatusCodes.ServiceUnavailable
  }
}
