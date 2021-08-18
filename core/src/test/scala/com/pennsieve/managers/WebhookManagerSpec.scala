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

import com.pennsieve.domain.{ NotFound, PermissionError }
import com.pennsieve.models.{
  DBPermission,
  User,
  Webhook,
  WebhookEventSubcription,
  WebhookEventType
}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext.Implicits.global

class WebhookManagerSpec extends BaseManagerSpec {

  var orgReader: User = _
  var colleagueReader: User = _
  var colleagueAdmin: User = _
  var externalAdmin: User = _

  override def beforeEach(): Unit = {
    super.beforeEach()
    orgReader = createUser(
      email = "orgReader@example.com",
      permission = DBPermission.Read
    )
    colleagueReader = createUser(
      email = "colleagueReader@example.com",
      permission = DBPermission.Read
    )
    colleagueAdmin = createUser(
      email = "colleagueAdmin@example.com",
      permission = DBPermission.Administer
    )
    externalAdmin = createUser(
      email = "externalAdmin@example.edu",
      organization = Some(testOrganization2),
      permission = DBPermission.Administer,
      datasets = Nil
    )
  }

  "create" should "insert a webhook and its subscriptions into the database" in {
    val whManager = webhookManager()
    val expectedApiUrl = "http://api.example.com"
    val expectedImageUrl = "http://example.com/image.jpg"
    val expectedDescription = "test webhook"
    val expectedSecret = "secret123"
    val expectedDisplayName = "Test Webhook"
    val expectedIsPrivate = false
    val expectedIsDefault = true
    val expectedTargetEvents = List("METADATA", "PERMISSIONS")
    val expectedCreatedBy = whManager.actor.id

    val result = whManager
      .create(
        expectedApiUrl,
        Some(expectedImageUrl),
        expectedDescription,
        expectedSecret,
        expectedDisplayName,
        expectedIsPrivate,
        expectedIsDefault,
        Some(expectedTargetEvents),
        expectedCreatedBy
      )
      .await

    assert(result.isRight)
    val (returnedWebhook, returnedTargetEvents) = result.right.get
    assert(returnedWebhook.apiUrl == expectedApiUrl)
    assert(returnedWebhook.imageUrl.isDefined)
    assert(returnedWebhook.imageUrl.get == expectedImageUrl)
    assert(returnedWebhook.isDefault == expectedIsDefault)
    assert(returnedWebhook.isPrivate == expectedIsPrivate)
    assert(returnedWebhook.createdBy == expectedCreatedBy)
    assert(returnedWebhook.description == expectedDescription)
    assert(returnedWebhook.displayName == expectedDisplayName)
    assert(returnedWebhook.secret == expectedSecret)
    assert(returnedTargetEvents.equals(expectedTargetEvents))

    val webhookRows =
      database
        .run(whManager.webhooksMapper.result)
        .mapTo[Seq[Webhook]]
        .await
    assert(webhookRows.length == 1)
    val actualWebhook = webhookRows.head
    assert(actualWebhook.equals(returnedWebhook))

    val subscriptionRows = database
      .run(whManager.webhookEventSubscriptionsMapper.result)
      .mapTo[Seq[WebhookEventSubcription]]
      .await
    assert(subscriptionRows.length == expectedTargetEvents.length)
    assert(subscriptionRows.forall(_.webhookId == actualWebhook.id))
    val expectedEventsSet = expectedTargetEvents.to[collection.mutable.Set]
    for (subscriptionRow <- subscriptionRows) {
      val eventTypeId = subscriptionRow.webhookEventTypeId

      val eventTypeRow = database
        .run(
          whManager.webhookEventTypesMapper
            .filter(_.id === eventTypeId)
            .result
            .headOption
        )
        .mapTo[Some[WebhookEventType]]
        .await

      assert(eventTypeRow.isDefined)
      assert(expectedEventsSet.remove(eventTypeRow.get.eventName))
    }

    assert(expectedEventsSet.isEmpty)

  }

  "getWithPermissionCheck" should "return a webhook to its creator" in {
    val (webhook, _) = createWebhook(creatingUser = orgReader)
    val whManager = webhookManager(user = orgReader)
    val result = whManager
      .getWithPermissionCheck(
        webhook.id,
        withPermission = DBPermission.Administer
      )
      .await
    assert(result.isRight)
    val returnedWebhook = result.right.get
    assert(returnedWebhook.id == webhook.id)
  }

  it should "return a webhook to a super admin" in {
    val (webhook, _) = createWebhook(creatingUser = orgReader)
    val whManager = webhookManager()
    assert(whManager.actor.isSuperAdmin)
    val result = whManager
      .getWithPermissionCheck(
        webhook.id,
        withPermission = DBPermission.Administer
      )
      .await
    assert(result.isRight)
    val returnedWebhook = result.right.get
    assert(returnedWebhook.id == webhook.id)
  }

  it should "return a webhook to an organization admin" in {
    val (webhook, _) = createWebhook(creatingUser = orgReader)
    val whManager = webhookManager(user = colleagueAdmin)
    val result = whManager
      .getWithPermissionCheck(
        webhook.id,
        withPermission = DBPermission.Administer
      )
      .await
    assert(result.isRight)
    val returnedWebhook = result.right.get
    assert(returnedWebhook.id == webhook.id)
  }

  it should "return a PermissionError if invoked by a user without the required minimum permission" in {
    val (webhook, _) = createWebhook(creatingUser = orgReader)
    val whManager = webhookManager(user = colleagueReader)

    val result = whManager
      .getWithPermissionCheck(
        webhook.id,
        withPermission = DBPermission.Administer
      )
      .await
    assert(result.isLeft)
    val error = result.left.get
    assert(error.isInstanceOf[PermissionError])
  }

  it should "return a NotFound if the webhook does not exist" in {
    val whManager = webhookManager(user = orgReader)

    val result = whManager
      .getWithPermissionCheck(
        webhookId = 1,
        withPermission = DBPermission.Administer
      )
      .await
    assert(result.isLeft)
    val error = result.left.get
    assert(error.isInstanceOf[NotFound])
  }

  it should "return a NotFound if invoked by a non-superAdmin outside the creator's organization" in {
    val (webhook, _) = createWebhook(creatingUser = orgReader)
    val whManager = webhookManager(user = externalAdmin)

    val result = whManager
      .getWithPermissionCheck(
        webhook.id,
        withPermission = DBPermission.Administer
      )
      .await
    assert(result.isLeft)
    val error = result.left.get
    assert(error.isInstanceOf[NotFound])
  }

  "delete" should "return 1 and delete the webhook and its subscriptions" in {
    val (webhook, _) = createWebhook()
    val (webhook2, webhook2Subs) = createWebhook(
      description = "test webhook2",
      displayName = "Test Webhook 2"
    )
    val whManager = webhookManager()
    val result = whManager.delete(webhook).await
    assert(result.isRight)
    val deletedRowCount = result.right.get
    assert(deletedRowCount == 1)

    val webhookRows =
      database
        .run(whManager.webhooksMapper.result)
        .mapTo[Seq[Webhook]]
        .await
    assert(webhookRows.length == 1)
    assert(webhookRows.head.id == webhook2.id)

    val subscriptionRows = database
      .run(whManager.webhookEventSubscriptionsMapper.result)
      .mapTo[Seq[WebhookEventSubcription]]
      .await
    assert(subscriptionRows.length == webhook2Subs.length)
    assert(subscriptionRows.forall(_.webhookId == webhook2.id))
  }

  it should "return 0 if given a non-persisted Webhook" in {
    val (webhook, subscriptions) = createWebhook()
    val unsavedWebhook = Webhook(
      "",
      None,
      "",
      "",
      "",
      "",
      isPrivate = false,
      isDefault = true,
      isDisabled = false,
      webhook.id + 1
    )
    val result = webhookManager().delete(unsavedWebhook).await
    assert(result.isRight)
    val deletedRowCount = result.right.get
    assert(deletedRowCount == 0)

    val whManager = webhookManager()
    val webhookRows =
      database
        .run(whManager.webhooksMapper.result)
        .mapTo[Seq[Webhook]]
        .await
    assert(webhookRows.length == 1)
    assert(webhookRows.head.id == webhook.id)

    val subscriptionRows = database
      .run(whManager.webhookEventSubscriptionsMapper.result)
      .mapTo[Seq[WebhookEventSubcription]]
      .await
    assert(subscriptionRows.length == subscriptions.length)
    assert(subscriptionRows.forall(_.webhookId == webhook.id))
  }
}
