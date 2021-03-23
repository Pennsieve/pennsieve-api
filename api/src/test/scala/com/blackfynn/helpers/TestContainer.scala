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
