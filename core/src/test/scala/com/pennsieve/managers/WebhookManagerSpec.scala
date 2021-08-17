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
import com.pennsieve.models.{ DBPermission, User, Webhook }

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
    assert(returnedWebhook.id === webhook.id)
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
    assert(returnedWebhook.id === webhook.id)
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
    assert(returnedWebhook.id === webhook.id)
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
    val (webhook, subscriptions) = createWebhook()
    val whManager = webhookManager()
    val result = whManager.delete(webhook).await
    assert(result.isRight)
    val deletedRows = result.right.get
    assert(deletedRows === 1)
  }
}
