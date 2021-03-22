// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.helpers

import cats.data._
import com.blackfynn.core.utilities.InsecureCoreContainer
import com.blackfynn.domain.CoreError
import com.blackfynn.managers._
import com.blackfynn.models.{ Organization, User }
import com.blackfynn.utilities.Container
import com.blackfynn.traits.PostgresProfile.api._

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
