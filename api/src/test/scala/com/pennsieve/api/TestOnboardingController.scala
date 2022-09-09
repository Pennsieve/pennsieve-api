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

import com.pennsieve.models.OnboardingEventType
import com.pennsieve.models.OnboardingEventType._
import org.scalatest.EitherValues._
import com.pennsieve.test.helpers.AwaitableImplicits._
import io.circe._
import io.circe.syntax._
import io.circe.parser._

import scala.collection._

class TestOnboardingController extends BaseApiTest {

  override def afterStart(): Unit = {
    super.afterStart()
    addServlet(
      new OnboardingController(
        insecureContainer,
        secureContainerBuilder,
        system.dispatcher
      ),
      "/*"
    )
  }

  def getEventsFromResponse(): Seq[OnboardingEventType] =
    decode[Seq[OnboardingEventType]](response.body).value

  test("serialization for event types works properly") {
    val firstTimeSignOn: OnboardingEventType =
      OnboardingEventType.FirstTimeSignOn
    firstTimeSignOn.asJson.noSpaces should ===("\"FirstTimeSignOn\"")

    val launchCarousel: OnboardingEventType =
      OnboardingEventType.LaunchCarousel
    launchCarousel.asJson.noSpaces should ===("\"LaunchCarousel\"")

    val completedCarousel: OnboardingEventType =
      OnboardingEventType.CompletedCarousel
    completedCarousel.asJson.noSpaces should ===("\"CompletedCarousel\"")

    val createdDataset: OnboardingEventType =
      OnboardingEventType.CreatedDataset
    createdDataset.asJson.noSpaces should ===("\"CreatedDataset\"")

    val createdModel: OnboardingEventType =
      OnboardingEventType.CreatedModel
    createdModel.asJson.noSpaces should ===("\"CreatedModel\"")

    val addedFile: OnboardingEventType = OnboardingEventType.AddedFile
    addedFile.asJson.noSpaces should ===("\"AddedFile\"")

    val createdRecord: OnboardingEventType =
      OnboardingEventType.CreatedRecord
    createdRecord.asJson.noSpaces should ===("\"CreatedRecord\"")

    val createdRelationshipType: OnboardingEventType =
      OnboardingEventType.CreatedRelationshipType
    createdRelationshipType.asJson.noSpaces should ===(
      "\"CreatedRelationshipType\""
    )

    val AddedOrcid: OnboardingEventType = OnboardingEventType.AddedOrcid
    AddedOrcid.asJson.noSpaces should ===("\"AddedOrcid\"")
  }

  test("deserialization for event types work properly") {
    decode[OnboardingEventType]("\"FirstTimeSignOn\"") should ===(
      Right(OnboardingEventType.FirstTimeSignOn)
    )
    decode[OnboardingEventType]("\"LaunchCarousel\"") should ===(
      Right(OnboardingEventType.LaunchCarousel)
    )
    decode[OnboardingEventType]("\"CompletedCarousel\"") should ===(
      Right(OnboardingEventType.CompletedCarousel)
    )
    decode[OnboardingEventType]("\"CreatedDataset\"") should ===(
      Right(OnboardingEventType.CreatedDataset)
    )
    decode[OnboardingEventType]("\"CreatedModel\"") should ===(
      Right(OnboardingEventType.CreatedModel)
    )
    decode[OnboardingEventType]("\"AddedFile\"") should ===(
      Right(OnboardingEventType.AddedFile)
    )
    decode[OnboardingEventType]("\"CreatedRecord\"") should ===(
      Right(OnboardingEventType.CreatedRecord)
    )
    decode[OnboardingEventType]("\"CreatedRelationshipType\"") should ===(
      Right(OnboardingEventType.CreatedRelationshipType)
    )
    decode[OnboardingEventType]("\"AddedOrcid\"") should ===(
      Right(OnboardingEventType.AddedOrcid)
    )
  }

  test("fetching events works as expected for the current user") {
    // Add some distinct events for the logged in user:
    onboardingManager
      .addEvent(loggedInUser.id, OnboardingEventType.FirstTimeSignOn)
      .await
    onboardingManager
      .addEvent(loggedInUser.id, OnboardingEventType.CreatedDataset)
      .await
    // and some events for another user:
    onboardingManager
      .addEvent(colleagueUser.id, OnboardingEventType.CreatedRecord)
      .await
    onboardingManager
      .addEvent(colleagueUser.id, OnboardingEventType.CompletedCarousel)
      .await

    get(s"/events", headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      val events = getEventsFromResponse
      events should have length (2)
      events should contain(OnboardingEventType.FirstTimeSignOn)
      events should contain(OnboardingEventType.CreatedDataset)
      events shouldNot contain(OnboardingEventType.CreatedRecord)
      events shouldNot contain(
        events contains OnboardingEventType.CompletedCarousel
      )
    }
  }

  test("adding multiple copies of the same event results in one instance") {
    // Add the same event 3 times
    for (i <- 1 to 3) {
      onboardingManager
        .addEvent(loggedInUser.id, OnboardingEventType.FirstTimeSignOn)
        .await
    }
    get(s"/events", headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      val events = getEventsFromResponse
      events should have length (1)
      events should ===(Seq(OnboardingEventType.FirstTimeSignOn))
    }
  }

  test("adding valid events should work") {
    postJson(
      s"/events",
      "\"FirstTimeSignOn\"",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
    }
    postJson(
      s"/events",
      "\"CreatedDataset\"",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
    }
    postJson(
      s"/events",
      "\"CreatedRecord\"",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
    }
    get(s"/events", headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      val events = getEventsFromResponse
      events should have length (3)
      events should contain(OnboardingEventType.FirstTimeSignOn)
      events should contain(OnboardingEventType.CreatedDataset)
      events should contain(OnboardingEventType.CreatedRecord)
      events shouldNot contain(OnboardingEventType.CompletedCarousel)
    }
  }

  test("adding invalid events should fail") {
    postJson(
      s"/events",
      "\"NotValid\"",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(400)
    }
  }
}
