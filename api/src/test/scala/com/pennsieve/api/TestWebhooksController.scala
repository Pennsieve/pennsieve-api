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
}
