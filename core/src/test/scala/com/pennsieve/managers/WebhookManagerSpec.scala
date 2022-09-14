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

import com.pennsieve.domain.{ NotFound, PermissionError, PredicateError }
import com.pennsieve.models._
import slick.jdbc.PostgresProfile.api._
import org.scalatest.EitherValues._

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
    val expectedHasAccess = true
    val expectedTargetEvents = List("METADATA", "PERMISSIONS")

    val result = whManager
      .create(
        expectedApiUrl,
        Some(expectedImageUrl),
        expectedDescription,
        expectedSecret,
        expectedDisplayName,
        expectedIsPrivate,
        expectedIsDefault,
        expectedHasAccess,
        Some(expectedTargetEvents),
        integrationUser
      )
      .await

    val (returnedWebhook, returnedTargetEvents) = result.value
    assert(returnedWebhook.apiUrl == expectedApiUrl)
    assert(returnedWebhook.imageUrl.isDefined)
    assert(returnedWebhook.imageUrl.get == expectedImageUrl)
    assert(returnedWebhook.isDefault == expectedIsDefault)
    assert(returnedWebhook.isPrivate == expectedIsPrivate)
    assert(returnedWebhook.createdBy == whManager.actor.id)
    assert(returnedWebhook.description == expectedDescription)
    assert(returnedWebhook.displayName == expectedDisplayName)
    assert(returnedWebhook.secret == expectedSecret)
    assert(returnedWebhook.integrationUserId == integrationUser.id)
    assert(returnedTargetEvents.equals(expectedTargetEvents))

    checkActualWebhooks(whManager, returnedWebhook)
    checkActualSubscriptions(
      whManager,
      returnedWebhook.id,
      expectedTargetEvents
    )
  }

  it should "insert a webhook with no subscriptions into the database" in {
    val whManager = webhookManager()
    val expectedApiUrl = "http://api.example.com"
    val expectedImageUrl = "http://example.com/image.jpg"
    val expectedDescription = "test webhook"
    val expectedSecret = "secret123"
    val expectedDisplayName = "Test Webhook"
    val expectedIsPrivate = false
    val expectedIsDefault = true
    val expectedHasAccess = true
    val expectedTargetEvents = None

    val result = whManager
      .create(
        expectedApiUrl,
        Some(expectedImageUrl),
        expectedDescription,
        expectedSecret,
        expectedDisplayName,
        expectedIsPrivate,
        expectedIsDefault,
        expectedHasAccess,
        expectedTargetEvents,
        integrationUser
      )
      .await

    val (returnedWebhook, returnedTargetEvents) = result.value
    assert(returnedWebhook.apiUrl == expectedApiUrl)
    assert(returnedWebhook.imageUrl.isDefined)
    assert(returnedWebhook.imageUrl.get == expectedImageUrl)
    assert(returnedWebhook.isDefault == expectedIsDefault)
    assert(returnedWebhook.isPrivate == expectedIsPrivate)
    assert(returnedWebhook.createdBy == whManager.actor.id)
    assert(returnedWebhook.description == expectedDescription)
    assert(returnedWebhook.displayName == expectedDisplayName)
    assert(returnedWebhook.secret == expectedSecret)
    assert(returnedTargetEvents.isEmpty)

    checkActualWebhooks(whManager, returnedWebhook)
    assertNoSubscriptions(whManager)
  }

  it should "handle an empty target event list without error" in {
    val whManager = webhookManager()
    val expectedApiUrl = "http://api.example.com"
    val expectedImageUrl = "http://example.com/image.jpg"
    val expectedDescription = "test webhook"
    val expectedSecret = "secret123"
    val expectedDisplayName = "Test Webhook"
    val expectedIsPrivate = false
    val expectedIsDefault = true
    val expectedHasAccess = true
    val expectedTargetEvents = Nil

    val result = whManager
      .create(
        expectedApiUrl,
        Some(expectedImageUrl),
        expectedDescription,
        expectedSecret,
        expectedDisplayName,
        expectedIsPrivate,
        expectedIsDefault,
        expectedHasAccess,
        Some(expectedTargetEvents),
        integrationUser
      )
      .await

    val (returnedWebhook, returnedTargetEvents) = result.value
    assert(returnedWebhook.apiUrl == expectedApiUrl)
    assert(returnedWebhook.imageUrl.isDefined)
    assert(returnedWebhook.imageUrl.get == expectedImageUrl)
    assert(returnedWebhook.isDefault == expectedIsDefault)
    assert(returnedWebhook.isPrivate == expectedIsPrivate)
    assert(returnedWebhook.createdBy == whManager.actor.id)
    assert(returnedWebhook.description == expectedDescription)
    assert(returnedWebhook.displayName == expectedDisplayName)
    assert(returnedWebhook.secret == expectedSecret)
    assert(returnedTargetEvents.isEmpty)

    checkActualWebhooks(whManager, returnedWebhook)
    assertNoSubscriptions(whManager)
  }

  it should "return a PredicateError if used with an unknown target event" in {
    val whManager = webhookManager()
    val expectedApiUrl = "http://api.example.com"
    val expectedImageUrl = "http://example.com/image.jpg"
    val expectedDescription = "test webhook"
    val expectedSecret = "secret123"
    val expectedDisplayName = "Test Webhook"
    val expectedIsPrivate = false
    val expectedIsDefault = true
    val expectedHasAccess = true
    val expectedTargetEvents =
      List("METADATA", "PERMISSIONS", "NON-EXISTENT EVENT")

    val result = whManager
      .create(
        expectedApiUrl,
        Some(expectedImageUrl),
        expectedDescription,
        expectedSecret,
        expectedDisplayName,
        expectedIsPrivate,
        expectedIsDefault,
        expectedHasAccess,
        Some(expectedTargetEvents),
        integrationUser
      )
      .await

    val error = result.left.value
    assert(error.isInstanceOf[PredicateError])

    checkActualWebhooks(whManager)
    assertNoSubscriptions(whManager)
  }

  it should "return a PredicateError if apiUrl is empty" in {
    val whManager = webhookManager()
    val expectedApiUrl = ""
    val expectedImageUrl = "http://example.com/image.jpg"
    val expectedDescription = "test webhook"
    val expectedSecret = "secret123"
    val expectedDisplayName = "Test Webhook"
    val expectedIsPrivate = false
    val expectedIsDefault = true
    val expectedHasAccess = true
    val expectedTargetEvents =
      List("METADATA", "PERMISSIONS")

    val result = whManager
      .create(
        expectedApiUrl,
        Some(expectedImageUrl),
        expectedDescription,
        expectedSecret,
        expectedDisplayName,
        expectedIsPrivate,
        expectedIsDefault,
        expectedHasAccess,
        Some(expectedTargetEvents),
        integrationUser
      )
      .await

    val error = result.left.value
    assert(error.isInstanceOf[PredicateError])

    checkActualWebhooks(whManager)
    assertNoSubscriptions(whManager)
  }

  it should "return a PredicateError if displayName is too long" in {
    val whManager = webhookManager()
    val expectedApiUrl = "http://api.example.com"
    val expectedImageUrl = "http://example.com/image.jpg"
    val expectedDescription = "test webhook"
    val expectedSecret = "secret123"
    val expectedDisplayName = "Test Webhook" * 100
    val expectedIsPrivate = false
    val expectedIsDefault = true
    val expectedHasAccess = true
    val expectedTargetEvents =
      List("METADATA", "PERMISSIONS")

    val result = whManager
      .create(
        expectedApiUrl,
        Some(expectedImageUrl),
        expectedDescription,
        expectedSecret,
        expectedDisplayName,
        expectedIsPrivate,
        expectedIsDefault,
        expectedHasAccess,
        Some(expectedTargetEvents),
        integrationUser
      )
      .await

    val error = result.left.value
    assert(error.isInstanceOf[PredicateError])

    checkActualWebhooks(whManager)
    assertNoSubscriptions(whManager)
  }

  it should "return a PredicateError if imageUrl is too long" in {
    val whManager = webhookManager()
    val expectedApiUrl = "http://api.example.com"
    val expectedImageUrl = "http://" + "example" * 100 + ".com/image.jpg"
    val expectedDescription = "test webhook"
    val expectedSecret = "secret123"
    val expectedDisplayName = "Test Webhook"
    val expectedIsPrivate = false
    val expectedIsDefault = true
    val expectedHasAccess = true
    val expectedTargetEvents =
      List("METADATA", "PERMISSIONS")

    val result = whManager
      .create(
        expectedApiUrl,
        Some(expectedImageUrl),
        expectedDescription,
        expectedSecret,
        expectedDisplayName,
        expectedIsPrivate,
        expectedIsDefault,
        expectedHasAccess,
        Some(expectedTargetEvents),
        integrationUser
      )
      .await

    val error = result.left.value
    assert(error.isInstanceOf[PredicateError])

    checkActualWebhooks(whManager)
    assertNoSubscriptions(whManager)
  }

  it should "handle an empty image url without error" in {
    val whManager = webhookManager()
    val expectedApiUrl = "http://api.example.com"
    val expectedDescription = "test webhook"
    val expectedSecret = "secret123"
    val expectedDisplayName = "Test Webhook"
    val expectedIsPrivate = false
    val expectedIsDefault = true
    val expectedHasAccess = true
    val expectedTargetEvents = List("METADATA", "PERMISSIONS")

    val result = whManager
      .create(
        expectedApiUrl,
        Some(""),
        expectedDescription,
        expectedSecret,
        expectedDisplayName,
        expectedIsPrivate,
        expectedIsDefault,
        expectedHasAccess,
        Some(expectedTargetEvents),
        integrationUser
      )
      .await

    val (returnedWebhook, returnedTargetEvents) = result.value
    assert(returnedWebhook.apiUrl == expectedApiUrl)
    assert(returnedWebhook.imageUrl.isEmpty)
    assert(returnedWebhook.isDefault == expectedIsDefault)
    assert(returnedWebhook.isPrivate == expectedIsPrivate)
    assert(returnedWebhook.createdBy == whManager.actor.id)
    assert(returnedWebhook.description == expectedDescription)
    assert(returnedWebhook.displayName == expectedDisplayName)
    assert(returnedWebhook.secret == expectedSecret)
    assert(returnedTargetEvents.equals(expectedTargetEvents))

    checkActualWebhooks(whManager, returnedWebhook)
    checkActualSubscriptions(
      whManager,
      returnedWebhook.id,
      expectedTargetEvents
    )
  }

  /**
    * Looks at all webhooks in database and checks that they are equal to the {@code expectedWebhooks} in some order.
    *
    * @param whManager
    * @param expectedWebhooks
    */
  def checkActualWebhooks(
    whManager: WebhookManager,
    expectedWebhooks: Webhook*
  ): Unit = {
    val webhookRows = database
      .run(whManager.webhooksMapper.result)
      .mapTo[Seq[Webhook]]
      .await
    assert(webhookRows.length == expectedWebhooks.length)
    assert(webhookRows.toSet.equals(expectedWebhooks.toSet))
  }

  /**
    * Looks at all event subscriptions in database and verifies that each subscription
    * has the {@code expectedWebhookId} and that the actual events match those in {@code expectedTargetEvents}
    *
    * @param whManager
    * @param expectedWebhookId
    * @param expectedTargetEvents
    */
  def checkActualSubscriptions(
    whManager: WebhookManager,
    expectedWebhookId: Int,
    expectedTargetEvents: Seq[String]
  ): Unit = {
    val whIdEventQuery = for {
      (sub, event) <- whManager.webhookEventSubscriptionsMapper join whManager.webhookEventTypesMapper on (_.webhookEventTypeId === _.id)
    } yield (sub.webhookId, event.eventName)

    val actualWhIdEvents = database.run(whIdEventQuery.result).await
    assert(actualWhIdEvents.length == expectedTargetEvents.length)
    assert(actualWhIdEvents.forall(_._1 == expectedWebhookId))
    assert(actualWhIdEvents.map(_._2).toSet == expectedTargetEvents.toSet)
  }

  /**
    * Checks that there are no webhook event subscriptions in the database
    *
    * @param whManager
    */
  def assertNoSubscriptions(whManager: WebhookManager): Unit = {
    val subscriptionRows = database
      .run(whManager.webhookEventSubscriptionsMapper.result)
      .await
    assert(subscriptionRows.isEmpty)
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
    val returnedWebhook = result.value
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
    val returnedWebhook = result.value
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
    val returnedWebhook = result.value
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
    val error = result.left.value
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
    val error = result.left.value
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
    val error = result.left.value
    assert(error.isInstanceOf[NotFound])
  }

  "update" should "update and return the modified webhook" in {
    val (webhook, _) =
      createWebhook(
        description = "original description",
        targetEvents = Some(List("METADATA", "STATUS"))
      )
    val newDescription = "new description"
    val newEvents = List("STATUS", "FILES")
    val whManager = webhookManager()

    val updatedWebhook = webhook.copy(description = newDescription)
    val result =
      whManager.update(updatedWebhook, targetEvents = Some(newEvents)).await

    val (returnedWebhook, returnedEvents) = result.value
    assert(returnedWebhook.description == newDescription)
    assert(returnedEvents.toSet == newEvents.toSet)

    checkActualWebhooks(whManager, returnedWebhook)
    checkActualSubscriptions(whManager, returnedWebhook.id, returnedEvents)
  }

  it should "work if no changes were made" in {
    val (webhook, subscriptions) =
      createWebhook()
    val whManager = webhookManager()

    val result = whManager.update(webhook).await
    val (returnedWebhook, returnedEvents) = result.value
    assert(webhook == returnedWebhook)
    assert(returnedEvents == subscriptions)

    checkActualWebhooks(whManager, returnedWebhook)
    checkActualSubscriptions(whManager, returnedWebhook.id, subscriptions)
  }

  it should "delete all event subscriptions if targetEvents is Some but empty" in {
    val (webhook, _) =
      createWebhook(targetEvents = Some(List("FILES", "METADATA")))
    val whManager = webhookManager()

    val result = whManager.update(webhook, targetEvents = Some(Nil)).await
    val (returnedWebhook, returnedEvents) = result.value
    assert(webhook == returnedWebhook)
    assert(returnedEvents.isEmpty)

    checkActualWebhooks(whManager, returnedWebhook)
    assertNoSubscriptions(whManager)
  }

  it should "return a PredicateError if targetEvents contains a non-existent event name" in {
    val (webhook, subscriptions) =
      createWebhook(targetEvents = Some(List("FILES", "METADATA")))
    val whManager = webhookManager()

    val result = whManager
      .update(
        webhook,
        targetEvents = Some(List("FILES", "STATUS", "NON-EVENT"))
      )
      .await
    val error = result.left.value
    assert(error.isInstanceOf[PredicateError])
    assert(error.getMessage.contains("NON-EVENT"))

    checkActualWebhooks(whManager, webhook)
    checkActualSubscriptions(whManager, webhook.id, subscriptions)
  }

  it should "return a NotFound error if given an non-existent webhook" in {
    val unsavedWebhook = Webhook(
      "https://example.com/api",
      None,
      "description",
      "secret",
      "name",
      "display name",
      isPrivate = false,
      isDefault = true,
      isDisabled = false,
      hasAccess = false,
      integrationUser.id,
      1
    )

    val whManager = webhookManager()
    val result = whManager.update(unsavedWebhook).await

    val error = result.left.value
    assert(error.isInstanceOf[NotFound])
    assert(error.getMessage.contains(unsavedWebhook.id.toString))
    checkActualWebhooks(whManager)
  }

  it should "return a PredicateError if apiUrl is updated to empty" in {
    val (webhook, subscriptions) =
      createWebhook()
    val whManager = webhookManager()

    val updatedWebhook = webhook.copy(apiUrl = "")
    val result = whManager.update(updatedWebhook).await
    val error = result.left.value
    assert(error.isInstanceOf[PredicateError])
    assert(error.getMessage contains "api url")

    checkActualWebhooks(whManager, webhook)
    checkActualSubscriptions(whManager, webhook.id, subscriptions)
  }

  it should "return a PredicateError if displayName is updated to a value that is too long" in {
    val (webhook, subscriptions) =
      createWebhook()
    val whManager = webhookManager()

    val updatedWebhook = webhook.copy(displayName = "display name" * 300)
    val result = whManager.update(updatedWebhook).await

    val error = result.left.value
    assert(error.isInstanceOf[PredicateError])
    assert(error.getMessage contains "display name")

    checkActualWebhooks(whManager, webhook)
    checkActualSubscriptions(whManager, webhook.id, subscriptions)
  }

  it should "return a PredicateError if image url is updated to a value that is too long" in {
    val (webhook, subscriptions) =
      createWebhook()
    val whManager = webhookManager()

    val updatedWebhook = webhook.copy(
      imageUrl = Some("https://" + "example" * 300 + ".com/image.jpg")
    )
    val result = whManager.update(updatedWebhook).await

    val error = result.left.value
    assert(error.isInstanceOf[PredicateError])
    assert(error.getMessage contains "image url")

    checkActualWebhooks(whManager, webhook)
    checkActualSubscriptions(whManager, webhook.id, subscriptions)
  }

  it should "work if image url is updated to an empty value" in {
    val (webhook, subscriptions) =
      createWebhook(imageUrl = Some("http://www.example.com/image.jpg"))
    val whManager = webhookManager()

    val updatedWebhook = webhook.copy(imageUrl = Some(""))
    val result = whManager.update(updatedWebhook).await

    val (returnedWebhook, returnedEvents) = result.value
    assert(returnedWebhook.imageUrl.isEmpty)
    assert(returnedEvents == subscriptions)

    checkActualWebhooks(whManager, returnedWebhook)
    checkActualSubscriptions(whManager, returnedWebhook.id, subscriptions)
  }

  it should "work if image url is updated to None" in {
    val (webhook, subscriptions) =
      createWebhook(imageUrl = Some("http://www.example.com/image.jpg"))
    val whManager = webhookManager()

    val updatedWebhook = webhook.copy(imageUrl = None)
    val result = whManager.update(updatedWebhook).await

    val (returnedWebhook, returnedEvents) = result.value
    assert(returnedWebhook.imageUrl.isEmpty)
    assert(returnedEvents == subscriptions)

    checkActualWebhooks(whManager, returnedWebhook)
    checkActualSubscriptions(whManager, returnedWebhook.id, subscriptions)
  }

  "delete" should "return 1 and delete the webhook and its subscriptions" in {
    val (webhook, _) = createWebhook()
    val (webhook2, webhook2Subs) = createWebhook(
      description = "test webhook2",
      displayName = "Test Webhook 2"
    )
    val whManager = webhookManager()
    val result = whManager.delete(webhook).await
    val deletedRowCount = result.value
    assert(deletedRowCount == 1)

    checkActualWebhooks(whManager, webhook2)
    checkActualSubscriptions(whManager, webhook2.id, webhook2Subs)
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
      hasAccess = false,
      integrationUserId = 1,
      webhook.id + 1
    )
    val result = webhookManager().delete(unsavedWebhook).await
    val deletedRowCount = result.value
    assert(deletedRowCount == 0)

    val whManager = webhookManager()
    checkActualWebhooks(whManager, webhook)
    checkActualSubscriptions(whManager, webhook.id, subscriptions)
  }
}
