// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.helpers

import cats.data._
import com.pennsieve.core.utilities.InsecureCoreContainer
import com.pennsieve.domain.CoreError
import com.pennsieve.managers._
import com.pennsieve.models.{ Organization, User }
import com.pennsieve.utilities.Container
import com.pennsieve.traits.PostgresProfile.api._

import scala.concurrent.{ ExecutionContext, Future }

class TestableSecureOrganizationManager(user: User, db: Database)
    extends SecureOrganizationManager(db, user) {

  override def schemaExists(
    organization: Organization
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, String] = {
    EitherT(
      Future
        .successful(Right(organization.schemaId): Either[CoreError, String])
    )
  }
}

trait TestCoreContainer extends InsecureCoreContainer { self: Container =>
  override lazy val userManager: UserManager = new UserManager(db)
  override lazy val sessionManager: SessionManager =
    new SessionManager(redisManager, userManager)
  override lazy val tokenManager: TokenManager = new TokenManager(db)
}
