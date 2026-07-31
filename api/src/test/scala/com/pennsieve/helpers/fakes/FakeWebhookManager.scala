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

package com.pennsieve.helpers.fakes

import com.pennsieve.db.{
  WebhookEventSubscriptionsMapper,
  WebhookEventTypesMapper,
  WebhooksMapper
}
import com.pennsieve.managers.WebhookManager
import com.pennsieve.models.{ Organization, User }
import com.pennsieve.traits.PostgresProfile

/** Skeleton fake. */
class FakeWebhookManager(
  val state: InMemoryState,
  org: Organization,
  val actor: User
) extends WebhookManager {

  def db: PostgresProfile.api.Database =
    sys.error(
      "FakeWebhookManager: a method not yet stubbed by your test tried to " +
        "use the database. Override the method on this fake."
    )

  // Slick mappers, no db access at construction.
  override lazy val webhooksMapper: WebhooksMapper =
    new WebhooksMapper(org)
  override lazy val webhookEventSubscriptionsMapper
    : WebhookEventSubscriptionsMapper =
    new WebhookEventSubscriptionsMapper(org)
  override lazy val webhookEventTypesMapper: WebhookEventTypesMapper =
    new WebhookEventTypesMapper(org)
}
