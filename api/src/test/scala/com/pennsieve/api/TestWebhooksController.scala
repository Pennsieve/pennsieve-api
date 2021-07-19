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

import com.pennsieve.helpers.DataSetTestMixin
import com.pennsieve.helpers.MockAuditLogger
import com.pennsieve.models.{ Webhook, WebhookEventSubcription }

import java.time.ZonedDateTime
import com.pennsieve.dtos.WebhookDTO
import org.json4s.jackson.Serialization.write

class TestWebhooksController extends BaseApiTest with DataSetTestMixin {

  val auditLogger = new MockAuditLogger()

  override def afterStart(): Unit = {
    super.afterStart()

    addServlet(
      new WebhooksController(
        insecureContainer,
        secureContainerBuilder,
        system,
        auditLogger,
        system.dispatcher
      ),
      "/*"
    )
  }

  test("get a webhook") {
    val webhookSubscription = createWebhook()
    val webhook = webhookSubscription._1
    val subscriptions = webhookSubscription._2

    subscriptions foreach println

    get(s"/${webhook.id}", headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      val resp = parsedBody.extract[WebhookDTO]
      resp.id should equal(webhook.id)
      resp.apiUrl should equal(webhook.apiUrl)
      resp.imageUrl should equal(Some(webhook.imageUrl.get))
      resp.description should equal(webhook.description)
      resp.name should equal(webhook.name)
      resp.displayName should equal(webhook.displayName)
      resp.isPrivate should equal(webhook.isPrivate)
      resp.isDefault should equal(webhook.isDefault)
      resp.isDisabled should equal(false)
      resp.createdBy should equal(webhook.createdBy)
      resp.createdAt should equal(webhook.createdAt)
    }
  }

  test("get a list of webhooks") {
    val publicWebhook1 = createWebhook(
      displayName = "Public webhook 1",
      createdBy = loggedInUser.id
    )
    val publicWebhook2 = createWebhook(
      displayName = "Public webhook 2",
      createdBy = loggedInUser.id
    )
    val privateWebhook1 =
      createWebhook(
        displayName = "Private webhook 1 ",
        isPrivate = true,
        createdBy = loggedInUser.id
      )

    get("", headers = authorizationHeader(loggedInJwt)) {
      status shouldBe (200)
      val resp = parsedBody.extract[List[WebhookDTO]]
      resp.length should equal(3)
    }

    get("", headers = authorizationHeader(colleagueJwt)) {
      status shouldBe (200)
      val resp = parsedBody.extract[List[WebhookDTO]]
      resp.length should equal(2)
      resp.map(_.isPrivate).forall(_ == false) should equal(true)
    }
  }

  test("can't get a webhook that doesn't exist") {
    get("/1", headers = authorizationHeader(loggedInJwt)) {
      status shouldBe (404)
      body should include("Webhook (1) not found")
    }
  }

  test("can't get a private webhook created by a different user") {
    val req = write(
      CreateWebhookRequest(
        apiUrl = "https://www.api.com",
        imageUrl = Some("https://www.image.com"),
        description = "something something",
        secret = "secretkey",
        displayName = "Test Webhook",
        targetEvents = Some(List(1, 2)),
        isPrivate = true,
        isDefault = true
      )
    )
    postJson(s"/", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(201)
      val webhook = parsedBody.extract[WebhookDTO]

      get(s"/${webhook.id}", headers = authorizationHeader(colleagueJwt)) {
        status shouldBe (403)
        body should include(
          s"user ${colleagueUser.id} does not have access to webhook ${webhook.id}"
        )
      }
    }
  }

  test("create a webhook") {
    val req = write(
      CreateWebhookRequest(
        apiUrl = "https://www.api.com",
        imageUrl = Some("https://www.image.com"),
        description = "something something",
        secret = "secretkey",
        displayName = "Test Webhook",
        targetEvents = Some(List(1, 2)),
        isPrivate = false,
        isDefault = true
      )
    )

    postJson(s"/", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(201)
      val webhook = parsedBody.extract[WebhookDTO]
      webhook.apiUrl should equal("https://www.api.com")
      webhook.imageUrl should equal(Some("https://www.image.com"))
      webhook.description should equal("something something")
      webhook.name should equal("TEST_WEBHOOK") // name = slugified display name
      webhook.displayName should equal("Test Webhook")
      webhook.isPrivate should equal(false)
      webhook.isDefault should equal(true)
      webhook.isDisabled should equal(false)
      webhook.createdBy should equal(loggedInUser.id)

      get(s"/${webhook.id}", headers = authorizationHeader(loggedInJwt)) {
        status should equal(200)
        parsedBody.extract[WebhookDTO].id shouldBe (webhook.id)
      }
    }
  }

  test("can't create a webhook without an api url") {
    val req = write(
      CreateWebhookRequest(
        apiUrl = "",
        imageUrl = Some("https://www.image.com"),
        description = "something something",
        secret = "secretkey",
        displayName = "Test Webhook",
        targetEvents = Some(List(1, 2)),
        isPrivate = false,
        isDefault = true
      )
    )

    postJson(s"/", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(400)
      body should include(
        "api url must be less than or equal to 255 characters"
      )
    }
  }

  test("can create a webhook without an image url") {
    val req = write(
      CreateWebhookRequest(
        apiUrl = "https://www.api.com",
        imageUrl = None,
        description = "something something",
        secret = "secretkey",
        displayName = "Test Webhook",
        targetEvents = Some(List(1, 2)),
        isPrivate = false,
        isDefault = true
      )
    )

    postJson(s"/", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(201)
      parsedBody.extract[WebhookDTO].imageUrl shouldBe None
    }
  }

  test("can't create a webhook without a secret key") {
    val req = write(
      CreateWebhookRequest(
        apiUrl = "https://www.api.com",
        imageUrl = Some("https://www.image.com"),
        description = "something something",
        secret = "",
        displayName = "Test Webhook",
        targetEvents = Some(List(1, 2)),
        isPrivate = false,
        isDefault = true
      )
    )

    postJson(s"/", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(400)
      body should include("secret must be between 1 and 255 characters")
    }
  }
}
