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

import com.pennsieve.core.utilities.{ checkOrErrorT, slugify }
import com.pennsieve.helpers.DataSetTestMixin
import com.pennsieve.helpers.MockAuditLogger
import com.pennsieve.dtos.WebhookDTO
import com.pennsieve.models.Webhook
import org.json4s.jackson.Serialization.write
import org.scalatest.OptionValues._

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
      checkProperties(resp, webhook, subscriptions)
    }
  }

  test("get a list of webhooks") {

    // Public webhook without event subscriptions
    val publicWebhook1 =
      createWebhook(displayName = "Public webhook 1", targetEvents = None)

    // Public webhook with event subscriptions
    val publicWebhook2 = createWebhook(displayName = "Public webhook 2")

    // Private webbhook with event subscriptions
    val privateWebhook1 =
      createWebhook(displayName = "Private webhook 1 ", isPrivate = true)

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
        targetEvents = Some(List("METADATA", "FILES")),
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
        targetEvents = Some(List("METADATA", "FILES")),
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

  test("Can't create a webhook for non-existing targetEvents") {
    val req = write(
      CreateWebhookRequest(
        apiUrl = "https://www.api.com",
        imageUrl = Some("https://www.image.com"),
        description = "something something",
        secret = "secretkey",
        displayName = "Test Webhook",
        targetEvents = Some(List("METADATA", "FAKETARGET")),
        isPrivate = false,
        isDefault = true
      )
    )

    postJson(s"/", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(400)
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
        targetEvents = Some(List("METADATA", "FILES")),
        isPrivate = false,
        isDefault = true
      )
    )

    postJson(s"/", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(400)
      body should include("api url must be between 1 and 255 characters")
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
        targetEvents = Some(List("METADATA", "FILES")),
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
        targetEvents = Some(List("METADATA", "FILES")),
        isPrivate = false,
        isDefault = true
      )
    )

    postJson(s"/", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(400)
      body should include("secret must be between 1 and 255 characters")
    }
  }

  test("delete a webhook") {
    val webhookSubscription = createWebhook()
    val webhook = webhookSubscription._1

    delete(s"/${webhook.id}", headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      val resp = parsedBody.extract[Int]
      resp should equal(1)
    }
    get(s"/${webhook.id}", headers = authorizationHeader(loggedInJwt)) {
      status shouldBe (404)
      body should include(s"Webhook (${webhook.id}) not found")
    }

  }

  test("can't delete a webhook that doesn't exist") {
    delete("/1", headers = authorizationHeader(loggedInJwt)) {
      status shouldBe (404)
      body should include("Webhook (1) not found")
    }
  }

  test("can't delete another user's webhook") {
    val webhookSubscription = createWebhook()
    val webhook = webhookSubscription._1

    delete(s"/${webhook.id}", headers = authorizationHeader(colleagueJwt)) {
      status should equal(403)
      body should include(
        s"${colleagueUser.nodeId} does not have Administer for Webhook (${webhook.id})"
      )
    }

    get(s"/${webhook.id}", headers = authorizationHeader(loggedInJwt)) {
      status shouldBe (200)
    }

  }

  test("super admin can delete another user's webhook") {
    val webhookSubscription = createWebhook()
    val webhook = webhookSubscription._1

    delete(s"/${webhook.id}", headers = authorizationHeader(adminJwt)) {
      status should equal(200)
      val resp = parsedBody.extract[Int]
      resp should equal(1)
    }

    get(s"/${webhook.id}", headers = authorizationHeader(loggedInJwt)) {
      status shouldBe (404)
    }

  }

  test("organization admin can delete another user's webhook") {
    val colleagueContainer =
      secureContainerBuilder(colleagueUser, loggedInOrganization)
    val webhookSubscription = createWebhook(container = colleagueContainer)
    val webhook = webhookSubscription._1

    //Make sure we're really testing org admin, not super admin
    loggedInUser.isSuperAdmin should be(false)

    delete(s"/${webhook.id}", headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      val resp = parsedBody.extract[Int]
      resp should equal(1)
    }

    get(s"/${webhook.id}", headers = authorizationHeader(colleagueJwt)) {
      status shouldBe (404)
    }

  }

  test("update api url") {
    val (webhook, subscriptions) =
      createWebhook(apiUrl = "https://example.com/api")
    val newApiUrl = webhook.apiUrl + "/v2"
    val req = write(UpdateWebhookRequest(apiUrl = Some(newApiUrl)))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      val updatedWebhook = parsedBody.extract[WebhookDTO]
      val expectedWebhook = webhook.copy(apiUrl = newApiUrl)
      checkProperties(updatedWebhook, expectedWebhook, subscriptions)
    }
  }

  test("update image url") {
    val (webhook, subscriptions) =
      createWebhook(imageUrl = Some("https://example.com/image1.jpg"))
    val newImageUrl = Some("https://example.com/image2.png")
    val req = write(UpdateWebhookRequest(imageUrl = newImageUrl))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      val expectedWebhook = webhook.copy(imageUrl = newImageUrl)
      val updatedWebhook = parsedBody.extract[WebhookDTO]
      checkProperties(updatedWebhook, expectedWebhook, subscriptions)
    }
  }

  test("remove image url") {
    val (webhook, subscriptions) =
      createWebhook(imageUrl = Some("https://example.com/image1.jpg"))
    val newImageUrl = Some("")
    val req = write(UpdateWebhookRequest(imageUrl = newImageUrl))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      val updatedWebhook = parsedBody.extract[WebhookDTO]
      val expectedWebhook = webhook.copy(imageUrl = None)
      checkProperties(updatedWebhook, expectedWebhook, subscriptions)
    }
  }

  test("update description") {
    val (webhook, subscriptions) =
      createWebhook(description = "original description")
    val newDescription = "a new description"
    val req = write(UpdateWebhookRequest(description = Some(newDescription)))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      val updatedWebhook = parsedBody.extract[WebhookDTO]
      val expectedWebhook = webhook.copy(description = newDescription)
      checkProperties(updatedWebhook, expectedWebhook, subscriptions)
    }
  }

  test("update secret") {
    val (webhook, subscriptions) =
      createWebhook(secret = "xyz123")
    val newSecret = "123xyz"
    val req = write(UpdateWebhookRequest(secret = Some(newSecret)))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      val updatedWebhook = parsedBody.extract[WebhookDTO]
      val expectedWebhook = webhook.copy(secret = newSecret)
      checkProperties(updatedWebhook, expectedWebhook, subscriptions)

    }
  }

  test("update display name") {
    val (webhook, subscriptions) =
      createWebhook(displayName = "webhook name")
    val newDisplayName = "new webhook name"
    val req = write(UpdateWebhookRequest(displayName = Some(newDisplayName)))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      val updatedWebhook = parsedBody.extract[WebhookDTO]
      val expectedWebhook = webhook.copy(displayName = newDisplayName)
      checkProperties(updatedWebhook, expectedWebhook, subscriptions)

    }
  }

  test("update isPrivate") {
    val (webhook, subscriptions) =
      createWebhook(isPrivate = true)
    val newIsPrivate = !webhook.isPrivate
    val req = write(UpdateWebhookRequest(isPrivate = Some(newIsPrivate)))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      val updatedWebhook = parsedBody.extract[WebhookDTO]
      val expectedWebhook = webhook.copy(isPrivate = newIsPrivate)
      checkProperties(updatedWebhook, expectedWebhook, subscriptions)

    }
  }

  test("update isDefault") {
    val (webhook, subscriptions) =
      createWebhook(isDefault = true)
    val newIsDefault = !webhook.isDefault
    val req = write(UpdateWebhookRequest(isDefault = Some(newIsDefault)))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      val updatedWebhook = parsedBody.extract[WebhookDTO]
      val expectedWebhook = webhook.copy(isDefault = newIsDefault)
      checkProperties(updatedWebhook, expectedWebhook, subscriptions)

    }
  }

  test("update isDisabled") {
    val (webhook, subscriptions) =
      createWebhook()
    val newIsDisabled = !webhook.isDisabled
    val req = write(UpdateWebhookRequest(isDisabled = Some(newIsDisabled)))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      val updatedWebhook = parsedBody.extract[WebhookDTO]
      val expectedWebhook = webhook.copy(isDisabled = newIsDisabled)
      checkProperties(updatedWebhook, expectedWebhook, subscriptions)

    }
  }

  test("update events") {
    val (webhook, _) =
      createWebhook(targetEvents = Some(List("METADATA", "STATUS")))
    val newSubscriptions = List("STATUS", "FILES")
    val req = write(UpdateWebhookRequest(targetEvents = Some(newSubscriptions)))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      val updatedWebhook = parsedBody.extract[WebhookDTO]
      checkProperties(updatedWebhook, webhook, newSubscriptions)

    }
  }

  test("remove all events") {
    val (webhook, _) =
      createWebhook(targetEvents = Some(List("METADATA", "STATUS")))
    val newSubscriptions = List()
    val req = write(UpdateWebhookRequest(targetEvents = Some(newSubscriptions)))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      val updatedWebhook = parsedBody.extract[WebhookDTO]
      checkProperties(updatedWebhook, webhook, newSubscriptions)

    }
  }

  test("can't remove api url") {
    val (webhook, _) =
      createWebhook()
    val newApiUrl = Some("")
    val req = write(UpdateWebhookRequest(apiUrl = newApiUrl))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(400)
      body should include("api url must be between 1 and 255 characters")
    }
  }

  test("get correct error if updated api url is too long") {
    val (webhook, _) =
      createWebhook()
    val newApiUrl = Some("http://" + "example" * 400 + ".com")
    val req = write(UpdateWebhookRequest(apiUrl = newApiUrl))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(400)
      body should include("api url must be between 1 and 255 characters")
    }
  }

  test("get correct error if updated image url is too long") {
    val (webhook, _) =
      createWebhook()
    val newImageUrl = Some("http://" + "example" * 400 + ".com/image.jpg")
    val req = write(UpdateWebhookRequest(imageUrl = newImageUrl))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(400)
      body should include("image url")
    }
  }

  test("get correct error if updated event does not exist") {
    val (webhook, _) =
      createWebhook()
    val newTargetEvents = Some(List("NON-EVENT"))
    val req = write(UpdateWebhookRequest(targetEvents = newTargetEvents))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(400)
      body should include("unknown event name")
    }
  }

  test("get not found response if updating a webhook that does not exist") {
    val req =
      write(UpdateWebhookRequest(apiUrl = Some("https://example.com/api")))

    putJson(s"/15", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(404)
      body should include(s"Webhook (15) not found")

    }
  }

  test("can't update another user's webhook") {
    val (webhook, _) = createWebhook(apiUrl = "https://example.com/api/v1")
    val req =
      write(UpdateWebhookRequest(apiUrl = Some("https://example.com/api/v2")))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(colleagueJwt)) {
      status should equal(403)
      body should include(
        s"${colleagueUser.nodeId} does not have Administer for Webhook (${webhook.id})"
      )
    }

    get(s"/${webhook.id}", headers = authorizationHeader(loggedInJwt)) {
      status shouldBe (200)
    }

  }

  def checkProperties(
    webserviceResponse: WebhookDTO,
    expectedWebhook: Webhook,
    expectedEvents: Seq[String]
  ): Unit = {

    webserviceResponse.apiUrl should equal(expectedWebhook.apiUrl)

    webserviceResponse.imageUrl should equal(expectedWebhook.imageUrl)

    webserviceResponse.description should equal(expectedWebhook.description)

    val (actualWebhook, _) = secureContainer.webhookManager
      .getWithSubscriptions(expectedWebhook.id)
      .await
      .right
      .get

    actualWebhook.secret should equal(expectedWebhook.secret)

    webserviceResponse.displayName should equal(expectedWebhook.displayName)
    webserviceResponse.name should equal(slugify(expectedWebhook.displayName))

    webserviceResponse.isPrivate should equal(expectedWebhook.isPrivate)

    webserviceResponse.isDisabled should equal(expectedWebhook.isDisabled)

    webserviceResponse.isDefault should equal(expectedWebhook.isDefault)

    webserviceResponse.id should equal(expectedWebhook.id)
    webserviceResponse.createdAt should equal(expectedWebhook.createdAt)
    webserviceResponse.createdBy should equal(expectedWebhook.createdBy)
    if (expectedEvents.isEmpty) {
      webserviceResponse.eventTargets shouldBe None
    } else {
      webserviceResponse.eventTargets.value should contain theSameElementsAs expectedEvents
    }
  }
}
