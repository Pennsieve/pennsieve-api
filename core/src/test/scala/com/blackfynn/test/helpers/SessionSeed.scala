// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

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
