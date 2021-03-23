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

package com.pennsieve.test.helpers

import com.pennsieve.test.helpers.EitherValue._
import com.pennsieve.core.utilities._
import com.pennsieve.managers._

import scala.concurrent.ExecutionContext.Implicits.global

trait SessionSeed[
  SeedContainer <: SessionManagerContainer with OrganizationManagerContainer with UserManagerContainer
] extends CoreSeed[SeedContainer] {

  var sessionManager: SessionManager = _

  var adminSession: Option[String] = None
  var nonAdminSession: Option[String] = None
  var blindReviewerSession: Option[String] = None

  override def seed(container: SeedContainer): Unit = {
    super.seed(container)

    sessionManager = container.sessionManager

    adminSession = Some(
      sessionManager.generateBrowserSession(admin, 6000).await.value.uuid
    )
    nonAdminSession = Some(
      sessionManager.generateBrowserSession(nonAdmin, 6000).await.value.uuid
    )
    blindReviewerSession = Some(
      sessionManager
        .generateBrowserSession(blindReviewer, 6000)
        .await
        .value
        .uuid
    )
  }

}
