// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.test.helpers

import cats.data._

import com.blackfynn.core.utilities.SecureCoreContainer
import com.blackfynn.core.utilities.{ SecureContainer, SecureCoreContainer }
import com.blackfynn.domain.{ CoreError, NotFound }
import com.blackfynn.managers.SecureOrganizationManager
import com.blackfynn.models.{ Organization, User }
import com.blackfynn.traits.PostgresProfile.api._

import scala.concurrent.{ ExecutionContext, Future }

class TestableOrganizationManager(
  checkSchema: Boolean,
  db: Database,
  user: User
) extends SecureOrganizationManager(db, user) {

  override def schemaExists(
    organization: Organization
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, String] = {
    if (checkSchema) {
      EitherT(
        Future.successful(Left(NotFound("error!")): Either[CoreError, String])
      )
    } else {
      EitherT(
        Future
          .successful(Right(organization.schemaId): Either[CoreError, String])
      )
    }
  }
}
