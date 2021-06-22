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
import com.pennsieve.models.Webhook
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
    val webhook = createWebhook()

    get(s"/${webhook.id}", headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      (parsedBody \ "id").extract[Int] should equal(webhook.id)
      (parsedBody \ "apiUrl").extract[String] should equal(webhook.apiUrl)
      (parsedBody \ "imageUrl").extract[String] should equal(
        webhook.imageUrl.get
      )
      (parsedBody \ "description").extract[String] should equal(
        webhook.description
      )
      (parsedBody \ "name").extract[String] should equal(webhook.name)
      (parsedBody \ "displayName").extract[String] should equal(
        webhook.displayName
      )
      (parsedBody \ "isPrivate").extract[Boolean] should equal(
        webhook.isPrivate
      )
      (parsedBody \ "isDefault").extract[Boolean] should equal(
        webhook.isDefault
      )
      (parsedBody \ "isDisabled").extract[Boolean] should equal(
        webhook.isDisabled
      )
      (parsedBody \ "createdBy").extract[Int] should equal(
        webhook.createdBy.get
      )
      (parsedBody \ "createdAt").extract[ZonedDateTime] should equal(
        webhook.createdAt
      )
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
      webhook.name should equal("TEST_WEBHOOK")
      webhook.displayName should equal("Test Webhook")
      webhook.isPrivate should equal(false)
      webhook.isDefault should equal(true)
      webhook.createdBy should equal(Some(loggedInUser.id))
    }
  }
  
  test("can't create a webhook without api url") {
    val req = write(
      CreateWebhookRequest(
        apiUrl = "",
        imageUrl = Some("https://www.image.com"),
        description = "something something",
        secret = "secretkey",
        displayName = "Test Webhook",
        isPrivate = false,
        isDefault = true
      )
    )

    postJson(s"/", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(400)
      body should include("api url must be less than or equal to 255 characters")
    }
  }
  
  test("can create a webhook without image url") {
    val req = write(
      CreateWebhookRequest(
        apiUrl = "https://www.api.com",
        imageUrl = None,
        description = "something something",
        secret = "secretkey",
        displayName = "Test Webhook",
        isPrivate = false,
        isDefault = true
      )
    )

    postJson(s"/", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(201)
      parsedBody.extract[WebhookDTO].imageUrl shouldBe None
    }
  }
}
