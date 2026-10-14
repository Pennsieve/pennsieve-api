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

package com.pennsieve.managers

import com.pennsieve.models._
import io.circe.syntax._
import org.scalatest.EitherValues._
import org.scalatest.matchers.should.Matchers._

import scala.concurrent.ExecutionContext.Implicits.global

class OrganizationEventManagerSpec extends BaseManagerSpec {

  "organization events" should "log an event with both actor and subject" in {
    val oem = organizationEventManager()
    val user = createUser()

    val event = oem
      .logEvent(
        actorUserId = Some(superAdmin.id),
        subjectUserId = Some(user.id),
        detail = OrganizationEventDetail
          .AddOrganizationUser(user.id, DBPermission.Read)
      )
      .await
      .value

    event.organizationId shouldBe testOrganization.id
    event.actorUserId shouldBe Some(superAdmin.id)
    event.subjectUserId shouldBe Some(user.id)
    event.eventType shouldBe OrganizationEventName.ADD_ORGANIZATION_USER
  }

  it should "log an event with no actor at all (workspace-wide broadcast shape)" in {
    val oem = organizationEventManager()

    val event = oem
      .logEvent(
        actorUserId = None,
        subjectUserId = None,
        detail = OrganizationEventDetail.CreateTeam(1, "Some Team")
      )
      .await
      .value

    event.actorUserId shouldBe None
    event.subjectUserId shouldBe None
    event.eventType shouldBe OrganizationEventName.CREATE_TEAM
  }

  it should "round trip the detail payload through the DB" in {
    val oem = organizationEventManager()
    val user = createUser()

    oem
      .logEvent(
        actorUserId = Some(superAdmin.id),
        subjectUserId = Some(user.id),
        detail = OrganizationEventDetail
          .RemovedFromDataset(42, "N:dataset:abc", "My Dataset")
      )
      .await
      .value

    val events = oem.getEvents().await.value

    events should have size 1
    events.head.eventType shouldBe OrganizationEventName.REMOVED_FROM_DATASET

    val decoded = events.head.detail
      .as(OrganizationEventDetail.decoder(events.head.eventType))
      .value

    decoded shouldBe OrganizationEventDetail
      .RemovedFromDataset(42, "N:dataset:abc", "My Dataset")
  }

  it should "scope events to their organization" in {
    val oem1 = organizationEventManager(testOrganization)
    val oem2 = organizationEventManager(testOrganization2)

    oem1
      .logEvent(
        actorUserId = Some(superAdmin.id),
        subjectUserId = None,
        detail = OrganizationEventDetail.CreateTeam(1, "Team A")
      )
      .await
      .value

    oem2
      .logEvent(
        actorUserId = Some(superAdmin.id),
        subjectUserId = None,
        detail = OrganizationEventDetail.CreateTeam(2, "Team B")
      )
      .await
      .value

    val org1Events = oem1.getEvents().await.value
    org1Events.map(_.detail) shouldBe List(
      OrganizationEventDetail.CreateTeam(1, "Team A").asJson
    )
  }
}
